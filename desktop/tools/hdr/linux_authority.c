/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Only loaded by an isolated ephemeral compositor, never a user's desktop.
 */
#include <libweston/libweston.h>
#include <libweston/colorimetry.h>
#include <libweston/weston-log.h>
#include <stdlib.h>
static struct wl_listener listener;
static struct weston_compositor *compositor;
static void authorize(struct wl_listener *l, struct weston_output_capture_attempt *a) {
 struct weston_output *output;
 wl_list_for_each(output, &compositor->output_list, link) {
  weston_log("HDR_NUMERIC_OUTPUT name=%s eotf=%u colorimetry=%u profile=%s\n", output->name,
   weston_output_get_eotf_mode(output), weston_output_get_colorimetry_mode(output),
   weston_color_profile_get_description(output->color_profile));
  char *details=weston_color_profile_get_details(output->color_profile);
  if(details) {weston_log("HDR_NUMERIC_PROFILE %s\n",details);free(details);}
 }
 a->authorized=true;
}
WL_EXPORT int wet_module_init(struct weston_compositor *c, int *argc, char *argv[]) {
 compositor=c;
 weston_compositor_add_screenshot_authority(c,&listener,authorize);
 return 0;
}
