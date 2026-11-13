/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Diagnostic ONLY: one final pixel readback for GL-driver/format isolation.
 * Production GLCanvas path does not call glReadPixels.
 */
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <dlfcn.h>
#include <chrono>
#include <thread>
#include <fstream>
#include <iostream>
#include <vector>
#include <clocale>
#include <fcntl.h>
#include <unistd.h>
static void *proc(void*,const char *name){void *p=(void*)eglGetProcAddress(name);return p?p:dlsym(RTLD_DEFAULT,name);}
static void check(int rc){if(rc<0){std::cerr<<mpv_error_string(rc)<<std::endl;std::exit(2);}}
int main(int argc,char**argv){
    if(argc<3){std::cerr<<"egl_probe MEDIA OUTPUT.ppm [fbo-format] [vd-lavc-dr] [hwdec] [gpu-dumb-mode]\n";return 2;}
    setlocale(LC_NUMERIC,"C");
    auto platform=(PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if(!platform)return 3;
    EGLDisplay display=platform(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,nullptr);
    EGLint major,minor;if(!eglInitialize(display,&major,&minor)){std::cerr<<"EGL init "<<eglGetError()<<std::endl;return 3;}
    std::cout<<"EGL_VENDOR="<<eglQueryString(display,EGL_VENDOR)<<"\nEGL_VERSION="<<major<<"."<<minor<<std::endl;
    if(!eglBindAPI(EGL_OPENGL_API))return 3;
    EGLint attr[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint count;
    if(!eglChooseConfig(display,attr,&config,1,&count)||!count)return 3;
    EGLint size[]={EGL_WIDTH,640,EGL_HEIGHT,360,EGL_NONE};
    auto surface=eglCreatePbufferSurface(display,config,size);
    EGLint ctxattr[]={EGL_CONTEXT_MAJOR_VERSION,3,EGL_CONTEXT_MINOR_VERSION,3,EGL_CONTEXT_OPENGL_PROFILE_MASK,EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,EGL_NONE};
    auto gl=eglCreateContext(display,config,EGL_NO_CONTEXT,ctxattr);
    if(!eglMakeCurrent(display,surface,surface,gl)){std::cerr<<"EGL context "<<eglGetError()<<std::endl;return 3;}
    std::cout<<"GL_VENDOR="<<glGetString(GL_VENDOR)<<"\nGL_RENDERER="<<glGetString(GL_RENDERER)<<"\nGL_VERSION="<<glGetString(GL_VERSION)<<std::endl;
    GLuint texture=0,target=0;
    glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,640,360,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
    glGenFramebuffers(1,&target);glBindFramebuffer(GL_FRAMEBUFFER,target);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)return 3;
    mpv_handle *mpv=mpv_create();
    auto opt=[&](const char*n,const char*v){check(mpv_set_option_string(mpv,n,v));};
    opt("config","no");opt("vo","libmpv");opt("ao","null");opt("volume","0");opt("hwdec",argc>5?argv[5]:"no");opt("keep-open","yes");opt("sub-visibility","no");opt("osd-level","0");
    opt("terminal","yes");opt("msg-level","all=warn,vd=info,vo=info");
    if(argc>3)opt("fbo-format",argv[3]);
    if(argc>4)opt("vd-lavc-dr",argv[4]);
    if(argc>6)opt("gpu-dumb-mode",argv[6]);
    check(mpv_initialize(mpv));
    mpv_opengl_init_params init={proc,nullptr};mpv_render_context *render=nullptr;
    mpv_opengl_drm_params_v2 drm={};drm.fd=-1;drm.render_fd=open("/dev/dri/renderD128",O_RDWR);
    mpv_render_param p[]={{MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_OPENGL},{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,&init},{MPV_RENDER_PARAM_DRM_DISPLAY_V2,&drm},{MPV_RENDER_PARAM_INVALID,nullptr}};
    check(mpv_render_context_create(&render,mpv,p));
    const char *load[]={"loadfile",argv[1],nullptr};check(mpv_command(mpv,load));
    mpv_opengl_fbo fbo={(int)target,640,360,GL_RGBA8};int flip=1;
    mpv_render_param rp[]={{MPV_RENDER_PARAM_OPENGL_FBO,&fbo},{MPV_RENDER_PARAM_FLIP_Y,&flip},{MPV_RENDER_PARAM_INVALID,nullptr}};
    auto begin=std::chrono::steady_clock::now();unsigned long calls=0;
    while(std::chrono::steady_clock::now()-begin<std::chrono::seconds(4)){
        check(mpv_render_context_render(render,rp));calls++;
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    // Read our explicit framebuffer, before any swap can invalidate default-buffer contents.
    glBindFramebuffer(GL_READ_FRAMEBUFFER,target);glReadBuffer(GL_COLOR_ATTACHMENT0);
    std::vector<unsigned char> pixels(640*360*3);glPixelStorei(GL_PACK_ALIGNMENT,1);glReadPixels(0,0,640,360,GL_RGB,GL_UNSIGNED_BYTE,pixels.data());
    if(glGetError()!=GL_NO_ERROR)return 4;
    std::ofstream image(argv[2],std::ios::binary);image<<"P6\n640 360\n255\n";
    for(int y=359;y>=0;y--)image.write((char*)pixels.data()+y*640*3,640*3);image.close();
    std::cout<<"renderCalls="<<calls<<"\nfinalReadbacks=1\n";
    for(const char *key:{"time-pos","video-params/pixelformat","video-params/w","video-params/h","frame-drop-count","decoder-frame-drop-count","hwdec-current","mpv-configuration","ffmpeg-version"}){char *v=mpv_get_property_string(mpv,key);std::cout<<key<<"="<<(v?v:"null")<<"\n";mpv_free(v);}
    mpv_render_context_free(render);mpv_terminate_destroy(mpv);
    if(drm.render_fd>=0)close(drm.render_fd);
    glDeleteFramebuffers(1,&target);glDeleteTextures(1,&texture);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(display,gl);eglDestroySurface(display,surface);eglTerminate(display);
}
