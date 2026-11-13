/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Only loaded by an isolated ephemeral compositor, never a user's desktop.
 */
#include <libweston/libweston.h>
#include <libweston/colorimetry.h>
#include <libweston/weston-log.h>
#include <stdlib.h>
#include <stdio.h>
#include <GLES2/gl2.h>
static struct wl_listener listener;
static struct weston_compositor *compositor;
static struct wl_listener frame_listener;
static struct weston_output *observed_output;
static unsigned capture_number;
static bool capture_pending;
static int compare_byte(const void *a,const void *b){return *(const unsigned char*)a-*(const unsigned char*)b;}
static void output_frame(struct wl_listener *l, void *damage) {
 const char *root=getenv("SPIKE_HDR_COMPOSITOR_SAMPLES");
 if(!capture_pending || !root) return;
 capture_pending=false;
 // Stock Weston GL renderer emits output.frame_signal after drawing to its final
 // output framebuffer. Observe that buffer synchronously without altering color code.
 GLint fbo=0,readFbo=0,packBuffer=0, viewport[4];glGetIntegerv(GL_FRAMEBUFFER_BINDING,&fbo);glGetIntegerv(0x8CAA,&readFbo);glGetIntegerv(0x88ED,&packBuffer);glGetIntegerv(GL_VIEWPORT,viewport);
 weston_log("HDR_NUMERIC_BINDING readFbo=%d drawFbo=%d packBuffer=%d\n",readFbo,fbo,packBuffer);
 GLenum prior=glGetError();
 if(!fbo || packBuffer || viewport[2]!=observed_output->width || viewport[3]!=observed_output->height || prior!=GL_NO_ERROR) {
  weston_log("HDR_NUMERIC_DIRECT invalid FBO=%d viewport=%dx%d GLerror=%u\n",fbo,viewport[2],viewport[3],prior);return;
 }
 char path[4096];snprintf(path,sizeof(path),"%s/direct-compositor-%u.json",root,capture_number);
 FILE *out=fopen(path,"w");if(!out)return;
 const GLenum packNames[]={0x0D02,0x0D03,0x0D04,GL_PACK_ALIGNMENT};
 GLint packValues[4];
 for(int i=0;i<4;i++){glGetIntegerv(packNames[i],&packValues[i]);glPixelStorei(packNames[i],i==3?1:0);}
 weston_log("HDR_NUMERIC_PACK rowLength=%d skipRows=%d skipPixels=%d alignment=%d\n",packValues[0],packValues[1],packValues[2],packValues[3]);
 glBindFramebuffer(0x8CA8,fbo); // Explicit READ_FRAMEBUFFER = the actual output DRAW_FRAMEBUFFER.
 bool pq=weston_output_get_eotf_mode(observed_output)==WESTON_EOTF_MODE_ST2084;
 const char *names[]={"black","gray100","gray203","gray1000","red100","green100","blue100","gray50"};
 fprintf(out,"{\"encoding\":\"%s\",\"primaries\":\"%s\",\"referenceWhiteNits\":%d,\"boundary\":\"stock Weston final output FBO at frame_signal AFTER composition\",\"fbo\":%d,\"width\":%d,\"height\":%d,\"samples\":[",pq?"pq":"gamma2.2",pq?"bt2020":"bt709",pq?203:80,fbo,viewport[2],viewport[3]);
 for(int n=0;n<8;n++) {
  unsigned char pixels[100],channels[3][25];
  glReadPixels((int)((n+.5)*viewport[2]/8)-2,viewport[3]/2-2,5,5,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
  for(int k=0;k<25;k++)for(int c=0;c<3;c++)channels[c][k]=pixels[k*4+c];
  for(int c=0;c<3;c++)qsort(channels[c],25,1,compare_byte);
  fprintf(out,"%s{\"name\":\"%s\",\"rgb\":[%.9g,%.9g,%.9g]}",n?",":"",names[n],channels[0][12]/255.0,channels[1][12]/255.0,channels[2][12]/255.0);
 }
 unsigned char ui[4];glReadPixels(viewport[2]-140,viewport[3]-32,1,1,GL_RGBA,GL_UNSIGNED_BYTE,ui);
 GLenum error=glGetError();fprintf(out,"],\"diagnosticUiRGBA8\":[%u,%u,%u,%u],\"glError\":%u}\n",ui[0],ui[1],ui[2],ui[3],error);fclose(out);
 glBindFramebuffer(0x8CA8,readFbo);
 for(int i=0;i<4;i++)glPixelStorei(packNames[i],packValues[i]);
 weston_log("HDR_NUMERIC_DIRECT captured=%u FBO=%d GLerror=%u\n",capture_number,fbo,error);
}
static void authorize(struct wl_listener *l, struct weston_output_capture_attempt *a) {
 struct weston_output *output;
 wl_list_for_each(output, &compositor->output_list, link) {
  weston_log("HDR_NUMERIC_OUTPUT name=%s eotf=%u colorimetry=%u profile=%s\n", output->name,
   weston_output_get_eotf_mode(output), weston_output_get_colorimetry_mode(output),
   weston_color_profile_get_description(output->color_profile));
  char *details=weston_color_profile_get_details(output->color_profile);
  if(details) {weston_log("HDR_NUMERIC_PROFILE %s\n",details);free(details);}
 }
 struct weston_view *view;
 wl_list_for_each(view, &compositor->view_list, link) {
  const pixman_box32_t *box=pixman_region32_extents(&view->transform.boundingbox);
  struct weston_buffer *buffer=view->surface->buffer_ref.buffer;
  weston_log("HDR_NUMERIC_SURFACE name=%s label=%s role=%s layer=%u opaque=%d bufferType=%d\n",
   view->surface->internal_name?view->surface->internal_name:"none",
   view->surface->label?view->surface->label:"none",
   view->surface->role_name?view->surface->role_name:"none",
   view->layer_link.layer?(unsigned)view->layer_link.layer->position:0,
   view->surface->is_opaque,buffer?(int)buffer->type:-1);
  if(buffer && buffer->type==WESTON_BUFFER_SHM) {
   struct wl_shm_buffer *shm=buffer->shm_buffer;
   wl_shm_buffer_begin_access(shm);
   unsigned char *bytes=wl_shm_buffer_get_data(shm);
   int stride=wl_shm_buffer_get_stride(shm),w=wl_shm_buffer_get_width(shm),h=wl_shm_buffer_get_height(shm);
   for(int n=0;n<8;n++) {
    unsigned char *p=bytes+(h/2)*stride+(int)((n+.5)*w/8)*4;
    weston_log("HDR_NUMERIC_SHM patch=%d rawBytes=%u,%u,%u,%u\n",n,p[0],p[1],p[2],p[3]);
   }
   if(w>140 && h>32) {
    unsigned char *p=bytes+32*stride+(w-140)*4;
    weston_log("HDR_NUMERIC_SHM uiPointRawBytes=%u,%u,%u,%u\n",p[0],p[1],p[2],p[3]);
   }
   wl_shm_buffer_end_access(shm);
  }
  weston_log("HDR_NUMERIC_VIEW mapped=%d surfaceMapped=%d size=%dx%d alpha=%g visibleMask=%u bounds=%d,%d,%d,%d\n",
   view->is_mapped,view->surface->is_mapped,view->surface->width,view->surface->height,view->alpha,
   view->output_visibility_mask,box->x1,box->y1,box->x2,box->y2);
 }
 capture_number++;capture_pending=true;
 a->authorized=true;
}
WL_EXPORT int wet_module_init(struct weston_compositor *c, int *argc, char *argv[]) {
 compositor=c;
 weston_compositor_add_screenshot_authority(c,&listener,authorize);
 if(getenv("SPIKE_HDR_COMPOSITOR_SAMPLES")) {
  struct weston_output *output;
  wl_list_for_each(output,&c->output_list,link) {
   if(observed_output) return -1; // This diagnostic intentionally owns one output.
   observed_output=output;
  }
  if(!observed_output) return -1;
  frame_listener.notify=output_frame;wl_signal_add(&observed_output->frame_signal,&frame_listener);
 }
 return 0;
}
