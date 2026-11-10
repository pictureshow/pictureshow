// SPDX-License-Identifier: MPL-2.0
// CGL FP16 IOSurface → Metal blit → Qt Quick FP16 scene/swapchain. No CPU pixels.
#include "surface.h"
#include <QJsonArray>
#import <objc/runtime.h>
#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#include <dlfcn.h>
#include <mpv/render_gl.h>
struct Mac {
 Player *p; CGLContextObj gl=nullptr; mpv_render_context *ctx=nullptr;
 IOSurfaceRef surface=nullptr; id<MTLDevice> device; id<MTLTexture> texture=nil;
 id<MTLCommandBuffer> pending=nil;
 GLuint fbo=0,tex=0; int w=0,h=0;
};
static void *proc(void*,const char*n){return dlsym(RTLD_DEFAULT,n);}
void *macCreate(void *device,Player *p){
 auto *s=new Mac;s->p=p;s->device=[(id<MTLDevice>)device retain];
 CGLPixelFormatAttribute attrs[]={kCGLPFAOpenGLProfile,(CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,kCGLPFAAccelerated,kCGLPFAAllowOfflineRenderers,(CGLPixelFormatAttribute)0};
 CGLPixelFormatObj pf;GLint count;auto err=CGLChoosePixelFormat(attrs,&pf,&count);if(err)qFatal("CGLChoosePixelFormat %d",err);
 err=CGLCreateContext(pf,nullptr,&s->gl);CGLDestroyPixelFormat(pf);if(err)qFatal("CGLCreateContext %d",err);
 CGLSetCurrentContext(s->gl);glGenFramebuffers(1,&s->fbo);glGenTextures(1,&s->tex);
 mpv_opengl_init_params init{proc,nullptr};mpv_render_param params[]={{MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_OPENGL},{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,&init},{MPV_RENDER_PARAM_INVALID,nullptr}};
 int e=p->api.render_context_create(&s->ctx,p->handle,params);if(e<0)qFatal("mpv CGL initialize %d",e);
 qInfo()<<"MAC_BRIDGE CGL IOSurface RGBA16F -> Metal; glFinish synchronization; no CPU readback";return s;
}
void macDraw(void *state,void *target,void *buffer,int w,int h){
 auto *s=(Mac*)state;
 if(s->pending){[s->pending waitUntilCompleted];[s->pending release];s->pending=nil;}
 CGLSetCurrentContext(s->gl);
 if(w!=s->w||h!=s->h){
  if(s->texture)[s->texture release];if(s->surface)CFRelease(s->surface);
  NSDictionary *props=@{(id)kIOSurfaceWidth:@(w),(id)kIOSurfaceHeight:@(h),(id)kIOSurfaceBytesPerElement:@8,(id)kIOSurfaceBytesPerRow:@(w*8),(id)kIOSurfacePixelFormat:@((uint32_t)'RGhA')};
  s->surface=IOSurfaceCreate((CFDictionaryRef)props);if(!s->surface)qFatal("IOSurfaceCreate failed");
  glBindTexture(GL_TEXTURE_RECTANGLE,s->tex);
  auto e=CGLTexImageIOSurface2D(s->gl,GL_TEXTURE_RECTANGLE,GL_RGBA16F,w,h,GL_RGBA,GL_HALF_FLOAT,s->surface,0);if(e)qFatal("IOSurface CGL bind %d",e);
  glTexParameteri(GL_TEXTURE_RECTANGLE,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_RECTANGLE,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
  glBindFramebuffer(GL_FRAMEBUFFER,s->fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,s->tex,0);
  if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)qFatal("IOSurface FBO incomplete");
  auto *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];desc.storageMode=MTLStorageModeShared;desc.usage=MTLTextureUsageShaderRead;
  s->texture=[s->device newTextureWithDescriptor:desc iosurface:s->surface plane:0];if(!s->texture)qFatal("Metal IOSurface texture failed");s->w=w;s->h=h;
 }
 glBindFramebuffer(GL_FRAMEBUFFER,s->fbo);mpv_opengl_fbo fbo{int(s->fbo),w,h,GL_RGBA16F};int flip=0;
 mpv_render_param params[]={{MPV_RENDER_PARAM_OPENGL_FBO,&fbo},{MPV_RENDER_PARAM_FLIP_Y,&flip},{MPV_RENDER_PARAM_INVALID,nullptr}};
 int e=s->p->api.render_context_render(s->ctx,params);if(e<0)qFatal("CGL render %d",e);
 glFinish(); // completes GL writes before Metal consumes the shared IOSurface
 auto cb=(id<MTLCommandBuffer>)buffer;if(!cb)qFatal("Missing Qt Metal command buffer");
 id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
 [blit copyFromTexture:s->texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(w,h,1) toTexture:(id<MTLTexture>)target destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[blit endEncoding];s->pending=[cb retain];
 s->p->api.render_context_report_swap(s->ctx);
}
void macDestroy(void *state){auto*s=(Mac*)state;if(s->pending){[s->pending waitUntilCompleted];[s->pending release];}CGLSetCurrentContext(s->gl);s->p->api.render_context_free(s->ctx);glDeleteTextures(1,&s->tex);glDeleteFramebuffers(1,&s->fbo);if(s->texture)[s->texture release];if(s->surface)CFRelease(s->surface);CGLSetCurrentContext(nullptr);CGLDestroyContext(s->gl);[s->device release];delete s;}
static QJsonArray rect(NSRect r){return {r.origin.x,r.origin.y,r.size.width,r.size.height};}
static void layers(CALayer *l,QJsonArray &a){if([l isKindOfClass:[CAMetalLayer class]]){auto *m=(CAMetalLayer*)l;a.append(QJsonObject{{"class",QString::fromUtf8(object_getClassName(m))},{"pixelFormat",int(m.pixelFormat)},{"wantsExtendedDynamicRangeContent",bool(m.wantsExtendedDynamicRangeContent)},{"colorspace",m.colorspace?QString::fromUtf8(CFStringGetCStringPtr(CGColorSpaceGetName(m.colorspace),kCFStringEncodingUTF8)):QString()},{"drawableWidth",m.drawableSize.width},{"drawableHeight",m.drawableSize.height}});}for(CALayer *s in l.sublayers)layers(s,a);}
QJsonObject macReceipt(WId id){NSView *v=(NSView*)id;NSWindow*w=v.window;NSScreen*s=w.screen;QJsonArray a;layers(v.layer,a);return {{"windowFrame",rect(w.frame)},{"screenFrame",rect(s.frame)},{"styleMask",qint64(w.styleMask)},{"nativeFullscreen",bool(w.styleMask&NSWindowStyleMaskFullScreen)},{"screenMaximumEDR",s.maximumExtendedDynamicRangeColorComponentValue},{"screenPotentialEDR",s.maximumPotentialExtendedDynamicRangeColorComponentValue},{"screenReferenceEDR",s.maximumReferenceExtendedDynamicRangeColorComponentValue},{"metalLayers",a}};}

void macActivate(WId id){[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];[NSApp activateIgnoringOtherApps:YES];NSWindow*w=((NSView*)id).window;[w makeKeyAndOrderFront:nil];}

void macImmersive(WId id,bool enabled){NSWindow*w=((NSView*)id).window;[NSApp setPresentationOptions:enabled?(NSApplicationPresentationAutoHideDock|NSApplicationPresentationAutoHideMenuBar):NSApplicationPresentationDefault];if(enabled)[w setFrame:w.screen.frame display:YES];macActivate(id);}
