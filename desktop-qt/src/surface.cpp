// SPDX-License-Identifier: MPL-2.0
#include "surface.h"
#include <rhi/qrhi.h>
#include <rhi/qrhi_platform.h>
#include <QOpenGLContext>
#include <QOpenGLExtraFunctions>
#include <mpv/render_gl.h>
#ifdef Q_OS_WIN
#include <d3d11.h>
#include <mpv/render_d3d11.h>
#endif
Player *Surface::player=nullptr;
class Renderer:public QQuickRhiItemRenderer {
 Player *p=Surface::player;
 mpv_render_context *ctx=nullptr;
 unsigned int fbo=0;
 void *mac=nullptr;
 bool ready=false;
 static void *getProc(void*,const char*n){return reinterpret_cast<void*>(QOpenGLContext::currentContext()->getProcAddress(n));}
 void initialize(QRhiCommandBuffer*) override {
  if(ready)return;
#ifdef Q_OS_MACOS
  auto *h=static_cast<const QRhiMetalNativeHandles*>(rhi()->nativeHandles());mac=macCreate(h->dev,p);ready=true;
#else
  mpv_render_param params[3]{};
#ifdef Q_OS_WIN
  auto *h=static_cast<const QRhiD3D11NativeHandles*>(rhi()->nativeHandles());
  mpv_d3d11_init_params init{h->dev};
  params[0]={MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_D3D11};params[1]={MPV_RENDER_PARAM_D3D11_INIT_PARAMS,&init};
#else
  mpv_opengl_init_params init{getProc,nullptr};
  params[0]={MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_OPENGL};params[1]={MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,&init};
  auto *gl=QOpenGLContext::currentContext()->extraFunctions();gl->glGenFramebuffers(1,&fbo);
#endif
  int e=p->api.render_context_create(&ctx,p->handle,params);if(e<0)qFatal("mpv render context %d",e);ready=true;
#endif
  qInfo()<<"RENDER_READY backend"<<rhi()->backend()<<"format"<<colorTexture()->format();
 }
 void synchronize(QQuickRhiItem*) override {}
 void render(QRhiCommandBuffer *cb) override {
  auto size=colorTexture()->pixelSize();
  cb->beginExternal();
#ifdef Q_OS_MACOS
  auto *h=static_cast<const QRhiMetalCommandBufferNativeHandles*>(cb->nativeHandles());
  macDraw(mac,reinterpret_cast<void*>(colorTexture()->nativeTexture().object),h->commandBuffer,size.width(),size.height());
#else
#ifdef Q_OS_WIN
  auto *h=static_cast<const QRhiD3D11NativeHandles*>(rhi()->nativeHandles());static_cast<ID3D11DeviceContext*>(h->context)->ClearState();
  mpv_d3d11_fbo target{reinterpret_cast<void*>(colorTexture()->nativeTexture().object)};
  mpv_render_param params[]={{MPV_RENDER_PARAM_D3D11_FBO,&target},{MPV_RENDER_PARAM_INVALID,nullptr}};
#else
  auto *gl=QOpenGLContext::currentContext()->extraFunctions();gl->glBindFramebuffer(GL_FRAMEBUFFER,fbo);gl->glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,GLuint(colorTexture()->nativeTexture().object),0);
  mpv_opengl_fbo target{int(fbo),size.width(),size.height(),GL_RGBA16F};int flip=1;
  mpv_render_param params[]={{MPV_RENDER_PARAM_OPENGL_FBO,&target},{MPV_RENDER_PARAM_FLIP_Y,&flip},{MPV_RENDER_PARAM_INVALID,nullptr}};
#endif
  int e=p->api.render_context_render(ctx,params);if(e<0)qFatal("render failed %d",e);p->api.render_context_report_swap(ctx);
#endif
  cb->endExternal();update();
 }
public:
 ~Renderer() override {
#ifdef Q_OS_MACOS
  if(mac)macDestroy(mac);
#else
  if(ctx)p->api.render_context_free(ctx);
#ifndef Q_OS_WIN
  if(fbo && QOpenGLContext::currentContext())QOpenGLContext::currentContext()->extraFunctions()->glDeleteFramebuffers(1,&fbo);
#endif
#endif
 qInfo()<<"RENDER_TEARDOWN_COMPLETE";
 }
};
QQuickRhiItemRenderer *Surface::createRenderer(){return new Renderer;}
