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

/* AppKit supplies the standard traffic-light buttons on our existing borderless
 * alpha window. Changing its titled style behind AWT changes frame insets and
 * breaks restore geometry, so keep the qualified overlay's window style intact. */
int ps_window_desktop_chrome(void *ptr) {
 if (!ptr) return 0;
 __block int configured=0;
 onMain(^{
  NSWindow *w=(__bridge NSWindow*)ptr;
  w.styleMask |= NSWindowStyleMaskClosable|NSWindowStyleMaskMiniaturizable|NSWindowStyleMaskResizable;
  NSView *content=w.contentView;
  NSArray *kinds=@[@(NSWindowCloseButton), @(NSWindowMiniaturizeButton), @(NSWindowZoomButton)];
  NSArray *actions=@[NSStringFromSelector(@selector(performClose:)), NSStringFromSelector(@selector(miniaturize:)), NSStringFromSelector(@selector(zoom:))];
  configured=1;
  for (NSUInteger i=0;i<kinds.count;i++) {
   NSInteger tag=13700+i;
   NSButton *button=(NSButton*)[content viewWithTag:tag];
   if(!button) {
    button=[NSWindow standardWindowButton:[kinds[i] unsignedIntegerValue] forStyleMask:NSWindowStyleMaskTitled|NSWindowStyleMaskClosable|NSWindowStyleMaskMiniaturizable|NSWindowStyleMaskResizable];
    button.tag=tag;
    button.target=w;
    button.action=NSSelectorFromString(actions[i]);
    [content addSubview:button positioned:NSWindowAbove relativeTo:nil];
    button.translatesAutoresizingMaskIntoConstraints=NO;
    [NSLayoutConstraint activateConstraints:@[
     [button.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:14+i*20],
     [button.topAnchor constraintEqualToAnchor:content.topAnchor constant:19],
     [button.widthAnchor constraintEqualToConstant:14],
     [button.heightAnchor constraintEqualToConstant:14]
    ]];
   }
   if(!button || button.hidden || button.superview.hidden) configured=0;
  }
  [content layoutSubtreeIfNeeded];
 });
 return configured;
}
