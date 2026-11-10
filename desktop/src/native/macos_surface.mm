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
struct Surface {
    NSWindow *overlay, *video;
    CAMetalLayer *layer;
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    id<MTLTexture> shared;
    IOSurfaceRef io = nullptr;
    CGLContextObj gl = nullptr;
    GLuint texture=0, fbo=0;
    mpv_render_context *render=nullptr;
    int w=0,h=0;
    std::atomic<unsigned long> frames{0};
    std::string receipt;
};
static void *getProc(void *, const char *name) { return dlsym(RTLD_DEFAULT,name); }
extern "C" void ps_destroy(void *ptr);
extern "C" void *ps_create(void *viewPtr, void *mpvPtr) {
    Surface *s = new Surface();
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
    mpv_opengl_init_params init={getProc,nullptr};
    mpv_render_param params[]={{MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_OPENGL},{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,&init},{MPV_RENDER_PARAM_INVALID,nullptr}};
    if(mpv_render_context_create(&s->render,(mpv_handle*)mpvPtr,params)<0) { ps_destroy(s); return nullptr; }
    return s;
}
extern "C" int ps_frame(void *ptr, int w, int h) {
    @autoreleasepool {
    Surface *s=(Surface*)ptr; CGLSetCurrentContext(s->gl);
    if(w<1||h<1) return -1;
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
    mpv_opengl_fbo fbo={(int)s->fbo,w,h,GL_RGBA16F}; int flip=1;
    mpv_render_param params[]={{MPV_RENDER_PARAM_OPENGL_FBO,&fbo},{MPV_RENDER_PARAM_FLIP_Y,&flip},{MPV_RENDER_PARAM_INVALID,nullptr}};
    int rc=mpv_render_context_render(s->render,params); if(rc<0) return rc;
    // Synchronize cross-API ownership; this waits for the GPU, never reads frame pixels.
    glFinish();
    id<CAMetalDrawable> drawable=[s->layer nextDrawable]; if(!drawable) return -5;
    id<MTLCommandBuffer> cmd=[s->queue commandBuffer];
    id<MTLBlitCommandEncoder> blit=[cmd blitCommandEncoder];
    [blit copyFromTexture:s->shared sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(w,h,1) toTexture:drawable.texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
    [blit endEncoding]; [cmd presentDrawable:drawable]; [cmd commit]; [cmd waitUntilCompleted];
    if(cmd.status==MTLCommandBufferStatusError) return -6;
    mpv_render_context_report_swap(s->render); s->frames++; return 0;
    }
}
extern "C" const char *ps_receipt(void *ptr) {
    Surface *s=(Surface*)ptr;
    mainCall(^{
        NSScreen *screen=s->overlay.screen; NSRect a=s->overlay.frame,b=screen.frame,c=s->video.frame;
        NSDictionary *d=@{@"layer":@"CAMetalLayer",@"format":@"RGBA16Float",@"colorspace":@"extendedLinearITUR_2020",@"wantsEDR":@(s->layer.wantsExtendedDynamicRangeContent),@"screenMaxEDR":@(screen.maximumExtendedDynamicRangeColorComponentValue),@"screenPotentialEDR":@(screen.maximumPotentialExtendedDynamicRangeColorComponentValue),@"overlayFrame":NSStringFromRect(a),@"screenFrame":NSStringFromRect(b),@"videoFrame":NSStringFromRect(c),@"borderless":@((s->overlay.styleMask&NSWindowStyleMaskTitled)==0),@"device":s->device.name,@"submittedFrames":@(s->frames.load()),@"perFrameCPUReadbacks":@0};
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
extern "C" void ps_destroy(void *ptr) {
    Surface *s=(Surface*)ptr; CGLSetCurrentContext(s->gl);
    if(s->render) mpv_render_context_free(s->render);
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
