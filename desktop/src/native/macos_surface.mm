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
#include <array>
#include <deque>
#include <fstream>
#include "macos_frame_trace.h"

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
static double referenceWhite(NSWindow *window) {
    return window.screen.maximumExtendedDynamicRangeColorComponentValue>1.0 ? 100.0 : 203.0;
}
extern "C" double ps_edr_reference_white(void *window) {
    __block double result=203; mainCall(^{ result=referenceWhite((__bridge NSWindow*)window); }); return result;
}
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point a) { return std::chrono::duration<double,std::milli>(Clock::now()-a).count(); }
struct Timing { std::atomic<double> total{0}, maximum{0}; void add(double v) { total.store(total.load()+v); maximum.store(std::max(maximum.load(),v)); } };
struct FrameSlot {
    IOSurfaceRef io=nullptr;
    id<MTLTexture> shared;
    id<MTLCommandBuffer> inflight;
    GLuint texture=0, fbo=0;
    GLsync ready=nullptr;
    int64_t targetTime=0;
    uint64_t frameID=0;
};
struct Surface {
    NSWindow *overlay, *video;
    mpv_handle *mpv=nullptr;
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
    // GL -> fence-ready -> Metal -> completed -> reusable. All slot transitions
    // occur on the one render thread; callbacks only wake it.
    std::array<FrameSlot,3> slots;
    std::deque<int> pending;
    unsigned cursor=0;
    bool buffered=std::getenv("SPIKE_MAC_SINGLE_SURFACE")==nullptr && std::getenv("SPIKE_LEGACY_PACING")==nullptr;
    std::atomic<unsigned long> rendered{0}, retiredOnResize{0}, fencePolls{0};
    Timing fenceLatency, targetLead;
    // Metal owns target-time presentation; LayerProbe supplies render headroom.
    bool renderAhead=buffered && std::getenv("SPIKE_MAC_IMMEDIATE")==nullptr;
    std::atomic<unsigned long> timedPresents{0}, latePresents{0};
    std::array<Clock::time_point,3> issued;
    std::shared_ptr<MacFrameTrace> trace;
    NSMutableArray *visibilityObservers;
    uint64_t nextFrameID=0;
    double referenceWhiteNits=203;
    std::atomic<bool> diagnosticCaptured{false};

