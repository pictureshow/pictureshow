/* Private isolated-test compositor module. Never installed on a user desktop. */
#include <libweston/libweston.h>
#include <libweston/weston-log.h>
static struct wl_listener listener;
static void authorize(struct wl_listener*l,struct weston_output_capture_attempt*a){a->authorized=true;}
WL_EXPORT int wet_module_init(struct weston_compositor*c,int*argc,char*argv[]){weston_compositor_add_screenshot_authority(c,&listener,authorize);return 0;}
