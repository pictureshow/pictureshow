/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Native EGL video with a separate SDR Compose shm subsurface. No video readback.
 */
#include <wayland-client.h>
#include <wayland-egl.h>
#include <EGL/egl.h>
#include <GL/gl.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include "xdg-shell-client-protocol.h"
#include "color-management-v1-client-protocol.h"
#include "weston-output-capture-client-protocol.h"
#include "weston-test-client-protocol.h"
#include <algorithm>
#include <atomic>
#include <thread>
#include <mutex>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>
#include <string>

struct Buffer {wl_buffer*b=nullptr; void*data=nullptr; size_t n=0; bool busy=false;};
struct Surface {
 wl_display*d=nullptr;wl_compositor*c=nullptr;wl_subcompositor*sc=nullptr;wl_shm*shm=nullptr;
 xdg_wm_base*wm=nullptr;wl_surface*video=nullptr,*ui=nullptr;wl_subsurface*sub=nullptr;
 xdg_surface*xdg=nullptr;xdg_toplevel*top=nullptr;wl_output*output=nullptr;
 wp_color_manager_v1*cm=nullptr;wp_image_description_v1*desc=nullptr;wp_color_management_surface_v1*cms=nullptr;
 weston_capture_v1*capture=nullptr;weston_capture_source_v1*shot=nullptr;weston_test*test=nullptr;
 wl_seat*seat=nullptr;wl_pointer*pointer=nullptr;wl_surface*pointerSurface=nullptr;
 EGLDisplay ed=EGL_NO_DISPLAY;EGLContext ec=EGL_NO_CONTEXT;EGLSurface es=EGL_NO_SURFACE;wl_egl_window*ew=nullptr;
 mpv_render_context*render=nullptr;mpv_handle*mpv=nullptr;std::atomic<bool>update{true};
 bool legacy=false;bool configured=false,fullscreen=false,closed=false,ready=false,hdr=false,pq=false,bt2020=false,parametric=false;
 std::atomic<int> width{960},height{640};int uiw=0,uih=0;std::atomic<bool>running{true};std::atomic<int>renderFailure{0};std::thread renderThread;std::mutex metrics; double px=0,py=0;std::vector<int>input;
 unsigned frames=0,redraws=0;double renderMs=0,swapMs=0;int redBits=0,greenBits=0,blueBits=0,alphaBits=0;int renderFd=-1;
 std::vector<Buffer*>buffers;FILE*log=nullptr;int capw=0,caph=0;uint32_t capformat=0;int capdone=0;
};
static void note(Surface*s,const char*t){if(s->log){fprintf(s->log,"%s\n",t);fflush(s->log);}}
static void check(bool b,const char*m){if(!b){fprintf(stderr,"Wayland surface: %s\n",m);abort();}}
static Buffer*buffer(Surface*s,int w,int h,uint32_t format){
 auto*b=new Buffer;b->n=(size_t)w*h*4;int fd=memfd_create("pictureshow-ui",MFD_CLOEXEC);check(fd>=0,"memfd");check(ftruncate(fd,b->n)==0,"ftruncate");
 b->data=mmap(nullptr,b->n,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);check(b->data!=MAP_FAILED,"mmap");
 auto*p=wl_shm_create_pool(s->shm,fd,b->n);b->b=wl_shm_pool_create_buffer(p,0,w,h,w*4,format);wl_shm_pool_destroy(p);close(fd);
 static const wl_buffer_listener listener={[](void*d,wl_buffer*){((Buffer*)d)->busy=false;}};wl_buffer_add_listener(b->b,&listener,b);s->buffers.push_back(b);return b;
}
static const wp_color_manager_v1_listener cmListener={
 [](void*d,wp_color_manager_v1*,uint32_t v){},
 [](void*d,wp_color_manager_v1*,uint32_t v){if(v==WP_COLOR_MANAGER_V1_FEATURE_PARAMETRIC)((Surface*)d)->parametric=true;},
 [](void*d,wp_color_manager_v1*,uint32_t v){if(v==WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ)((Surface*)d)->pq=true;},
 [](void*d,wp_color_manager_v1*,uint32_t v){if(v==WP_COLOR_MANAGER_V1_PRIMARIES_BT2020)((Surface*)d)->bt2020=true;},
 [](void*,wp_color_manager_v1*){}
};
static const wl_pointer_listener pointerListener={
 [](void*d,wl_pointer*,uint32_t,wl_surface*w,wl_fixed_t x,wl_fixed_t y){auto*s=(Surface*)d;s->pointerSurface=w;s->px=wl_fixed_to_double(x);s->py=wl_fixed_to_double(y);},
 [](void*d,wl_pointer*,uint32_t,wl_surface*){((Surface*)d)->pointerSurface=nullptr;},
 [](void*d,wl_pointer*,uint32_t,wl_fixed_t x,wl_fixed_t y){auto*s=(Surface*)d;s->px=wl_fixed_to_double(x);s->py=wl_fixed_to_double(y);},
 [](void*d,wl_pointer*,uint32_t,uint32_t,uint32_t button,uint32_t state){auto*s=(Surface*)d;if(s->pointerSurface==s->ui&&button==0x110){s->input.push_back(state?1:2);s->input.push_back((int)s->px);s->input.push_back((int)s->py);}},
 [](void*,wl_pointer*,uint32_t,uint32_t,wl_fixed_t){},[](void*,wl_pointer*){},[](void*,wl_pointer*,uint32_t){},[](void*,wl_pointer*,uint32_t,uint32_t){},[](void*,wl_pointer*,uint32_t,int32_t){},[](void*,wl_pointer*,uint32_t,int32_t){}
};
static const wl_seat_listener seatListener={
 [](void*d,wl_seat*seat,uint32_t caps){auto*s=(Surface*)d;if((caps&WL_SEAT_CAPABILITY_POINTER)&&!s->pointer){s->pointer=wl_seat_get_pointer(seat);wl_pointer_add_listener(s->pointer,&pointerListener,s);}},[](void*,wl_seat*,const char*){}
};
static void global(void*d,wl_registry*r,uint32_t name,const char*i,uint32_t version){auto*s=(Surface*)d;
 if(!strcmp(i,"wl_compositor"))s->c=(wl_compositor*)wl_registry_bind(r,name,&wl_compositor_interface,std::min(version,4u));
 else if(!strcmp(i,"wl_subcompositor"))s->sc=(wl_subcompositor*)wl_registry_bind(r,name,&wl_subcompositor_interface,1);
 else if(!strcmp(i,"wl_shm"))s->shm=(wl_shm*)wl_registry_bind(r,name,&wl_shm_interface,1);
 else if(!strcmp(i,"xdg_wm_base")){s->wm=(xdg_wm_base*)wl_registry_bind(r,name,&xdg_wm_base_interface,1);static const xdg_wm_base_listener l={[](void*,xdg_wm_base*w,uint32_t n){xdg_wm_base_pong(w,n);}};xdg_wm_base_add_listener(s->wm,&l,s);}
 else if(!strcmp(i,"wp_color_manager_v1")){s->cm=(wp_color_manager_v1*)wl_registry_bind(r,name,&wp_color_manager_v1_interface,1);wp_color_manager_v1_add_listener(s->cm,&cmListener,s);}
 else if(!strcmp(i,"wl_output")&&!s->output)s->output=(wl_output*)wl_registry_bind(r,name,&wl_output_interface,1);
 else if(!strcmp(i,"weston_capture_v1"))s->capture=(weston_capture_v1*)wl_registry_bind(r,name,&weston_capture_v1_interface,1);
 else if(!strcmp(i,"weston_test"))s->test=(weston_test*)wl_registry_bind(r,name,&weston_test_interface,1);
 else if(!strcmp(i,"wl_seat")&&!s->seat){s->seat=(wl_seat*)wl_registry_bind(r,name,&wl_seat_interface,std::min(version,5u));wl_seat_add_listener(s->seat,&seatListener,s);}
}
static void*getproc(void*,const char*n){return (void*)eglGetProcAddress(n);}
static double seconds(){return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();}
extern "C" {
void*ps_wayland_create(mpv_handle*mpv,const char*receipt,int hdr){
 auto*s=new Surface;s->mpv=mpv;s->hdr=hdr;s->legacy=getenv("SPIKE_WAYLAND_LEGACY_PACING")!=nullptr;s->log=fopen(receipt,"w");s->d=wl_display_connect(nullptr);check(s->d,"wl_display_connect");
 auto*r=wl_display_get_registry(s->d);static const wl_registry_listener l={global,[](void*,wl_registry*,uint32_t){}};wl_registry_add_listener(r,&l,s);check(wl_display_roundtrip(s->d)>=0,"registry");check(wl_display_roundtrip(s->d)>=0,"capabilities");
 check(s->c&&s->sc&&s->wm&&s->shm,"required Wayland globals");
 s->video=wl_compositor_create_surface(s->c);s->xdg=xdg_wm_base_get_xdg_surface(s->wm,s->video);
 static const xdg_surface_listener xl={[](void*d,xdg_surface*x,uint32_t serial){auto*s=(Surface*)d;xdg_surface_set_window_geometry(x,0,0,s->width,s->height);xdg_surface_ack_configure(x,serial);s->configured=true;s->update=true;}};xdg_surface_add_listener(s->xdg,&xl,s);
 s->top=xdg_surface_get_toplevel(s->xdg);xdg_toplevel_set_title(s->top,"Pictureshow Compose Wayland HDR probe");xdg_toplevel_set_app_id(s->top,"app.pictureshow.desktop");
 static const xdg_toplevel_listener tl={[](void*d,xdg_toplevel*,int32_t w,int32_t h,wl_array*states){auto*s=(Surface*)d;s->fullscreen=false;for(auto*p=(uint32_t*)states->data;(char*)p<(char*)states->data+states->size;p++)if(*p==XDG_TOPLEVEL_STATE_FULLSCREEN)s->fullscreen=true;if(w>0&&h>0){s->width=w;s->height=h;}},[](void*d,xdg_toplevel*){((Surface*)d)->closed=true;}};xdg_toplevel_add_listener(s->top,&tl,s);
 if(hdr){check(s->cm&&s->pq&&s->bt2020&&s->parametric,"compositor must advertise PQ/BT2020 parametric color management");
  auto*p=wp_color_manager_v1_create_parametric_creator(s->cm);wp_image_description_creator_params_v1_set_tf_named(p,WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ);wp_image_description_creator_params_v1_set_primaries_named(p,WP_COLOR_MANAGER_V1_PRIMARIES_BT2020);
  s->desc=wp_image_description_creator_params_v1_create(p);static const wp_image_description_v1_listener dl={[](void*d,wp_image_description_v1*,uint32_t cause,const char*msg){fprintf(stderr,"Image description rejected %u: %s\n",cause,msg);note((Surface*)d,"image_description=FAILED");},[](void*d,wp_image_description_v1*,uint32_t id){auto*s=(Surface*)d;s->ready=true;note(s,"image_description=ready; transfer=ST2084_PQ(11); primaries=BT2020(6)");}};wp_image_description_v1_add_listener(s->desc,&dl,s);
  wl_display_roundtrip(s->d);wl_display_roundtrip(s->d);check(s->ready,"PQ/BT2020 image description accepted");s->cms=wp_color_manager_v1_get_surface(s->cm,s->video);wp_color_management_surface_v1_set_image_description(s->cms,s->desc,WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL);
 }
 s->ui=wl_compositor_create_surface(s->c);s->sub=wl_subcompositor_get_subsurface(s->sc,s->ui,s->video);wl_subsurface_set_desync(s->sub);wl_subsurface_place_above(s->sub,s->video);
 wl_surface_commit(s->video);while(!s->configured)check(wl_display_dispatch(s->d)>=0,"initial configure");
 s->ed=eglGetDisplay((EGLNativeDisplayType)s->d);EGLint major,minor;check(eglInitialize(s->ed,&major,&minor),"eglInitialize");check(eglBindAPI(EGL_OPENGL_API),"eglBindAPI");
 EGLint attrs[]={EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_RED_SIZE,hdr?10:8,EGL_GREEN_SIZE,hdr?10:8,EGL_BLUE_SIZE,hdr?10:8,EGL_ALPHA_SIZE,0,EGL_NONE};EGLConfig config;EGLint n;check(eglChooseConfig(s->ed,attrs,&config,1,&n)&&n,"EGL 10-bit window config");
 eglGetConfigAttrib(s->ed,config,EGL_RED_SIZE,&s->redBits);eglGetConfigAttrib(s->ed,config,EGL_GREEN_SIZE,&s->greenBits);eglGetConfigAttrib(s->ed,config,EGL_BLUE_SIZE,&s->blueBits);
 eglGetConfigAttrib(s->ed,config,EGL_ALPHA_SIZE,&s->alphaBits);
 EGLint context[]={EGL_CONTEXT_MAJOR_VERSION,3,EGL_CONTEXT_MINOR_VERSION,3,EGL_NONE};s->ec=eglCreateContext(s->ed,config,EGL_NO_CONTEXT,context);check(s->ec!=EGL_NO_CONTEXT,"eglCreateContext");
 s->ew=wl_egl_window_create(s->video,s->width,s->height);s->es=eglCreateWindowSurface(s->ed,config,(EGLNativeWindowType)s->ew,nullptr);check(s->es!=EGL_NO_SURFACE,"eglCreateWindowSurface");check(eglMakeCurrent(s->ed,s->es,s->es,s->ec),"eglMakeCurrent");eglSwapInterval(s->ed,1);
 fprintf(s->log,"GL_VENDOR=%s\nGL_RENDERER=%s\nGL_VERSION=%s\nEGL_RGB_BITS=%d,%d,%d\nvideoReadbacks=regular-path-zero; explicit-HDR-diagnostic=%d\nUI=Compose ImageComposeScene -> SDR wl_shm subsurface\n",glGetString(GL_VENDOR),glGetString(GL_RENDERER),glGetString(GL_VERSION),s->redBits,s->greenBits,s->blueBits,getenv("SPIKE_HDR_APP_SAMPLES")?1:0);fflush(s->log);
 mpv_opengl_drm_params_v2 drm={};drm.fd=-1;s->renderFd=drm.render_fd=open("/dev/dri/renderD128",O_RDWR);check(s->renderFd>=0 || getenv("SPIKE_SOFTWARE_GPU"),"render node required unless explicit software diagnostic");
 mpv_opengl_init_params init={getproc,nullptr};const char*api=MPV_RENDER_API_TYPE_OPENGL;mpv_render_param params[]={{MPV_RENDER_PARAM_API_TYPE,(void*)api},{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,&init},{MPV_RENDER_PARAM_WL_DISPLAY,s->d},{MPV_RENDER_PARAM_DRM_DISPLAY_V2,s->renderFd>=0?&drm:nullptr},{MPV_RENDER_PARAM_INVALID,nullptr}};
 if(s->renderFd<0) params[3]={MPV_RENDER_PARAM_INVALID,nullptr};
 check(mpv_render_context_create(&s->render,mpv,params)>=0,"mpv_render_context_create");mpv_render_context_set_update_callback(s->render,[](void*d){((Surface*)d)->update=true;},s);
 eglMakeCurrent(s->ed,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
 s->renderThread=std::thread([s]{
  eglMakeCurrent(s->ed,s->es,s->es,s->ec);int lastw=s->width,lasth=s->height;auto next=std::chrono::steady_clock::now();
  while(s->running){
   if(s->legacy||s->update.exchange(false)){
    auto flags=mpv_render_context_update(s->render);int w=s->width,h=s->height;
    if((flags&MPV_RENDER_UPDATE_FRAME)||s->legacy||w!=lastw||h!=lasth){
     if(w!=lastw||h!=lasth){wl_egl_window_resize(s->ew,w,h,0,0);lastw=w;lasth=h;}
     mpv_opengl_fbo fbo{0,w,h,s->hdr?GL_RGB10_A2:GL_RGBA8};int flip=1,block=s->legacy?1:0;mpv_render_param params[]={{MPV_RENDER_PARAM_OPENGL_FBO,&fbo},{MPV_RENDER_PARAM_FLIP_Y,&flip},{MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,&block},{MPV_RENDER_PARAM_INVALID,nullptr}};
     double a=seconds();int rc=mpv_render_context_render(s->render,params);double b=seconds();if(rc<0){s->renderFailure=rc;break;}
     if(const char *path=getenv("SPIKE_HDR_APP_SAMPLES")) {
      // Explicit finite diagnostic only: this boundary precedes Wayland composition.
      FILE *out=fopen(path,"w");
      if(!out){s->renderFailure=-4;break;}
      const char *names[]={"black","gray100","gray203","gray1000","red100","green100","blue100","gray50"};
      GLint readFbo=0,drawFbo=0,readBuffer=0,drawBuffer=0;
      GLboolean doubleBuffered=GL_FALSE;EGLint eglRenderBuffer=0;
      glGetIntegerv(0x8CAA,&readFbo);glGetIntegerv(0x8CA6,&drawFbo);glGetIntegerv(GL_READ_BUFFER,&readBuffer);glGetIntegerv(GL_DRAW_BUFFER,&drawBuffer);
      glGetBooleanv(GL_DOUBLEBUFFER,&doubleBuffered);eglQuerySurface(s->ed,s->es,EGL_RENDER_BUFFER,&eglRenderBuffer);
      double mediaTime=-1;int eof=0;
      mpv_get_property(s->mpv,"time-pos",MPV_FORMAT_DOUBLE,&mediaTime);mpv_get_property(s->mpv,"eof-reached",MPV_FORMAT_FLAG,&eof);
      auto bindFramebuffer=(void(*)(GLenum,GLuint))eglGetProcAddress("glBindFramebuffer");
      if(!bindFramebuffer){fclose(out);s->renderFailure=-6;break;}
      bindFramebuffer(0x8CA8,0);glReadBuffer(GL_BACK);
      fprintf(out,"{\"encoding\":\"%s\",\"primaries\":\"%s\",\"referenceWhiteNits\":203,\"boundary\":\"app explicit EGL default GL_BACK BEFORE compositor\",\"readFbo\":0,\"previousReadFbo\":%d,\"drawFbo\":%d,\"previousReadBuffer\":%d,\"drawBuffer\":%d,\"doubleBuffered\":%s,\"eglRenderBuffer\":%d,\"eglRGBABits\":[%d,%d,%d,%d],\"frame\":%u,\"mediaTime\":%.9g,\"eof\":%s,\"width\":%d,\"height\":%d,\"samples\":[",s->hdr?"pq":"gamma2.2",s->hdr?"bt2020":"bt709",readFbo,drawFbo,readBuffer,drawBuffer,doubleBuffered?"true":"false",eglRenderBuffer,s->redBits,s->greenBits,s->blueBits,s->alphaBits,s->frames,mediaTime,eof?"true":"false",w,h);
      GLenum diagnosticError=GL_NO_ERROR;
      for(int n=0;n<8;n++) {
       float pixels[100];glReadPixels((int)((n+0.5)*w/8)-2,h/2-2,5,5,GL_RGBA,GL_FLOAT,pixels);
       float channels[4][25];
       for(int k=0;k<25;k++)for(int c=0;c<4;c++)channels[c][k]=pixels[k*4+c];
       for(int c=0;c<4;c++)std::sort(channels[c],channels[c]+25);
       float rgbPixels[75],rgbChannels[3][25],frontPixels[100]={},frontChannels[4][25];
       glReadPixels((int)((n+0.5)*w/8)-2,h/2-2,5,5,GL_RGB,GL_FLOAT,rgbPixels);
       for(int k=0;k<25;k++)for(int c=0;c<3;c++)rgbChannels[c][k]=rgbPixels[k*3+c];
       for(int c=0;c<3;c++)std::sort(rgbChannels[c],rgbChannels[c]+25);
       GLenum backError=glGetError();if(backError!=GL_NO_ERROR)diagnosticError=backError;
       glReadBuffer(GL_FRONT);glReadPixels((int)((n+0.5)*w/8)-2,h/2-2,5,5,GL_RGBA,GL_FLOAT,frontPixels);
       GLenum frontError=glGetError();glReadBuffer(GL_BACK);
       for(int k=0;k<25;k++)for(int c=0;c<4;c++)frontChannels[c][k]=frontPixels[k*4+c];
       for(int c=0;c<4;c++)std::sort(frontChannels[c],frontChannels[c]+25);
       fprintf(out,"%s{\"name\":\"%s\",\"rgb\":[%.9g,%.9g,%.9g],\"alpha\":%.9g,\"rgbRead\":[%.9g,%.9g,%.9g],\"frontRGBA\":[%.9g,%.9g,%.9g,%.9g],\"backError\":%u,\"frontError\":%u}",n?",":"",names[n],channels[0][12],channels[1][12],channels[2][12],channels[3][12],rgbChannels[0][12],rgbChannels[1][12],rgbChannels[2][12],frontChannels[0][12],frontChannels[1][12],frontChannels[2][12],frontChannels[3][12],backError,frontError);
      }
      fprintf(out,"]}\n");fclose(out);
      std::string framePath=std::string(path)+".frame-"+std::to_string(s->frames)+".json";
      FILE *source=fopen(path,"rb"),*snapshot=fopen(framePath.c_str(),"wb");
      if(source&&snapshot){char bytes[4096];size_t count;while((count=fread(bytes,1,sizeof(bytes),source)))fwrite(bytes,1,count,snapshot);}
      if(source)fclose(source);if(snapshot)fclose(snapshot);
      bindFramebuffer(0x8CA8,readFbo);glReadBuffer(readBuffer);
      if(glGetError()!=GL_NO_ERROR || diagnosticError!=GL_NO_ERROR){s->renderFailure=-5;break;}
     }
     if(!eglSwapBuffers(s->ed,s->es)){s->renderFailure=-2;break;}double c=seconds();mpv_render_context_report_swap(s->render);
     {std::lock_guard<std::mutex> lock(s->metrics);s->frames++;s->renderMs+=(b-a)*1000;s->swapMs+=(c-b)*1000;}
    }
   }else std::this_thread::sleep_for(std::chrono::milliseconds(1));
   if(s->legacy){next+=std::chrono::nanoseconds(16666667);std::this_thread::sleep_until(next);}
  }
  mpv_render_context_set_update_callback(s->render,nullptr,nullptr);mpv_render_context_free(s->render);s->render=nullptr;eglMakeCurrent(s->ed,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
 });return s;
}
int ps_wayland_pump(void*p){auto*s=(Surface*)p;
 while(wl_display_prepare_read(s->d)!=0)if(wl_display_dispatch_pending(s->d)<0)return -1;
 wl_display_flush(s->d);pollfd fd{wl_display_get_fd(s->d),POLLIN,0};int rc=poll(&fd,1,0);if(rc>0){if(wl_display_read_events(s->d)<0)return -1;wl_display_dispatch_pending(s->d);}else wl_display_cancel_read(s->d);
 if(s->renderFailure)return s->renderFailure;
 return s->closed?-3:0;
}
int ps_wayland_width(void*p){return ((Surface*)p)->width;}int ps_wayland_height(void*p){return ((Surface*)p)->height;}int ps_wayland_fullscreen(void*p){return ((Surface*)p)->fullscreen;}
void ps_wayland_fullscreen_set(void*p,int fs){auto*s=(Surface*)p;if(fs)xdg_toplevel_set_fullscreen(s->top,nullptr);else xdg_toplevel_unset_fullscreen(s->top);wl_display_flush(s->d);}
void ps_wayland_resize(void*p,int w,int h){auto*s=(Surface*)p;check(!s->fullscreen,"resize while fullscreen");s->width=w;s->height=h;s->update=true;}
void ps_wayland_ui(void*p,const void*data,int w,int h){auto*s=(Surface*)p;Buffer*b=nullptr;for(auto*q:s->buffers)if(!q->busy&&q->n==(size_t)w*h*4){b=q;break;}if(!b)b=buffer(s,w,h,WL_SHM_FORMAT_ARGB8888);memcpy(b->data,data,b->n);b->busy=true;wl_surface_attach(s->ui,b->b,0,0);wl_surface_damage(s->ui,0,0,w,h);wl_surface_commit(s->ui);wl_display_flush(s->d);s->uiw=w;s->uih=h;}
int ps_wayland_input(void*p,int*event){auto*s=(Surface*)p;if(s->input.size()<3)return 0;for(int n=0;n<3;n++)event[n]=s->input[n];s->input.erase(s->input.begin(),s->input.begin()+3);return 1;}
void ps_wayland_receipt(void*p,const char*path){auto*s=(Surface*)p;std::lock_guard<std::mutex> lock(s->metrics);FILE*f=fopen(path,"w");fprintf(f,"width=%d\nheight=%d\nfullscreen=%d\nrenderCalls=%u\nmeanRenderMs=%.5f\nmeanSwapMs=%.5f\nPQ_BT2020_ready=%d\nWaylandProtocolError=%d\noutputPolicy=target-trc-pq,target-prim-bt.2020,target-peak-10000\nlegacyPacing=%d\n",s->width.load(),s->height.load(),s->fullscreen,s->frames,s->renderMs/std::max(1u,s->frames),s->swapMs/std::max(1u,s->frames),s->ready,wl_display_get_error(s->d),s->legacy);fclose(f);}

int ps_wayland_test_click(void*p,int x,int y){auto*s=(Surface*)p;if(!s->test)return -1;
 struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);weston_test_move_pointer(s->test,0,t.tv_sec,t.tv_nsec,x,y);
 weston_test_send_button(s->test,0,t.tv_sec,t.tv_nsec,0x110,WL_POINTER_BUTTON_STATE_PRESSED);
 weston_test_send_button(s->test,0,t.tv_sec,t.tv_nsec+1000000,0x110,WL_POINTER_BUTTON_STATE_RELEASED);wl_display_flush(s->d);note(s,"input=Weston test seat -> wl_pointer.button -> ComposeScene pointer hit test");return 0;
}
int ps_wayland_capture(void*p,const char*path){auto*s=(Surface*)p;if(!s->capture||!s->output)return -1;
 s->capw=0;s->caph=0;s->capdone=0;
 s->shot=weston_capture_v1_create(s->capture,s->output,WESTON_CAPTURE_V1_SOURCE_FRAMEBUFFER);
 static const weston_capture_source_v1_listener l={
 [](void*d,weston_capture_source_v1*,uint32_t f){((Surface*)d)->capformat=f;},
 [](void*d,weston_capture_source_v1*,int32_t w,int32_t h){auto*s=(Surface*)d;s->capw=w;s->caph=h;},
 [](void*d,weston_capture_source_v1*){((Surface*)d)->capdone=1;},
 [](void*d,weston_capture_source_v1*){((Surface*)d)->capdone=-1;},
 [](void*d,weston_capture_source_v1*,const char*m){((Surface*)d)->capdone=-2;fprintf(stderr,"capture failed: %s\n",m);},[](void*,weston_capture_source_v1*){}
 };
 weston_capture_source_v1_add_listener(s->shot,&l,s);wl_display_roundtrip(s->d);if(!s->capw||!s->caph)return -2;
 uint32_t f=s->capformat==0x34325258?WL_SHM_FORMAT_XRGB8888:s->capformat==0x34325241?WL_SHM_FORMAT_ARGB8888:s->capformat;
 auto*b=buffer(s,s->capw,s->caph,f);b->busy=true;weston_capture_source_v1_capture(s->shot,b->b);wl_display_flush(s->d);
 double deadline=seconds()+5;while(!s->capdone&&seconds()<deadline){pollfd fd{wl_display_get_fd(s->d),POLLIN,0};if(poll(&fd,1,100)>0)wl_display_dispatch(s->d);}
 if(s->capdone==1){
  fprintf(s->log,"captureSource=Weston composited FRAMEBUFFER; captureFourCC=0x%08x; width=%d; height=%d; bitsPerChannel=8\n",s->capformat,s->capw,s->caph);fflush(s->log);
  // Reject an unsupported layout rather than interpreting packed 10-bit/float as bytes.
  if(s->capformat!=0x34325258 && s->capformat!=0x34325241 && s->capformat!=0x34324258 && s->capformat!=0x34324241) {s->capdone=-3;}
 }
 if(s->capdone==1){
  std::string rawPath=std::string(path)+".raw";FILE*raw=fopen(rawPath.c_str(),"wb");if(raw){fwrite(b->data,1,b->n,raw);fclose(raw);}
 }
 if(s->capdone==1){FILE*out=fopen(path,"wb");fprintf(out,"P6\n%d %d\n255\n",s->capw,s->caph);auto*bytes=(unsigned char*)b->data;for(int n=0;n<s->capw*s->caph;n++){bool rgbOrder=s->capformat==0x34324258||s->capformat==0x34324241;unsigned char rgb[3]={bytes[n*4+(rgbOrder?0:2)],bytes[n*4+1],bytes[n*4+(rgbOrder?2:0)]};fwrite(rgb,1,3,out);}fclose(out);}
 weston_capture_source_v1_destroy(s->shot);s->shot=nullptr;
 // Capture buffers never enter the UI reuse pool: their opaque format differs.
 for(auto it=s->buffers.begin();it!=s->buffers.end();++it)if(*it==b){s->buffers.erase(it);break;}
 wl_buffer_destroy(b->b);munmap(b->data,b->n);delete b;return s->capdone==1?0:-3;
}
void ps_wayland_close(void*p){auto*s=(Surface*)p;s->running=false;s->renderThread.join();eglDestroySurface(s->ed,s->es);eglDestroyContext(s->ed,s->ec);eglTerminate(s->ed);wl_egl_window_destroy(s->ew);for(auto*b:s->buffers){wl_buffer_destroy(b->b);munmap(b->data,b->n);delete b;}wl_display_disconnect(s->d);if(s->renderFd>=0)close(s->renderFd);if(s->log)fclose(s->log);delete s;}
}
