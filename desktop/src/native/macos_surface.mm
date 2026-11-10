/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * CGL -> IOSurface -> Metal: GPU-only frame handoff. No pixel readback.
 */
#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#include <dlfcn.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <atomic>
#include <string>
#include <chrono>
#include <algorithm>
#include <pthread/qos.h>
#include <condition_variable>
#include <mutex>
#include <cstdlib>

@interface PSMainCall : NSObject
@property(copy) void (^block)(void);
-(void)run;
@end
@implementation PSMainCall
-(void)run { self.block(); }
@end
static void mainCall(void (^block)(void)) {
    if ([NSThread isMainThread]) { block(); return; }
    PSMainCall *call = [PSMainCall new]; call.block = block;
    [call performSelectorOnMainThread:@selector(run) withObject:nil waitUntilDone:YES
        modes:@[NSRunLoopCommonModes, @"AWTRunLoopMode", NSDefaultRunLoopMode]];
}
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point a) { return std::chrono::duration<double,std::milli>(Clock::now()-a).count(); }
struct Timing { std::atomic<double> total{0}, maximum{0}; void add(double v) { total.store(total.load()+v); maximum.store(std::max(maximum.load(),v)); } };
struct Surface {
    NSWindow *overlay, *video;
    CAMetalLayer *layer;
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    id<MTLTexture> shared;
    id<MTLCommandBuffer> inflight;
    IOSurfaceRef io = nullptr;
    CGLContextObj gl = nullptr;
    GLuint texture=0, fbo=0;
    mpv_render_context *render=nullptr;
    int w=0,h=0;
    std::atomic<unsigned long> frames{0};
    std::string receipt;
    Timing renderTime, glWait, drawableWait, metalWait, frameTime, pollGap;
    Clock::time_point previousPollEnd=Clock::now();
    std::atomic<unsigned long> polls{0};
    int qosResult=-1;
    // Diagnostic ablation uses the same rendering path and retains all GPU ownership waits.
    bool paced = std::getenv("SPIKE_LEGACY_PACING")==nullptr;
    std::string glRenderer;
    std::mutex updateMutex; std::condition_variable updateWake;
    std::atomic<bool> updatePending{true};
};
static void *getProc(void *, const char *name) { return dlsym(RTLD_DEFAULT,name); }
extern "C" void ps_destroy(void *ptr);
extern "C" void *ps_create(void *viewPtr, void *mpvPtr) {
    Surface *s = new Surface();
    s->qosResult=pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE,0);
    mainCall(^{
        s->overlay = (__bridge NSWindow*)viewPtr;
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        [s->overlay makeKeyAndOrderFront:nil]; [NSApp activateIgnoringOtherApps:YES];
        s->device = MTLCreateSystemDefaultDevice(); s->queue = [s->device newCommandQueue];
        s->layer = [CAMetalLayer layer]; s->layer.device=s->device;
        s->layer.pixelFormat=MTLPixelFormatRGBA16Float;
        s->layer.framebufferOnly=NO; s->layer.wantsExtendedDynamicRangeContent=YES;
        CGColorSpaceRef cs=CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearITUR_2020);
        s->layer.colorspace=cs; CGColorSpaceRelease(cs);
        NSView *view=[[NSView alloc] initWithFrame:s->overlay.contentView.bounds];
        view.wantsLayer=YES; view.layer=s->layer;
        s->video=[[NSWindow alloc] initWithContentRect:s->overlay.frame styleMask:NSWindowStyleMaskBorderless backing:NSBackingStoreBuffered defer:NO];
        s->video.contentView=view; s->video.ignoresMouseEvents=YES; s->video.releasedWhenClosed=NO;
        s->video.backgroundColor=NSColor.blackColor;
        s->overlay.opaque=NO; s->overlay.backgroundColor=NSColor.clearColor;
        [s->overlay addChildWindow:s->video ordered:NSWindowBelow];
        [s->video orderWindow:NSWindowBelow relativeTo:s->overlay.windowNumber];
    });
    if(!s->overlay || !s->device || !s->video) { ps_destroy(s); return nullptr; }
    CGLPixelFormatAttribute attrs[]={kCGLPFAOpenGLProfile,(CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,kCGLPFAAccelerated,(CGLPixelFormatAttribute)0};
    CGLPixelFormatObj pf; GLint n;
    if(CGLChoosePixelFormat(attrs,&pf,&n)!=kCGLNoError) { ps_destroy(s); return nullptr; }
    auto err=CGLCreateContext(pf,nullptr,&s->gl); CGLDestroyPixelFormat(pf);
    if(err!=kCGLNoError) { ps_destroy(s); return nullptr; }
    CGLSetCurrentContext(s->gl);
    s->glRenderer=(const char*)glGetString(GL_RENDERER);
    mpv_opengl_init_params init={getProc,nullptr};
    mpv_render_param params[]={{MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_OPENGL},{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,&init},{MPV_RENDER_PARAM_INVALID,nullptr}};
    if(mpv_render_context_create(&s->render,(mpv_handle*)mpvPtr,params)<0) { ps_destroy(s); return nullptr; }
    mpv_render_context_set_update_callback(s->render,[](void *p) {
        auto *surface=(Surface*)p;
        { std::lock_guard<std::mutex> lock(surface->updateMutex); surface->updatePending=true; }
        surface->updateWake.notify_one();
    },s);
    return s;
}
extern "C" int ps_frame(void *ptr, int w, int h) {
    @autoreleasepool {
    Surface *s=(Surface*)ptr; CGLSetCurrentContext(s->gl);
    if(s->inflight && s->inflight.status==MTLCommandBufferStatusError) return -6;
    auto frameStarted=Clock::now();
    if(s->polls.fetch_add(1)>0) s->pollGap.add(ms(s->previousPollEnd));
    struct PollEnd { Surface *s; ~PollEnd() { s->previousPollEnd=Clock::now(); } } pollEnd{s};
    bool resized=s->w!=w || s->h!=h;
    if(s->paced && !resized) {
        std::unique_lock<std::mutex> lock(s->updateMutex);
        s->updateWake.wait_for(lock,std::chrono::milliseconds(20),[s]{return s->updatePending.load();});
        s->updatePending=false;
    }
    uint64_t update=mpv_render_context_update(s->render);
    if(s->paced && !(update & MPV_RENDER_UPDATE_FRAME) && !resized) return 0;
    if(w<1||h<1) return -1;
    // Defer the ownership wait until reuse so the blit can finish between frames.
    auto reused=Clock::now();
    if(s->inflight) {
        [s->inflight waitUntilCompleted];
        if(s->inflight.status==MTLCommandBufferStatusError) return -6;
        s->inflight=nil;
    }
    s->metalWait.add(ms(reused));
    if(s->w!=w || s->h!=h) {
        if(s->texture) glDeleteTextures(1,&s->texture);
        if(s->fbo) glDeleteFramebuffers(1,&s->fbo);
        s->shared=nil; if(s->io) CFRelease(s->io);
        NSDictionary *props=@{(id)kIOSurfaceWidth:@(w),(id)kIOSurfaceHeight:@(h),(id)kIOSurfaceBytesPerElement:@8,(id)kIOSurfaceBytesPerRow:@(w*8),(id)kIOSurfacePixelFormat:@((uint32_t)'RGhA')};
        s->io=IOSurfaceCreate((__bridge CFDictionaryRef)props);
        glGenTextures(1,&s->texture); glBindTexture(GL_TEXTURE_RECTANGLE,s->texture);
        if(CGLTexImageIOSurface2D(s->gl,GL_TEXTURE_RECTANGLE,GL_RGBA16F,w,h,GL_RGBA,GL_HALF_FLOAT,s->io,0)!=kCGLNoError) return -2;
        glGenFramebuffers(1,&s->fbo); glBindFramebuffer(GL_FRAMEBUFFER,s->fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,s->texture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) return -3;
        MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
        desc.usage=MTLTextureUsageShaderRead; desc.storageMode=MTLStorageModeShared;
        s->shared=[s->device newTextureWithDescriptor:desc iosurface:s->io plane:0];
        if(!s->shared) return -4;
        glBindTexture(GL_TEXTURE_RECTANGLE,0); glBindFramebuffer(GL_FRAMEBUFFER,0);
        s->w=w; s->h=h;
        mainCall(^{ s->layer.drawableSize=CGSizeMake(w,h); });
    }
    mpv_opengl_fbo fbo={(int)s->fbo,w,h,GL_RGBA16F}; int flip=0; int block=s->paced?0:1; // IOSurface rows are consumed by Metal in top-left order.
    mpv_render_param params[]={{MPV_RENDER_PARAM_OPENGL_FBO,&fbo},{MPV_RENDER_PARAM_FLIP_Y,&flip},{MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,&block},{MPV_RENDER_PARAM_INVALID,nullptr}};
    auto t=Clock::now();
    int rc=mpv_render_context_render(s->render,params); s->renderTime.add(ms(t)); if(rc<0) return rc;
    // Synchronize cross-API ownership; this waits for the GPU, never reads frame pixels.
    t=Clock::now(); glFinish(); s->glWait.add(ms(t));
    t=Clock::now(); id<CAMetalDrawable> drawable=[s->layer nextDrawable]; s->drawableWait.add(ms(t)); if(!drawable) return -5;
    id<MTLCommandBuffer> cmd=[s->queue commandBuffer];
    id<MTLBlitCommandEncoder> blit=[cmd blitCommandEncoder];
    [blit copyFromTexture:s->shared sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(w,h,1) toTexture:drawable.texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
    [blit endEncoding]; [cmd presentDrawable:drawable]; [cmd commit]; s->inflight=cmd;
    mpv_render_context_report_swap(s->render); s->frames++; s->frameTime.add(ms(frameStarted)); return 0;
    }
}
extern "C" const char *ps_receipt(void *ptr) {
    Surface *s=(Surface*)ptr;
    mainCall(^{
        NSScreen *screen=s->overlay.screen; NSRect a=s->overlay.frame,b=screen.frame,c=s->video.frame;
        NSDictionary *d=@{@"layer":@"CAMetalLayer",@"format":@"RGBA16Float",@"colorspace":@"extendedLinearITUR_2020",@"wantsEDR":@(s->layer.wantsExtendedDynamicRangeContent),@"screenMaxEDR":@(screen.maximumExtendedDynamicRangeColorComponentValue),@"screenPotentialEDR":@(screen.maximumPotentialExtendedDynamicRangeColorComponentValue),@"overlayFrame":NSStringFromRect(a),@"screenFrame":NSStringFromRect(b),@"videoFrame":NSStringFromRect(c),@"borderless":@((s->overlay.styleMask&NSWindowStyleMaskTitled)==0),@"device":s->device.name,@"submittedFrames":@(s->frames.load()),@"perFrameCPUReadbacks":@0,@"updateDrivenNonblocking":@(s->paced),@"callbackWake":@(s->paced),@"renderThreadQosResult":@(s->qosResult),@"polls":@(s->polls.load()),@"pollGapMsTotal":@(s->pollGap.total.load()),@"pollGapMsMax":@(s->pollGap.maximum.load()),@"glRenderer":[NSString stringWithUTF8String:s->glRenderer.c_str()],@"renderMsTotal":@(s->renderTime.total.load()),@"renderMsMax":@(s->renderTime.maximum.load()),@"glWaitMsTotal":@(s->glWait.total.load()),@"glWaitMsMax":@(s->glWait.maximum.load()),@"drawableMsTotal":@(s->drawableWait.total.load()),@"drawableMsMax":@(s->drawableWait.maximum.load()),@"metalWaitMsTotal":@(s->metalWait.total.load()),@"metalWaitMsMax":@(s->metalWait.maximum.load()),@"frameMsTotal":@(s->frameTime.total.load()),@"frameMsMax":@(s->frameTime.maximum.load())};
        NSData *data=[NSJSONSerialization dataWithJSONObject:d options:NSJSONWritingPrettyPrinted error:nil];
        s->receipt.assign((const char*)data.bytes,data.length);
    });
    return s->receipt.c_str();
}
extern "C" void ps_sync(void *ptr) {
    Surface *s=(Surface*)ptr;
    mainCall(^{[s->video setFrame:s->overlay.frame display:YES];});
}
extern "C" void ps_fullscreen(void *ptr, int enabled) {
    Surface *s=(Surface*)ptr;
    CFRunLoopPerformBlock(CFRunLoopGetMain(),kCFRunLoopCommonModes, ^{NSApp.presentationOptions=enabled ? NSApplicationPresentationAutoHideDock|NSApplicationPresentationAutoHideMenuBar : NSApplicationPresentationDefault;});
    CFRunLoopWakeUp(CFRunLoopGetMain());
}
// Drain deferred work before the caller declares teardown successful.
extern "C" int ps_finish(void *ptr) {
    Surface *s=(Surface*)ptr;
    if(!s || !s->inflight) return 0;
    [s->inflight waitUntilCompleted];
    bool failed=s->inflight.status==MTLCommandBufferStatusError;
    s->inflight=nil; return failed?-6:0;
}
extern "C" void ps_destroy(void *ptr) {
    Surface *s=(Surface*)ptr; CGLSetCurrentContext(s->gl);
    if(s->inflight) { [s->inflight waitUntilCompleted]; s->inflight=nil; }
    if(s->render) { mpv_render_context_set_update_callback(s->render,nullptr,nullptr); mpv_render_context_free(s->render); }
    if(s->texture) glDeleteTextures(1,&s->texture); if(s->fbo) glDeleteFramebuffers(1,&s->fbo);
    s->shared=nil; if(s->io) CFRelease(s->io); CGLSetCurrentContext(nullptr); if(s->gl) CGLDestroyContext(s->gl);
    mainCall(^{NSApp.presentationOptions=NSApplicationPresentationDefault; [s->overlay removeChildWindow:s->video]; [s->video orderOut:nil]; [s->video close];});
    delete s;
}

// Inject through AppKit's event queue, separately identified from global Robot or direct AWT dispatch.
extern "C" void ps_click(void *ptr, int x, int y) {
    Surface *s=(Surface*)ptr;
    mainCall(^{
        NSPoint p=NSMakePoint(x,s->overlay.contentView.bounds.size.height-y);
        for(NSNumber *kind in @[@(NSEventTypeLeftMouseDown),@(NSEventTypeLeftMouseUp)]) {
            NSEvent *event=[NSEvent mouseEventWithType:(NSEventType)kind.integerValue location:p modifierFlags:0 timestamp:NSProcessInfo.processInfo.systemUptime windowNumber:s->overlay.windowNumber context:nil eventNumber:0 clickCount:1 pressure:1.0];
            [NSApp postEvent:event atStart:NO];
        }
    });
}

extern "C" void *ps_find_window(const char *title) {
    __block NSWindow *found=nil;
    mainCall(^{for(NSWindow *w in NSApp.windows) if([w.title isEqualToString:[NSString stringWithUTF8String:title]]) {found=w;break;}});
    return (__bridge void*)found;
}
