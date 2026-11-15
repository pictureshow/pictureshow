/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Exercise real surface attachment/replacement with another application active.
 */
#import <Cocoa/Cocoa.h>
#include <dlfcn.h>
#include <mpv/client.h>

static void pump(double seconds) {
    NSDate *end=[NSDate dateWithTimeIntervalSinceNow:seconds];
    while(end.timeIntervalSinceNow>0) {
        NSEvent *event=[NSApp nextEventMatchingMask:NSEventMaskAny untilDate:[NSDate dateWithTimeIntervalSinceNow:.01]
            inMode:NSDefaultRunLoopMode dequeue:YES];
        if(event) [NSApp sendEvent:event];
        [NSApp updateWindows];
    }
}
static void require(BOOL condition, NSString *message) {
    if(!condition) @throw [NSException exceptionWithName:@"NativeFocusRegression" reason:message userInfo:nil];
}
int main(int argc, const char **argv) { @autoreleasepool {
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    [NSApp finishLaunching];
    NSWindow *window=[[NSWindow alloc] initWithContentRect:NSMakeRect(80,80,480,280)
        styleMask:NSWindowStyleMaskTitled|NSWindowStyleMaskClosable backing:NSBackingStoreBuffered defer:NO];
    window.releasedWhenClosed=NO;
    if(argc==2 && strcmp(argv[1],"--helper")==0) {
        window.title=@"Pictureshow smoke focus helper";
        [window makeKeyAndOrderFront:nil];
        [NSApp activate];
        [NSApp run];
        return 0;
    }
    if(argc!=4) { fprintf(stderr,"Usage: macos-focus SURFACE_LIBRARY HELPER_APP OUTPUT_JSON\n"); return 2; }
    __block NSRunningApplication *helper=nil;
    __block BOOL helperLaunched=NO;
    void *library=nullptr, *surface=nullptr;
    mpv_handle *mpv=nullptr;
    void (*destroy)(void*)=nullptr;
    NSMutableDictionary *result=[NSMutableDictionary dictionary];
    NSMutableArray *steps=[NSMutableArray array];
    int code=0;
    @try {
        NSWorkspaceOpenConfiguration *config=[NSWorkspaceOpenConfiguration configuration];
        config.arguments=@[@"--helper"]; config.activates=YES; config.createsNewApplicationInstance=YES;
        [NSWorkspace.sharedWorkspace openApplicationAtURL:[NSURL fileURLWithPath:[NSString stringWithUTF8String:argv[2]]] configuration:config completionHandler:^(NSRunningApplication *app,NSError *error) { helper=app; helperLaunched=YES; }];
        NSDate *launchDeadline=[NSDate dateWithTimeIntervalSinceNow:10];
        while(!helperLaunched && launchDeadline.timeIntervalSinceNow>0) pump(.05);
        require(helper!=nil,@"helper launch failed");
        NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:10];
        while(NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier!=helper.processIdentifier && deadline.timeIntervalSinceNow>0) pump(.05);
        pump(.6);
        pid_t expected=helper.processIdentifier;
        result[@"helperPID"]=@(expected);
        // Leave ownership before native calls, so the runner can clean up this
        // exact helper even if a native crash/timeout prevents our finally.
        [[NSJSONSerialization dataWithJSONObject:result options:0 error:nil] writeToFile:[NSString stringWithUTF8String:argv[3]] atomically:YES];
        result[@"initialFrontmostPID"]=@(NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier); result[@"initialHostActive"]=@(NSApp.active);
        require(NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier==expected && !NSApp.active,@"helper must own foreground before attachment");
        library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
        require(library!=nullptr,@"surface library failed to load");
        auto create=(void*(*)(void*,void*))dlsym(library,"ps_create");
        destroy=(void(*)(void*))dlsym(library,"ps_destroy");
        auto receipt=(const char*(*)(void*))dlsym(library,"ps_receipt");
        require(create && destroy && receipt,@"native exports missing");
        // Exactly the adapter path used by initial playback and each autoplayed item.
        // No timing-only stub: libmpv creates/frees its real OpenGL render context.
        for(NSString *phase in @[@"startWhileInactive",@"replacementWhileInactive"]) {
            mpv=mpv_create(); require(mpv!=nullptr,@"mpv_create failed");
            require(mpv_set_option_string(mpv,"vo","libmpv")==0,@"vo option failed");
            require(mpv_set_option_string(mpv,"ao","null")==0,@"ao option failed");
            require(mpv_initialize(mpv)==0,@"mpv_initialize failed");
            surface=create((__bridge void*)window,mpv);
            require(surface!=nullptr,@"real native surface creation failed");
            pump(.4);
            NSData *data=[[NSString stringWithUTF8String:receipt(surface)] dataUsingEncoding:NSUTF8StringEncoding];
            NSDictionary *native=[NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
            pid_t observed=NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier;
            BOOL active=NSApp.active;
            [steps addObject:@{@"phase":phase,@"frontmostUnchanged":@(observed==expected),@"applicationActive":@(active),@"nativeApplicationActive":native[@"applicationActive"]?:NSNull.null}];
            require(observed==expected && !active && [native[@"applicationActive"] isEqual:@NO],@"surface attachment stole activation from helper");
            destroy(surface); surface=nullptr;
            mpv_terminate_destroy(mpv); mpv=nullptr;
        }
        result[@"pass"]=@YES;
    } @catch(NSException *error) {
        result[@"pass"]=@NO;
        result[@"reason"]=error.reason;
        code=1;
    } @finally {
        if(surface && destroy) destroy(surface);
        if(mpv) mpv_terminate_destroy(mpv);
        if(helper && !helper.terminated) { [helper terminate]; NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:3]; while(!helper.terminated && deadline.timeIntervalSinceNow>0) pump(.05); if(!helper.terminated) [helper forceTerminate]; }
        [window close];
        if(library) dlclose(library);
        result[@"steps"]=steps;
        result[@"helperStopped"]=@(!helper || helper.terminated);
        NSData *json=[NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:nil];
        [json writeToFile:[NSString stringWithUTF8String:argv[3]] atomically:YES];
        fwrite(json.bytes,1,json.length,stdout); puts("");
    }
    return code;
} }
