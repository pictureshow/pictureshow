/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Independent ScreenCaptureKit half-float readback, no app texture access.
 */
#import <AppKit/AppKit.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#include <sys/utsname.h>
#include <math.h>
static void writeJSON(NSDictionary *obj, NSString *path) {
    NSData *d=[NSJSONSerialization dataWithJSONObject:obj options:NSJSONWritingPrettyPrinted error:nil];
    if(path) [d writeToFile:path atomically:YES]; else {fwrite(d.bytes,1,d.length,stdout);puts("");}
}
static float half(uint16_t h) {
    unsigned s=h>>15, e=(h>>10)&31, m=h&1023;
    double v=e==0?ldexp(m,-24):e==31?(m?NAN:INFINITY):ldexp(1024+m,(int)e-25);
    return s?-v:v;
}
static int compareFloat(const void *a,const void *b) {float x=*(float*)a,y=*(float*)b;return (x>y)-(x<y);}
int main(int argc,const char **argv) { @autoreleasepool {
    struct utsname machine;uname(&machine);
    NSMutableArray *screens=[NSMutableArray new];
    for(NSScreen *s in NSScreen.screens) [screens addObject:@{@"id":s.deviceDescription[@"NSScreenNumber"],@"name":s.localizedName,@"frame":NSStringFromRect(s.frame),@"headroom":@(s.maximumExtendedDynamicRangeColorComponentValue),@"potentialHeadroom":@(s.maximumPotentialExtendedDynamicRangeColorComponentValue)}];
    NSDictionary *preflight=@{@"architecture":[NSString stringWithUTF8String:machine.machine],@"screenRecordingPreflight":@(CGPreflightScreenCaptureAccess()),@"screens":screens};
    if(argc<2 || strcmp(argv[1],"preflight")==0) {writeJSON(preflight,nil);return 0;}
    if(strcmp(machine.machine,"arm64")!=0) {writeJSON(@{@"pass":@NO,@"error":@"ScreenCaptureKit HDR capture requires Apple Silicon",@"preflight":preflight},nil);return 2;}
    if(!CGPreflightScreenCaptureAccess()) {writeJSON(@{@"pass":@NO,@"error":@"Screen Recording permission unavailable; no permission reset or dialog requested",@"preflight":preflight},nil);return 2;}
    if(@available(macOS 15.0,*)) {
        NSString *out=[NSString stringWithUTF8String:argv[1]];
        BOOL canonical=argc>2 && strcmp(argv[2],"canonical")==0;
        __block BOOL done=NO;__block int rc=2;
        [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:YES completionHandler:^(SCShareableContent *content,NSError *error) {
            if(error) {writeJSON(@{@"pass":@NO,@"error":error.localizedDescription},out);done=YES;return;}
            SCWindow *overlay=nil; for(SCWindow *w in content.windows) if([w.title isEqualToString:@"Pictureshow native HDR surface"]) {overlay=w;break;}
            if(!overlay) {writeJSON(@{@"pass":@NO,@"error":@"Pictureshow numeric test window absent"},out);done=YES;return;}
            SCDisplay *display=nil;for(SCDisplay *d in content.displays) if(CGRectIntersectsRect(d.frame,overlay.frame)){display=d;break;}
            if(!display) {writeJSON(@{@"pass":@NO,@"error":@"Test window has no display"},out);done=YES;return;}
            NSMutableArray *windows=[NSMutableArray new];NSMutableArray *winreceipt=[NSMutableArray new];
            for(SCWindow *w in content.windows) if(w.owningApplication.processID==overlay.owningApplication.processID) {
                [windows addObject:w];[winreceipt addObject:@{@"id":@(w.windowID),@"title":w.title?:@"",@"frame":NSStringFromRect(w.frame)}];
            }
            // Display filter retains actual app windows/child video in compositor order.
            SCContentFilter *filter=[[SCContentFilter alloc] initWithDisplay:display includingWindows:windows];
            SCStreamConfiguration *config=[SCStreamConfiguration streamConfigurationWithPreset:canonical?SCStreamConfigurationPresetCaptureHDRScreenshotCanonicalDisplay:SCStreamConfigurationPresetCaptureHDRScreenshotLocalDisplay];
            config.sourceRect=CGRectMake(overlay.frame.origin.x-display.frame.origin.x,overlay.frame.origin.y-display.frame.origin.y,overlay.frame.size.width,overlay.frame.size.height);
            config.width=1280;config.height=(size_t)round(1280*overlay.frame.size.height/overlay.frame.size.width);
            config.showsCursor=NO;config.pixelFormat=kCVPixelFormatType_64RGBAHalf;
            config.colorSpaceName=kCGColorSpaceExtendedLinearITUR_2020;
            [SCScreenshotManager captureSampleBufferWithFilter:filter configuration:config completionHandler:^(CMSampleBufferRef sample,NSError *captureError) {
                if(captureError || !sample) {writeJSON(@{@"pass":@NO,@"error":captureError.localizedDescription?:@"no sample"},out);done=YES;return;}
                CVPixelBufferRef pixels=CMSampleBufferGetImageBuffer(sample);
                OSType fmt=CVPixelBufferGetPixelFormatType(pixels);size_t w=CVPixelBufferGetWidth(pixels),h=CVPixelBufferGetHeight(pixels);
                CFDictionaryRef attachments=CVBufferCopyAttachments(pixels,kCVAttachmentMode_ShouldPropagate);
                NSDictionary *meta=attachments?CFBridgingRelease(attachments):@{};
                NSDictionary *formatMeta=(__bridge NSDictionary*)CMFormatDescriptionGetExtensions(CMSampleBufferGetFormatDescription(sample));
                NSString *prim=meta[(__bridge NSString*)kCVImageBufferColorPrimariesKey] ?: formatMeta[(__bridge NSString*)kCMFormatDescriptionExtension_ColorPrimaries];
                NSString *transfer=meta[(__bridge NSString*)kCVImageBufferTransferFunctionKey] ?: formatMeta[(__bridge NSString*)kCMFormatDescriptionExtension_TransferFunction];
                CGColorSpaceRef actualCS=(__bridge CGColorSpaceRef)meta[(__bridge NSString*)kCVImageBufferCGColorSpaceKey];
                NSString *csName=actualCS?(__bridge NSString*)CGColorSpaceGetName(actualCS):@"";
                BOOL actualColorProven=([prim isEqualToString:(__bridge NSString*)kCVImageBufferColorPrimaries_ITU_R_2020] && [transfer isEqualToString:(__bridge NSString*)kCVImageBufferTransferFunction_Linear]) || [csName isEqualToString:(__bridge NSString*)kCGColorSpaceExtendedLinearITUR_2020];
                if(!actualColorProven) {writeJSON(@{@"pass":@NO,@"error":@"Actual capture color attachments do not establish linear BT2020",@"actualPrimaries":prim?:@"",@"actualTransfer":transfer?:@"",@"actualColorSpace":csName?:@"",@"attachments":meta.description,@"formatAttachments":formatMeta.description?:@""},out);done=YES;return;}
                NSNumber *captureHeadroom=@0;
                for(NSScreen *screen in NSScreen.screens) if([screen.deviceDescription[@"NSScreenNumber"] unsignedIntValue]==display.displayID) captureHeadroom=@(screen.maximumExtendedDynamicRangeColorComponentValue);
                if(fmt!=kCVPixelFormatType_64RGBAHalf) {writeJSON(@{@"pass":@NO,@"error":@"Capture is not RGBA half float",@"format":@(fmt),@"attachments":meta.description},out);done=YES;return;}
                CVPixelBufferLockBaseAddress(pixels,kCVPixelBufferLock_ReadOnly);
                size_t stride=CVPixelBufferGetBytesPerRow(pixels);uint8_t *base=CVPixelBufferGetBaseAddress(pixels);
                NSString *names[] = {@"black",@"gray100",@"gray203",@"gray1000",@"red100",@"green100",@"blue100",@"gray50"};
                NSMutableArray *samples=[NSMutableArray new];
                // Native app preserves 16:9 inside window. Midline is inside video and clear of controls.
                for(int patch=0;patch<8;patch++) {
                    NSMutableArray *rgb=[NSMutableArray new];
                    for(int c=0;c<3;c++) {float values[441];int n=0;
                        for(int dy=-10;dy<=10;dy++) for(int dx=-10;dx<=10;dx++) {
                            size_t x=(size_t)((patch+.5)*w/8)+dx,y=h/2+dy;
                            uint16_t *p=(uint16_t*)(base+y*stride+x*8);values[n++]=half(p[c]);
                        }
                        qsort(values,n,sizeof(float),compareFloat);[rgb addObject:@(values[n/2])];
                    }
                    [samples addObject:@{@"name":names[patch],@"rgb":rgb}];
                }
                // Save only the app-filtered raw capture; no other desktop contents.
                NSMutableData *raw=[NSMutableData new];for(size_t y=0;y<h;y++) [raw appendBytes:base+y*stride length:w*8];
                [raw writeToFile:[out stringByAppendingString:@".rgba16f"] atomically:YES];
                CVPixelBufferUnlockBaseAddress(pixels,kCVPixelBufferLock_ReadOnly);
                writeJSON(@{@"boundary":@"ScreenCaptureKit display including app windows, sourceRect cropped",@"encoding":@"linear",@"primaries":@"bt2020",@"referenceWhiteNits":@203,@"actualColorProven":@YES,@"actualPrimaries":prim?:@"",@"actualTransfer":transfer?:@"",@"actualColorSpace":csName?:@"",@"captureHeadroom":captureHeadroom,@"samples":samples,@"format":@"RGhA",@"width":@(w),@"height":@(h),@"attachments":meta.description,@"mode":canonical?@"canonical":@"local",@"displayID":@(display.displayID),@"windows":winreceipt,@"preflight":preflight},out);
                rc=0;done=YES;
            }];
        }];
        NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:20];
        while(!done && deadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.05]];
        if(!done) writeJSON(@{@"pass":@NO,@"error":@"ScreenCaptureKit timeout"},out);
        return rc;
    }
    writeJSON(@{@"pass":@NO,@"error":@"macOS15 or newer required"},nil);return 2;
}}