    std::mutex updateMutex; std::condition_variable updateWake;
    std::atomic<bool> updatePending{true};
};
static void *getProc(void *, const char *name) { return dlsym(RTLD_DEFAULT,name); }
extern "C" void ps_destroy(void *ptr);
extern "C" void *ps_create(void *viewPtr, void *mpvPtr) {
    Surface *s = new Surface(); s->mpv=(mpv_handle*)mpvPtr;
    if(const char *path=std::getenv("SPIKE_MAC_FRAME_TRACE"))
        s->trace=std::make_shared<MacFrameTrace>(path);
    s->qosResult=pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE,0);
    mainCall(^{
        s->overlay = (__bridge NSWindow*)viewPtr;
        s->referenceWhiteNits=referenceWhite(s->overlay);
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
        // Controlled-environment diagnostic, never a playback default. Match the
        // native mpv control's --ontop flag when other GUI pilots share a display.
        if(std::getenv("SPIKE_MAC_TEST_ONTOP")) s->overlay.level=NSFloatingWindowLevel;
        if(s->trace) {
            auto trace=s->trace;
            NSWindow *overlay=s->overlay, *video=s->video;
            void (^record)(NSNotification*)=^(NSNotification*) {
                trace->add(MacFrameTrace::Visibility,0,
                    (overlay.occlusionState&NSWindowOcclusionStateVisible)!=0,
                    NSApp.active,NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier,
                    overlay.windowNumber);
                trace->add(MacFrameTrace::Visibility,0,
                    (video.occlusionState&NSWindowOcclusionStateVisible)!=0,
                    NSApp.active,NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier,
                    video.windowNumber);
            };
            s->visibilityObservers=[NSMutableArray array];
            for(NSString *name in @[NSWindowDidChangeOcclusionStateNotification,
                    NSApplicationDidBecomeActiveNotification,NSApplicationDidResignActiveNotification]) {
                id token=[NSNotificationCenter.defaultCenter addObserverForName:name object:nil
                    queue:NSOperationQueue.mainQueue usingBlock:record];
                [s->visibilityObservers addObject:token];
            }
            record(nil);
        }
    });
    if(!s->overlay || !s->device || !s->video) { ps_destroy(s); return nullptr; }
    // Hosted smoke explicitly permits Apple's software GL producer. The normal
    // physical-display path still requires acceleration; FP16 IOSurface/Metal
    // ownership and presentation remain identical in either context.
    const char *softwareFlag=std::getenv("SPIKE_SOFTWARE_GPU");
    bool softwareAllowed=softwareFlag && std::string(softwareFlag)=="1";
    CGLPixelFormatAttribute attrs[]={kCGLPFAOpenGLProfile,(CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,softwareAllowed?(CGLPixelFormatAttribute)0:kCGLPFAAccelerated,(CGLPixelFormatAttribute)0};
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
        if(surface->trace) surface->trace->add(MacFrameTrace::Wake,0);
        { std::lock_guard<std::mutex> lock(surface->updateMutex); surface->updatePending=true; }
        surface->updateWake.notify_one();
    },s);
    return s;
}
// A GLsync is not a Metal event. Submit a consumer only after the GL fence
// signals; glFlush alone does not establish cross-API completion.
static id<MTLBuffer> encodeDiagnostic(Surface *s,id<MTLBlitCommandEncoder> blit,id<CAMetalDrawable> drawable,int w,int h) {
    if(!std::getenv("SPIKE_HDR_READBACK_DIR") || s->diagnosticCaptured.load() || s->frames.load()<10) return nil;
    NSUInteger stride=((w*8+255)/256)*256;
    id<MTLBuffer> buffer=[s->device newBufferWithLength:stride*h options:MTLResourceStorageModeShared];
    if(buffer) [blit copyFromTexture:drawable.texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(w,h,1) toBuffer:buffer destinationOffset:0 destinationBytesPerRow:stride destinationBytesPerImage:stride*h];
    return buffer;
}
static int writeDiagnostic(Surface *s,id<MTLCommandBuffer> cmd,id<MTLBuffer> buffer,int w,int h) {
    if(!std::getenv("SPIKE_HDR_READBACK_DIR") || s->diagnosticCaptured.load() || s->frames.load()<10) return 0;
    if(!buffer) return -9;
    // One-shot numeric verification only. No wait/readback in normal playback.
    [cmd waitUntilCompleted]; if(cmd.status==MTLCommandBufferStatusError) return -6;
    NSUInteger stride=((w*8+255)/256)*256;
    std::string prefix=std::string(std::getenv("SPIKE_HDR_READBACK_DIR"))+"/drawable";
    std::ofstream raw(prefix+".rgba16f",std::ios::binary);
    for(int row=0;row<h;row++) raw.write((const char*)buffer.contents+row*stride,w*8);
    raw.close(); if(!raw) return -9;
    std::ofstream meta(prefix+".json");
    meta<<"{\"width\":"<<w<<",\"height\":"<<h<<",\"format\":\"RGBA16Float\",\"boundary\":\"actual CAMetalLayer drawable before WindowServer\",\"encoding\":\"linear\",\"primaries\":\"bt2020\",\"referenceWhiteNits\":"<<s->referenceWhiteNits<<"}\n";
    meta.close(); if(!meta) return -9;
    s->diagnosticCaptured.store(true); return 0;
}
static int presentReady(Surface *s) {
    while(!s->pending.empty()) {
        int index=s->pending.front(); auto &slot=s->slots[index];
        s->fencePolls++;
        GLenum state=glClientWaitSync(slot.ready,0,0);
        if(state==GL_WAIT_FAILED) return -7;
        if(state==GL_TIMEOUT_EXPIRED) break;
        s->fenceLatency.add(ms(s->issued[index]));
        if(s->trace) s->trace->add(MacFrameTrace::Fence,slot.frameID,
            CACurrentMediaTime()-ms(s->issued[index])/1000.0,CACurrentMediaTime(),index);
        glDeleteSync(slot.ready); slot.ready=nullptr; s->pending.pop_front();
        auto t=Clock::now(); double drawableBegin=CACurrentMediaTime();
        id<CAMetalDrawable> drawable=[s->layer nextDrawable];
        s->drawableWait.add(ms(t)); if(!drawable) return -5;
        double drawableEnd=CACurrentMediaTime();
        id<MTLCommandBuffer> cmd=[s->queue commandBuffer];
        id<MTLBlitCommandEncoder> blit=[cmd blitCommandEncoder];
        [blit copyFromTexture:slot.shared sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(s->w,s->h,1) toTexture:drawable.texture destinationSlice:0
            destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
        id<MTLBuffer> diagnostic=encodeDiagnostic(s,blit,drawable,s->w,s->h);
        [blit endEncoding];
        double targetHost=0;
        if(s->renderAhead && slot.targetTime>0) {
            // In pinned mpv 0.42 the implementation assigns vo_frame::pts (ns)
            // to target_time, despite the public header's stale microsecond text.
            // Fail on a clock/unit mismatch instead of queuing a distant drawable.
            int64_t remaining=slot.targetTime-mpv_get_time_ns(s->mpv);
            targetHost=CACurrentMediaTime()+remaining/1e9;
            // Past deadlines can occur across seek/pause or host stalls. Present
            // them immediately and count them as late; only a far future time
            // could pin a drawable indefinitely.
            if(remaining>1000000000LL) return -8;
            s->targetLead.add(remaining/1e6);
            if(remaining>0) [cmd presentDrawable:drawable atTime:CACurrentMediaTime()+remaining/1e9];
            else { [cmd presentDrawable:drawable]; s->latePresents++; }
            s->timedPresents++;
        } else [cmd presentDrawable:drawable];
        if(s->trace) {
            auto trace=s->trace; uint64_t frame=slot.frameID;
            trace->add(MacFrameTrace::Submit,frame,targetHost,drawableBegin,drawableEnd,drawable.drawableID);
            trace->expectPresentation();
            [drawable addPresentedHandler:^(id<MTLDrawable> shown) {
                trace->presented(frame,shown.presentedTime,shown.drawableID);
            }];
            [cmd addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                trace->add(MacFrameTrace::Completed,frame,completed.GPUStartTime,completed.GPUEndTime,completed.status);
            }];
        }
        [cmd commit]; slot.inflight=cmd;
        int diagnosticResult=writeDiagnostic(s,cmd,diagnostic,s->w,s->h); if(diagnosticResult<0) return diagnosticResult;
        mpv_render_context_report_swap(s->render); s->frames++;
    }
    return 0;
}
static int drainSlots(Surface *s) {
    glFinish();
    int result=0;
    for(auto &slot:s->slots) {
        if(slot.ready) {glDeleteSync(slot.ready); slot.ready=nullptr; s->retiredOnResize++;}
        if(slot.inflight) {
            [slot.inflight waitUntilCompleted];
            if(slot.inflight.status==MTLCommandBufferStatusError) result=-6;
            slot.inflight=nil;
        }
    }
    s->pending.clear(); return result;
}
static void releaseSlots(Surface *s) {
    for(auto &slot:s->slots) {
        if(slot.texture) glDeleteTextures(1,&slot.texture);
        if(slot.fbo) glDeleteFramebuffers(1,&slot.fbo);
        slot.texture=slot.fbo=0; slot.shared=nil;
        if(slot.io) { CFRelease(slot.io); slot.io=nullptr; }
    }
}
static int sizeSlots(Surface *s,int w,int h) {
    int rc=drainSlots(s); if(rc<0) return rc;
    releaseSlots(s);
    for(auto &slot:s->slots) {
        NSDictionary *props=@{(id)kIOSurfaceWidth:@(w),(id)kIOSurfaceHeight:@(h),
            (id)kIOSurfaceBytesPerElement:@8,(id)kIOSurfaceBytesPerRow:@(w*8),
            (id)kIOSurfacePixelFormat:@((uint32_t)'RGhA')};
        slot.io=IOSurfaceCreate((__bridge CFDictionaryRef)props); if(!slot.io) return -2;
        glGenTextures(1,&slot.texture); glBindTexture(GL_TEXTURE_RECTANGLE,slot.texture);
        if(CGLTexImageIOSurface2D(s->gl,GL_TEXTURE_RECTANGLE,GL_RGBA16F,w,h,GL_RGBA,GL_HALF_FLOAT,slot.io,0)!=kCGLNoError) return -2;
        glGenFramebuffers(1,&slot.fbo); glBindFramebuffer(GL_FRAMEBUFFER,slot.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,slot.texture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) return -3;
        MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
        desc.usage=MTLTextureUsageShaderRead; desc.storageMode=MTLStorageModeShared;
        slot.shared=[s->device newTextureWithDescriptor:desc iosurface:slot.io plane:0];
        if(!slot.shared) return -4;
    }
    glBindTexture(GL_TEXTURE_RECTANGLE,0); glBindFramebuffer(GL_FRAMEBUFFER,0);
    s->w=w; s->h=h; s->cursor=0;
    mainCall(^{s->layer.drawableSize=CGSizeMake(w,h);}); return 0;
}
static int bufferedFrame(Surface *s,int w,int h) {
    if(w<1||h<1) return -1;
    auto started=Clock::now();
    bool resized=s->w!=w || s->h!=h;
    if(resized) {int rc=sizeSlots(s,w,h); if(rc<0) return rc;}
    int rc=presentReady(s); if(rc<0) return rc;
    int freeSlot=-1;
    for(unsigned n=0;n<s->slots.size();n++) {
        unsigned i=(s->cursor+n)%s->slots.size(); auto &slot=s->slots[i];
        if(slot.inflight && slot.inflight.status==MTLCommandBufferStatusError) return -6;
        if(slot.ready) continue;
        if(slot.inflight && slot.inflight.status!=MTLCommandBufferStatusCompleted) continue;
        slot.inflight=nil; freeSlot=(int)i; break;
    }
    // Never consume an mpv update if no target can be acquired. Backpressure
    // remains bounded and mpv's own VO counters account for missed deadlines.
    if(freeSlot<0) {std::unique_lock<std::mutex> lock(s->updateMutex);
        s->updateWake.wait_for(lock,std::chrono::milliseconds(1)); return 0;}
    if(!resized) {
        std::unique_lock<std::mutex> lock(s->updateMutex);
        auto timeout=s->pending.empty()?std::chrono::milliseconds(20):std::chrono::milliseconds(1);
        s->updateWake.wait_for(lock,timeout,[s]{return s->updatePending.load();});
        s->updatePending=false;
    }
    rc=presentReady(s); if(rc<0) return rc;
    uint64_t update=mpv_render_context_update(s->render);
    if(!(update & MPV_RENDER_UPDATE_FRAME) && !resized) return 0;
    auto &slot=s->slots[freeSlot];
    slot.frameID=++s->nextFrameID;
    slot.targetTime=0;
    if(s->renderAhead) {
        mpv_render_frame_info info={};
        mpv_render_param query={MPV_RENDER_PARAM_NEXT_FRAME_INFO,&info};
        if(mpv_render_context_get_info(s->render,query)<0) return -8;
        if((info.flags&MPV_RENDER_FRAME_INFO_PRESENT) && !(info.flags&MPV_RENDER_FRAME_INFO_REDRAW))
            slot.targetTime=info.target_time;
    }
    mpv_opengl_fbo fbo={(int)slot.fbo,w,h,GL_RGBA16F}; int flip=0, block=0;
    mpv_render_param params[]={{MPV_RENDER_PARAM_OPENGL_FBO,&fbo},{MPV_RENDER_PARAM_FLIP_Y,&flip},
        {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,&block},{MPV_RENDER_PARAM_INVALID,nullptr}};
    auto t=Clock::now(); double renderBegin=CACurrentMediaTime();
    rc=mpv_render_context_render(s->render,params);
    if(s->trace) {
        qos_class_t qos=QOS_CLASS_UNSPECIFIED;
        pthread_get_qos_class_np(pthread_self(),&qos,nullptr);
        s->trace->add(MacFrameTrace::Render,slot.frameID,renderBegin,CACurrentMediaTime(),
            slot.targetTime?CACurrentMediaTime()+(slot.targetTime-mpv_get_time_ns(s->mpv))/1e9:0,qos);
    }
    s->renderTime.add(ms(t)); if(rc<0) return rc;
    slot.ready=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0); if(!slot.ready) return -7;
    s->issued[freeSlot]=Clock::now(); glFlush();
    s->pending.push_back(freeSlot); s->cursor=(freeSlot+1)%s->slots.size();
    s->rendered++; s->frameTime.add(ms(started)); return 0;
}

