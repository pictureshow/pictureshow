/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * A command-line JVM launched by a GUI-session job needs a foreground AppKit policy.
 */
#import <Cocoa/Cocoa.h>
void pictureshow_activate(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        [NSApp activateIgnoringOtherApps:YES];
        for (NSWindow *window in NSApp.windows) {
            if (window.isVisible) [window makeKeyAndOrderFront:nil];
        }
    });
}
