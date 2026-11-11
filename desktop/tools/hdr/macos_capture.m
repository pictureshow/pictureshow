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
#include <dlfcn.h>
#include <stdatomic.h>
@interface PSHDRSink : NSObject <SCStreamOutput>
@property(copy) void (^callback)(CMSampleBufferRef);
@property int seen;
@end
@implementation PSHDRSink
-(void)stream:(SCStream*)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    if(type!=SCStreamOutputTypeScreen || !CMSampleBufferIsValid(sample) || !CMSampleBufferGetImageBuffer(sample)) return;
    NSArray *attachments=(__bridge NSArray*)CMSampleBufferGetSampleAttachmentsArray(sample,false);
    NSNumber *status=attachments.firstObject[SCStreamFrameInfoStatus];
    if(!status || status.integerValue!=SCFrameStatusComplete) return;
    if(++self.seen==1) self.callback(sample);
}
@end
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
    void *sky=dlopen("/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight",RTLD_LAZY);
    int (*hdrEnabled)(uint32_t)=(int(*)(uint32_t))dlsym(sky,"SLSDisplayIsHDRModeEnabled");
    int (*hdrSupported)(uint32_t)=(int(*)(uint32_t))dlsym(sky,"SLSDisplaySupportsHDRMode");
    NSMutableArray *screens=[NSMutableArray new];
    for(NSScreen *s in NSScreen.screens) [screens addObject:@{@"id":s.deviceDescription[@"NSScreenNumber"],@"name":s.localizedName,@"hdrModeEnabled":hdrEnabled?@(hdrEnabled([s.deviceDescription[@"NSScreenNumber"] unsignedIntValue])):@"unknown",@"hdrSupported":hdrSupported?@(hdrSupported([s.deviceDescription[@"NSScreenNumber"] unsignedIntValue])):@"unknown",@"frame":NSStringFromRect(s.frame),@"headroom":@(s.maximumExtendedDynamicRangeColorComponentValue),@"potentialHeadroom":@(s.maximumPotentialExtendedDynamicRangeColorComponentValue)}];
    NSDictionary *preflight=@{@"architecture":[NSString stringWithUTF8String:machine.machine],@"screenRecordingPreflight":@(CGPreflightScreenCaptureAccess()),@"screens":screens};
    if(argc<2 || strcmp(argv[1],"preflight")==0) {writeJSON(preflight,nil);return 0;}
    if(strcmp(machine.machine,"arm64")!=0) {writeJSON(@{@"pass":@NO,@"error":@"ScreenCaptureKit HDR capture requires Apple Silicon",@"preflight":preflight},nil);return 2;}
    if(!CGPreflightScreenCaptureAccess()) {writeJSON(@{@"pass":@NO,@"error":@"Screen Recording permission unavailable; no permission reset or dialog requested",@"preflight":preflight},nil);return 2;}
    if(@available(macOS 15.0,*)) {
        NSString *out=[NSString stringWithUTF8String:argv[1]];
        BOOL canonical=argc>2 && strcmp(argv[2],"canonical")==0;
        __block _Atomic(bool) done=false;__block int rc=2;
        __block SCStream *liveStream=nil; __block PSHDRSink *sink=nil;
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
            BOOL fullDisplay=argc>3 && strcmp(argv[3],"display")==0;
            SCContentFilter *filter=fullDisplay?[[SCContentFilter alloc] initWithDisplay:display excludingWindows:@[]]:[[SCContentFilter alloc] initWithDisplay:display includingWindows:windows];
            SCStreamConfiguration *config=[SCStreamConfiguration streamConfigurationWithPreset:canonical?SCStreamConfigurationPresetCaptureHDRScreenshotCanonicalDisplay:SCStreamConfigurationPresetCaptureHDRScreenshotLocalDisplay];
            config.sourceRect=CGRectMake(overlay.frame.origin.x-display.frame.origin.x,overlay.frame.origin.y-display.frame.origin.y,overlay.frame.size.width,overlay.frame.size.height);
            config.width=1280;config.height=(size_t)round(1280*overlay.frame.size.height/overlay.frame.size.width);
            BOOL preservePreset=getenv("SPIKE_HDR_CAPTURE_PRESET")!=NULL;
            BOOL pqPreset=getenv("SPIKE_HDR_CAPTURE_PQ")!=NULL;
            BOOL pq2020=pqPreset && getenv("SPIKE_HDR_CAPTURE_PQ2020")!=NULL;
            if(pqPreset) config=[SCStreamConfiguration streamConfigurationWithPreset:canonical?SCStreamConfigurationPresetCaptureHDRStreamCanonicalDisplay:SCStreamConfigurationPresetCaptureHDRStreamLocalDisplay];
            if(pqPreset) {
                config.sourceRect=CGRectMake(overlay.frame.origin.x-display.frame.origin.x,overlay.frame.origin.y-display.frame.origin.y,overlay.frame.size.width,overlay.frame.size.height);
                config.width=1280;config.height=(size_t)round(1280*overlay.frame.size.height/overlay.frame.size.width);
            }
            config.showsCursor=NO;if(!pqPreset) config.pixelFormat=kCVPixelFormatType_64RGBAHalf;
            if(!preservePreset && !pqPreset) config.colorSpaceName=kCGColorSpaceExtendedLinearITUR_2020;
            if(pq2020){config.colorSpaceName=kCGColorSpaceITUR_2100_PQ;}
            config.captureDynamicRange=canonical?SCCaptureDynamicRangeHDRCanonicalDisplay:SCCaptureDynamicRangeHDRLocalDisplay;
            void (^receive)(CMSampleBufferRef,NSError*)=^(CMSampleBufferRef sample,NSError *captureError) {
                if(done) return;
                if(captureError || !sample) {writeJSON(@{@"pass":@NO,@"error":captureError.localizedDescription?:@"no sample"},out);done=YES;return;}
                CVPixelBufferRef pixels=CMSampleBufferGetImageBuffer(sample);
                OSType fmt=CVPixelBufferGetPixelFormatType(pixels);size_t w=CVPixelBufferGetWidth(pixels),h=CVPixelBufferGetHeight(pixels);
                CFDictionaryRef attachments=CVBufferCopyAttachments(pixels,kCVAttachmentMode_ShouldPropagate);
                NSDictionary *meta=attachments?CFBridgingRelease(attachments):@{};
                NSDictionary *formatMeta=(__bridge NSDictionary*)CMFormatDescriptionGetExtensions(CMSampleBufferGetFormatDescription(sample));
                NSString *prim=meta[(__bridge NSString*)kCVImageBufferColorPrimariesKey] ?: formatMeta[(__bridge NSString*)kCMFormatDescriptionExtension_ColorPrimaries];
                NSString *transfer=meta[(__bridge NSString*)kCVImageBufferTransferFunctionKey] ?: formatMeta[(__bridge NSString*)kCMFormatDescriptionExtension_TransferFunction];
                CGColorSpaceRef actualCS=(__bridge CGColorSpaceRef)meta[(__bridge NSString*)kCVImageBufferCGColorSpaceKey];
                NSData *actualICC=meta[@"CVImageBufferICCProfile"] ?: formatMeta[@"CVImageBufferICCProfile"];
                CGColorSpaceRef knownCS=CGColorSpaceCreateWithName(pq2020?kCGColorSpaceITUR_2100_PQ:pqPreset?kCGColorSpaceDisplayP3_PQ:preservePreset?kCGColorSpaceExtendedDisplayP3:kCGColorSpaceExtendedLinearITUR_2020);
                NSData *knownICC=CFBridgingRelease(CGColorSpaceCopyICCData(knownCS));
                BOOL exactICC=actualICC && [actualICC isEqualToData:knownICC];
                if(actualICC) [actualICC writeToFile:[out stringByAppendingString:@".icc"] atomically:YES];
                CGColorSpaceRelease(knownCS);
                NSString *csName=actualCS?(__bridge NSString*)CGColorSpaceGetName(actualCS):@"";
                BOOL actualColorProven=(!prim || [prim isEqualToString:(__bridge NSString*)kCVImageBufferColorPrimaries_ITU_R_2020]) && (!transfer || [transfer isEqualToString:(__bridge NSString*)kCVImageBufferTransferFunction_Linear]) && (([prim isEqualToString:(__bridge NSString*)kCVImageBufferColorPrimaries_ITU_R_2020] && [transfer isEqualToString:(__bridge NSString*)kCVImageBufferTransferFunction_Linear]) || exactICC || [csName isEqualToString:(__bridge NSString*)kCGColorSpaceExtendedLinearITUR_2020]);
                if(preservePreset) actualColorProven=exactICC &&
                    (!prim || [prim isEqualToString:(__bridge NSString*)kCVImageBufferColorPrimaries_P3_D65]) &&
                    (!transfer || [transfer isEqualToString:(__bridge NSString*)kCVImageBufferTransferFunction_sRGB]);
                NSString *matrix=meta[(__bridge NSString*)kCVImageBufferYCbCrMatrixKey] ?: formatMeta[(__bridge NSString*)kCMFormatDescriptionExtension_YCbCrMatrix];
                BOOL matrix2020=[matrix isEqualToString:(__bridge NSString*)kCVImageBufferYCbCrMatrix_ITU_R_2020];
                BOOL matrix709=[matrix isEqualToString:(__bridge NSString*)kCVImageBufferYCbCrMatrix_ITU_R_709_2];
                NSString *pqPrimaries=(__bridge NSString*)(pq2020?kCVImageBufferColorPrimaries_ITU_R_2020:kCVImageBufferColorPrimaries_P3_D65);
                if(pqPreset) actualColorProven=(exactICC || ([prim isEqualToString:pqPrimaries] && [transfer isEqualToString:(__bridge NSString*)kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ])) && (!prim || [prim isEqualToString:pqPrimaries]) && (!transfer || [transfer isEqualToString:(__bridge NSString*)kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ]) && (matrix2020 || matrix709);
                if(!actualColorProven) {writeJSON(@{@"pass":@NO,@"error":@"Actual capture color attachments do not establish the requested transfer/primaries",@"actualPrimaries":prim?:@"",@"actualTransfer":transfer?:@"",@"actualColorSpace":csName?:@"",@"actualPixelFormat":@(fmt),@"attachments":meta.description,@"formatAttachments":formatMeta.description?:@""},out);done=YES;return;}
                NSNumber *captureHeadroom=@0;
                for(NSScreen *screen in NSScreen.screens) if([screen.deviceDescription[@"NSScreenNumber"] unsignedIntValue]==display.displayID) captureHeadroom=@(screen.maximumExtendedDynamicRangeColorComponentValue);
                if(fmt!=(pqPreset?kCVPixelFormatType_444YpCbCr10BiPlanarFullRange:kCVPixelFormatType_64RGBAHalf)) {writeJSON(@{@"pass":@NO,@"error":@"Capture is not RGBA half float",@"format":@(fmt),@"attachments":meta.description},out);done=YES;return;}
                if(CVPixelBufferLockBaseAddress(pixels,kCVPixelBufferLock_ReadOnly)!=kCVReturnSuccess) {writeJSON(@{@"pass":@NO,@"error":@"Pixel buffer lock failed"},out);done=YES;return;}
                if(pqPreset && (CVPixelBufferGetPlaneCount(pixels)!=2 || CVPixelBufferGetHeightOfPlane(pixels,0)!=h || CVPixelBufferGetHeightOfPlane(pixels,1)!=h || CVPixelBufferGetBytesPerRowOfPlane(pixels,0)<2*w || CVPixelBufferGetBytesPerRowOfPlane(pixels,1)<4*w)) {CVPixelBufferUnlockBaseAddress(pixels,kCVPixelBufferLock_ReadOnly);writeJSON(@{@"pass":@NO,@"error":@"Unexpected xf44 plane geometry"},out);done=YES;return;}
                size_t stride=CVPixelBufferGetBytesPerRow(pixels);uint8_t *base=CVPixelBufferGetBaseAddress(pixels);
                NSString *names[] = {@"black",@"gray100",@"gray203",@"gray1000",@"red100",@"green100",@"blue100",@"gray50"};
                NSMutableArray *samples=[NSMutableArray new];
                // Native app preserves 16:9 inside window. Midline is inside video and clear of controls.
                for(int patch=0;patch<8;patch++) {
                    NSMutableArray *rgb=[NSMutableArray new];
                    for(int c=0;c<3;c++) {float values[441];int n=0;
                        for(int dy=-10;dy<=10;dy++) for(int dx=-10;dx<=10;dx++) {
                            size_t x=(size_t)((patch+.5)*w/8)+dx,y=h/2+dy;
                            if(pqPreset) {
                                uint16_t *yp=(uint16_t*)((uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pixels,0)+y*CVPixelBufferGetBytesPerRowOfPlane(pixels,0));
                                uint16_t *uv=(uint16_t*)((uint8_t*)CVPixelBufferGetBaseAddressOfPlane(pixels,1)+y*CVPixelBufferGetBytesPerRowOfPlane(pixels,1));
                                double Y=(yp[x]>>6)/1023.,Cb=((uv[2*x]>>6)-512.)/1023.,Cr=((uv[2*x+1]>>6)-512.)/1023.;
                                double kr=matrix2020?.2627:.2126,kb=matrix2020?.0593:.0722;
                                double q[3]={Y+2*(1-kr)*Cr,Y-2*kb*(1-kb)/(1-kr-kb)*Cb-2*kr*(1-kr)/(1-kr-kb)*Cr,Y+2*(1-kb)*Cb};
                                double v=fmax(q[c],0),z=pow(v,1/(2523./32));
                                values[n++]=10000*pow(fmax(z-3424./4096,0)/(2413./128-2392./128*z),1/(2610./16384))/203;
                            } else {uint16_t *p=(uint16_t*)(base+y*stride+x*8);values[n++]=half(p[c]);}
                        }
                        qsort(values,n,sizeof(float),compareFloat);[rgb addObject:@(values[n/2])];
                    }
                    if(preservePreset || (pqPreset && !pq2020)) {
                        double p3[3]; for(int c=0;c<3;c++){double v=[rgb[c] doubleValue],a=fabs(v);p3[c]=pqPreset?v:copysign(a<=.04045?a/12.92:pow((a+.055)/1.055,2.4),v);}
                        // P3-D65 -> BT.2020, derived from published RGB chromaticities/D65.
                        const double m[3][3]={{.753833034,.198597369,.047569597},{.045743849,.941777220,.012478931},{-.001210340,.017601717,.983608623}};
                        for(int c=0;c<3;c++)rgb[c]=@(m[c][0]*p3[0]+m[c][1]*p3[1]+m[c][2]*p3[2]);
                    }
                    [samples addObject:@{@"name":names[patch],@"rgb":rgb}];
                }
                // Save only the app-filtered raw capture; no other desktop contents.
                NSMutableData *raw=[NSMutableData new];
                if(pqPreset) for(int plane=0;plane<2;plane++) [raw appendBytes:CVPixelBufferGetBaseAddressOfPlane(pixels,plane) length:CVPixelBufferGetBytesPerRowOfPlane(pixels,plane)*h];
                else for(size_t y=0;y<h;y++) [raw appendBytes:base+y*stride length:w*8];
                [raw writeToFile:[out stringByAppendingString:pqPreset?@".xf44":@".rgba16f"] atomically:YES];
                CVPixelBufferUnlockBaseAddress(pixels,kCVPixelBufferLock_ReadOnly);
                writeJSON(@{@"boundary":@"ScreenCaptureKit display including app windows, sourceRect cropped",@"encoding":@"linear",@"primaries":@"bt2020",@"referenceWhiteNits":@203,@"actualColorProven":@YES,@"actualICCMatchesRequestedSpace":@(exactICC),@"preservedScreenshotPreset":@(preservePreset),@"actualPrimaries":prim?:@"",@"actualTransfer":transfer?:@"",@"actualColorSpace":csName?:@"",@"captureHeadroom":captureHeadroom,@"samples":samples,@"format":pqPreset?@"xf44":@"RGhA",@"ycbcrMatrix":matrix?:@"",@"pqPreset":@(pqPreset),@"pq2020":@(pq2020),@"rawEncoding":pqPreset?@"PQ":preservePreset?@"extended sRGB transfer":@"linear",@"rawPrimaries":pqPreset?(pq2020?@"bt2020":@"displayp3"):preservePreset?@"displayp3":@"bt2020",@"planeStrides":pqPreset?@[@(CVPixelBufferGetBytesPerRowOfPlane(pixels,0)),@(CVPixelBufferGetBytesPerRowOfPlane(pixels,1))]:@[@(stride)],@"width":@(w),@"height":@(h),@"attachments":meta.description,@"mode":canonical?@"canonical":@"local",@"fullDisplay":@(fullDisplay),@"configuredDynamicRange":@(config.captureDynamicRange),@"displayID":@(display.displayID),@"windows":winreceipt,@"preflight":preflight},out);
                rc=0;done=YES;
            };
            if(argc>4 && strcmp(argv[4],"stream")==0) {
                sink=[PSHDRSink new];sink.callback=^(CMSampleBufferRef sample){receive(sample,nil);};
                liveStream=[[SCStream alloc]initWithFilter:filter configuration:config delegate:nil];
                NSError *outputError=nil;
                BOOL ok=[liveStream addStreamOutput:sink type:SCStreamOutputTypeScreen sampleHandlerQueue:dispatch_queue_create("app.pictureshow.hdr-capture",DISPATCH_QUEUE_SERIAL) error:&outputError];
                if(!ok){receive(nil,outputError);return;}
                [liveStream startCaptureWithCompletionHandler:^(NSError *error){if(error)receive(nil,error);}];
            } else [SCScreenshotManager captureSampleBufferWithFilter:filter configuration:config completionHandler:receive];
        }];
        NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:20];
        while(!done && deadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.05]];
        if(liveStream) {
            __block _Atomic(bool) stopped=false;
            [liveStream stopCaptureWithCompletionHandler:^(NSError *error){stopped=true;}];
            NSDate *stopDeadline=[NSDate dateWithTimeIntervalSinceNow:3];
            while(!stopped && stopDeadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.05]];
        }
        if(!done) writeJSON(@{@"pass":@NO,@"error":@"ScreenCaptureKit timeout"},out);
        return rc;
    }
    writeJSON(@{@"pass":@NO,@"error":@"macOS15 or newer required"},nil);return 2;
}}
