/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Geometry-only AppKit probe, independent of JOGL/libmpv.
 */
#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
static char desktopChromeObserverKey;
static char desktopChromeActionsKey;
@interface PSChromeActions:NSObject
@property(weak) NSWindow *window;
-(void)close:(id)sender;
@end
@implementation PSChromeActions
-(void)close:(id)sender {
 NSWindow *window=self.window;
 // AWT owns disposal. Its delegate delivers WINDOW_CLOSING and returns NO;
 // Compose can then stop playback/report progress before disposing the window.
 // performClose: on our borderless window does not deliver that delegate call.
 id<NSWindowDelegate> delegate=window.delegate;
 if([delegate respondsToSelector:@selector(windowShouldClose:)]) [delegate windowShouldClose:window];
}
@end
@interface PSChromeObservers:NSObject
@property(strong) id updated;
@property(strong) id closed;
@end
@implementation PSChromeObservers
-(void)dealloc { if(_updated) [NSNotificationCenter.defaultCenter removeObserver:_updated]; if(_closed) [NSNotificationCenter.defaultCenter removeObserver:_closed]; }
@end
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
 NSMutableArray *buttons=[NSMutableArray array];
 for(NSInteger tag=13700;tag<13703;tag++) {
  NSView *v=[w.contentView viewWithTag:tag];
  if(v) {
   NSPoint point=NSMakePoint(NSMidX(v.frame),NSMidY(v.frame));
   NSView *hit=[w.contentView hitTest:point];
   NSButton *button=(NSButton*)v;
   [buttons addObject:@{@"tag":@(tag),@"frame":NSStringFromRect(v.frame),@"hidden":@(v.hidden),@"superHidden":@(v.superview.hidden),@"hitClass":hit?NSStringFromClass(hit.class):@"nil",@"hitTag":@(hit.tag),@"targetClass":button.target?NSStringFromClass([button.target class]):@"nil",@"action":NSStringFromSelector(button.action)}];
  }
 }
 NSDictionary *d=@{@"customButtons":buttons,@"contentFlipped":@(w.contentView.isFlipped),@"contentBounds":NSStringFromRect(w.contentView.bounds),@"contentFrame":NSStringFromRect(w.contentView.frame),@"class":NSStringFromClass(w.class),@"frame":NSStringFromRect(w.frame),@"screen":NSStringFromRect(w.screen.frame),@"styleMask":@(w.styleMask),@"collectionBehavior":@(w.collectionBehavior),@"level":@(w.level),@"active":@(NSApp.isActive),@"activationPolicy":@(NSApp.activationPolicy),@"nativeFullscreen":@((w.styleMask&NSWindowStyleMaskFullScreen)!=0),@"borderless":@((w.styleMask&NSWindowStyleMaskTitled)==0),@"appDelegate":NSStringFromClass(NSApp.delegate.class),@"mainRunLoopMode":NSRunLoop.currentRunLoop.currentMode?:@"nil",@"maxEDR":@(w.screen.maximumExtendedDynamicRangeColorComponentValue)};
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
  PSChromeActions *actionsOwner=objc_getAssociatedObject(w,&desktopChromeActionsKey);
  if(!actionsOwner) {
   actionsOwner=[PSChromeActions new];actionsOwner.window=w;
   objc_setAssociatedObject(w,&desktopChromeActionsKey,actionsOwner,OBJC_ASSOCIATION_RETAIN_NONATOMIC);
  }
  configured=1;
  for (NSUInteger i=0;i<kinds.count;i++) {
   NSInteger tag=13700+i;
   NSButton *button=(NSButton*)[content viewWithTag:tag];
   if(!button) {
    button=[NSWindow standardWindowButton:[kinds[i] unsignedIntegerValue] forStyleMask:NSWindowStyleMaskTitled|NSWindowStyleMaskClosable|NSWindowStyleMaskMiniaturizable|NSWindowStyleMaskResizable];
    button.tag=tag;
    button.target=i==0?actionsOwner:w;
    button.action=i==0?@selector(close:):NSSelectorFromString(actions[i]);
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
  if(!objc_getAssociatedObject(w,&desktopChromeObserverKey)) {
   PSChromeObservers *observers=[PSChromeObservers new];
   __weak NSWindow *weakWindow=w;
   // Skiko can replace/reorder its native view during resize. Keep standard
   // controls above that view; a stable order performs no mutation per update.
   observers.updated=[NSNotificationCenter.defaultCenter addObserverForName:NSWindowDidUpdateNotification object:w queue:nil usingBlock:^(NSNotification *n) {
    NSView *view=weakWindow.contentView;
    NSView *last=[view viewWithTag:13702];
    if(last && view.subviews.lastObject!=last) {
     for(NSInteger tag=13700;tag<13703;tag++) {
      NSView *button=[view viewWithTag:tag];
      if(button) [view addSubview:button positioned:NSWindowAbove relativeTo:nil];
     }
    }
   }];
   observers.closed=[NSNotificationCenter.defaultCenter addObserverForName:NSWindowWillCloseNotification object:w queue:nil usingBlock:^(NSNotification *n) {
    NSWindow *window=weakWindow;
    if(window) objc_setAssociatedObject(window,&desktopChromeObserverKey,nil,OBJC_ASSOCIATION_RETAIN_NONATOMIC);
   }];
   objc_setAssociatedObject(w,&desktopChromeObserverKey,observers,OBJC_ASSOCIATION_RETAIN_NONATOMIC);
  }
 });
 return configured;
}
