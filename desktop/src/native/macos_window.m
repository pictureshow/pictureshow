/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Geometry-only AppKit probe, independent of JOGL/libmpv.
 */
#import <Cocoa/Cocoa.h>
@interface PSWindowCall:NSObject
@property(copy) void (^block)(void);
-(void)run;
@end
@implementation PSWindowCall
-(void)run{NSLog(@"PSWindowCall run %@",NSThread.currentThread);self.block();}
@end
static void onMain(void(^block)(void)) {
 if(NSThread.isMainThread){block();return;}
 PSWindowCall *c=[PSWindowCall new];c.block=block;
 [c performSelectorOnMainThread:@selector(run) withObject:nil waitUntilDone:YES modes:@[NSRunLoopCommonModes,@"AWTRunLoopMode",NSDefaultRunLoopMode]];
}
const char *ps_window_info(void *ptr) {
 static char *result=NULL;
 if(!ptr) return "{\"error\":\"null window handle\"}";
 onMain(^{
 NSWindow *w=(__bridge NSWindow*)ptr;
 NSDictionary *d=@{@"class":NSStringFromClass(w.class),@"frame":NSStringFromRect(w.frame),@"screen":NSStringFromRect(w.screen.frame),@"styleMask":@(w.styleMask),@"collectionBehavior":@(w.collectionBehavior),@"level":@(w.level),@"active":@(NSApp.isActive),@"activationPolicy":@(NSApp.activationPolicy),@"nativeFullscreen":@((w.styleMask&NSWindowStyleMaskFullScreen)!=0),@"borderless":@((w.styleMask&NSWindowStyleMaskTitled)==0),@"appDelegate":NSStringFromClass(NSApp.delegate.class),@"mainRunLoopMode":NSRunLoop.currentRunLoop.currentMode?:@"nil",@"maxEDR":@(w.screen.maximumExtendedDynamicRangeColorComponentValue)};
 NSData *data=[NSJSONSerialization dataWithJSONObject:d options:NSJSONWritingPrettyPrinted error:nil];
 free(result);result=calloc(data.length+1,1);memcpy(result,data.bytes,data.length);
 });NSLog(@"ps_window_info return %p",result);return result?result:"{\"error\":\"AppKit callback did not execute\"}";
}
void ps_window_toggle(void *ptr) {
 onMain(^{NSWindow *w=(__bridge NSWindow*)ptr;w.collectionBehavior|=NSWindowCollectionBehaviorFullScreenPrimary;[w toggleFullScreen:nil];});
}

void *ps_window_find(const char *title) {
 __block NSWindow *found=nil;
 onMain(^{for(NSWindow *w in NSApp.windows) if([w.title isEqualToString:[NSString stringWithUTF8String:title]]) {found=w;break;}});
 return (__bridge void*)found;
}

void ps_window_activate(void *ptr) {
 onMain(^{[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];[(__bridge NSWindow*)ptr makeKeyAndOrderFront:nil];[NSApp activateIgnoringOtherApps:YES];});
}