extern "C" int ps_frame(void *ptr, int w, int h) {
    @autoreleasepool {
    Surface *s=(Surface*)ptr; CGLSetCurrentContext(s->gl);
    if(s->inflight && s->inflight.status==MTLCommandBufferStatusError) return -6;
    auto frameStarted=Clock::now();
    if(s->polls.fetch_add(1)>0) s->pollGap.add(ms(s->previousPollEnd));
    struct PollEnd { Surface *s; ~PollEnd() { s->previousPollEnd=Clock::now(); } } pollEnd{s};
    if(s->buffered) return bufferedFrame(s,w,h);
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
    id<MTLBuffer> diagnostic=encodeDiagnostic(s,blit,drawable,w,h);
    [blit endEncoding]; [cmd presentDrawable:drawable]; [cmd commit]; s->inflight=cmd;
    int diagnosticResult=writeDiagnostic(s,cmd,diagnostic,w,h); if(diagnosticResult<0) return diagnosticResult;
    mpv_render_context_report_swap(s->render); s->frames++; s->frameTime.add(ms(frameStarted)); return 0;
    }
}
extern "C" const char *ps_receipt(void *ptr) {
    Surface *s=(Surface*)ptr;
    mainCall(^{
        NSScreen *screen=s->overlay.screen; NSRect a=s->overlay.frame,b=screen.frame,c=s->video.frame;
        NSDictionary *d=@{@"testOntop":@(std::getenv("SPIKE_MAC_TEST_ONTOP")!=nullptr),@"overlayWindowNumber":@(s->overlay.windowNumber),@"videoWindowNumber":@(s->video.windowNumber),@"referenceWhiteNits":@(s->referenceWhiteNits),@"numericDrawableReadback":@(s->diagnosticCaptured.load()),@"displayID":screen.deviceDescription[@"NSScreenNumber"] ?: NSNull.null,@"maximumFramesPerSecond":@(screen.maximumFramesPerSecond),@"displaySyncEnabled":@(s->layer.displaySyncEnabled),@"maximumDrawableCount":@(s->layer.maximumDrawableCount),@"frameTraceEnabled":@(s->trace!=nullptr),@"applicationActive":@(NSApp.active),@"overlayVisible":@(s->overlay.visible),@"onActiveSpace":@(s->overlay.onActiveSpace),@"occlusionVisible":@((s->overlay.occlusionState&NSWindowOcclusionStateVisible)!=0),@"layer":@"CAMetalLayer",@"format":@"RGBA16Float",@"colorspace":@"extendedLinearITUR_2020",@"wantsEDR":@(s->layer.wantsExtendedDynamicRangeContent),@"screenMaxEDR":@(screen.maximumExtendedDynamicRangeColorComponentValue),@"screenPotentialEDR":@(screen.maximumPotentialExtendedDynamicRangeColorComponentValue),@"overlayFrame":NSStringFromRect(a),@"screenFrame":NSStringFromRect(b),@"videoFrame":NSStringFromRect(c),@"borderless":@((s->overlay.styleMask&NSWindowStyleMaskTitled)==0),@"device":s->device.name,@"submittedFrames":@(s->frames.load()),@"perFrameCPUReadbacks":@0,@"renderAhead":@(s->renderAhead),@"timedPresents":@(s->timedPresents.load()),@"latePresents":@(s->latePresents.load()),@"targetLeadMsTotal":@(s->targetLead.total.load()),@"targetLeadMsMax":@(s->targetLead.maximum.load()),@"bufferedIOSurfaces":@(s->buffered?3:1),@"renderedFrames":@(s->rendered.load()),@"retiredOnResize":@(s->retiredOnResize.load()),@"fencePolls":@(s->fencePolls.load()),@"fenceLatencyMsTotal":@(s->fenceLatency.total.load()),@"fenceLatencyMsMax":@(s->fenceLatency.maximum.load()),@"updateDrivenNonblocking":@(s->paced),@"callbackWake":@(s->paced),@"renderThreadQosResult":@(s->qosResult),@"polls":@(s->polls.load()),@"pollGapMsTotal":@(s->pollGap.total.load()),@"pollGapMsMax":@(s->pollGap.maximum.load()),@"glRenderer":[NSString stringWithUTF8String:s->glRenderer.c_str()],@"renderMsTotal":@(s->renderTime.total.load()),@"renderMsMax":@(s->renderTime.maximum.load()),@"glWaitMsTotal":@(s->glWait.total.load()),@"glWaitMsMax":@(s->glWait.maximum.load()),@"drawableMsTotal":@(s->drawableWait.total.load()),@"drawableMsMax":@(s->drawableWait.maximum.load()),@"metalWaitMsTotal":@(s->metalWait.total.load()),@"metalWaitMsMax":@(s->metalWait.maximum.load()),@"frameMsTotal":@(s->frameTime.total.load()),@"frameMsMax":@(s->frameTime.maximum.load())};
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
    if(!s) return 0;
    CGLSetCurrentContext(s->gl);
    if(s->buffered) return drainSlots(s);
    if(!s->inflight) return 0;
    [s->inflight waitUntilCompleted];
    bool failed=s->inflight.status==MTLCommandBufferStatusError;
    s->inflight=nil; return failed?-6:0;
}
extern "C" void ps_destroy(void *ptr) {
    Surface *s=(Surface*)ptr; CGLSetCurrentContext(s->gl);
    if(s->inflight) { [s->inflight waitUntilCompleted]; s->inflight=nil; }
    if(s->gl) { drainSlots(s); releaseSlots(s); }
    if(s->render) { mpv_render_context_set_update_callback(s->render,nullptr,nullptr); mpv_render_context_free(s->render); }
    mainCall(^{
        for(id token in s->visibilityObservers) [NSNotificationCenter.defaultCenter removeObserver:token];
        s->visibilityObservers=nil;
    });
    if(s->trace) s->trace->write();
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
