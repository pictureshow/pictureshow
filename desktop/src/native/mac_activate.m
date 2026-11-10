/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Use AWT's AppKit run-loop mode when scheduling from a JVM thread.
 */
#import <Cocoa/Cocoa.h>
static void on_appkit(void (^work)(void)) {
    CFRunLoopPerformBlock(CFRunLoopGetMain(), (__bridge CFArrayRef)@[NSDefaultRunLoopMode, @"AWTRunLoopMode"], work);
    CFRunLoopWakeUp(CFRunLoopGetMain());
}
void pictureshow_activate(void) {
    on_appkit(^{
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        [NSApp activateIgnoringOtherApps:YES];
        for (NSWindow *window in NSApp.windows) {
            if (window.isVisible && [window.title hasPrefix:@"Pictureshow"]) [window makeKeyAndOrderFront:nil];
        }
    });
}
void pictureshow_fullscreen(int enabled) {
    on_appkit(^{
        for (NSWindow *window in NSApp.windows) {
            if (!window.isVisible || ![window.title hasPrefix:@"Pictureshow"]) continue;
            window.collectionBehavior |= NSWindowCollectionBehaviorFullScreenPrimary;
            BOOL current = (window.styleMask & NSWindowStyleMaskFullScreen) != 0;
            if (current != (enabled != 0)) [window toggleFullScreen:nil];
            break;
        }
    });
}
