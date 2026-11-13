/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * macOS26 HDR screenshot API; raw extended-sRGB samples, no app texture access.
 */
#import <AppKit/AppKit.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#include <math.h>
#include <sys/utsname.h>
static void output(NSDictionary*d,NSString*p){NSData*j=[NSJSONSerialization dataWithJSONObject:d options:NSJSONWritingPrettyPrinted error:nil];[j writeToFile:p atomically:YES];}
static double half(uint16_t h){int e=(h>>10)&31,m=h&1023;double v=e==0?ldexp(m,-24):e==31?(m?NAN:INFINITY):ldexp(1024+m,e-25);return h&32768?-v:v;}
static double linear(double v){return fabs(v)<=.04045?v/12.92:copysign(pow((fabs(v)+.055)/1.055,2.4),v);}
static int compare(const void*a,const void*b){double x=*(double*)a,y=*(double*)b;return(x>y)-(x<y);}
int main(int argc,const char**argv){@autoreleasepool{
 if(argc>1&&strcmp(argv[1],"preflight")==0){struct utsname u;uname(&u);NSMutableArray*screens=[NSMutableArray new];for(NSScreen*s in NSScreen.screens)[screens addObject:@{@"id":s.deviceDescription[@"NSScreenNumber"],@"name":s.localizedName,@"headroom":@(s.maximumExtendedDynamicRangeColorComponentValue),@"potentialHeadroom":@(s.maximumPotentialExtendedDynamicRangeColorComponentValue)}];NSDictionary*d=@{@"architecture":[NSString stringWithUTF8String:u.machine],@"screenRecordingPreflight":@(CGPreflightScreenCaptureAccess()),@"screens":screens};NSData*j=[NSJSONSerialization dataWithJSONObject:d options:NSJSONWritingPrettyPrinted error:nil];fwrite(j.bytes,1,j.length,stdout);puts("");return 0;}
 if(argc<2)return 2;NSString*path=[NSString stringWithUTF8String:argv[1]];BOOL canonical=argc>2&&strcmp(argv[2],"canonical")==0;
 if(@available(macOS 26.0,*)){
 __block BOOL done=NO;__block int rc=2;
 [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:YES completionHandler:^(SCShareableContent*content,NSError*error){
  SCWindow*window=nil;for(SCWindow*w in content.windows)if([w.title isEqualToString:@"Pictureshow native HDR surface"]){window=w;break;}
  if(error||!window){output(@{@"pass":@NO,@"error":error.localizedDescription?:@"test app absent"},path);done=YES;return;}
  SCDisplay*display=nil;for(SCDisplay*d in content.displays)if(CGRectIntersectsRect(d.frame,window.frame)){display=d;break;}
  if(!display){output(@{@"pass":@NO,@"error":@"app display absent"},path);done=YES;return;}
  SCContentFilter*filter=[[SCContentFilter alloc]initWithDisplay:display excludingWindows:@[]];
  SCScreenshotConfiguration*config=[SCScreenshotConfiguration new];config.dynamicRange=SCScreenshotDynamicRangeHDR;
  config.displayIntent=canonical?SCScreenshotDisplayIntentCanonical:SCScreenshotDisplayIntentLocal;
  config.showsCursor=NO;config.width=960;config.height=640;
  config.sourceRect=CGRectMake(window.frame.origin.x-display.frame.origin.x,window.frame.origin.y-display.frame.origin.y,window.frame.size.width,window.frame.size.height);
  [SCScreenshotManager captureScreenshotWithFilter:filter configuration:config completionHandler:^(SCScreenshotOutput*shot,NSError*error){
   CGImageRef image=shot.hdrImage;if(error||!image){output(@{@"pass":@NO,@"error":error.localizedDescription?:@"HDR image absent"},path);done=YES;return;}
   size_t w=CGImageGetWidth(image),h=CGImageGetHeight(image),bits=CGImageGetBitsPerComponent(image),pixel=CGImageGetBitsPerPixel(image),stride=CGImageGetBytesPerRow(image);
   CGBitmapInfo bitmap=CGImageGetBitmapInfo(image);CGImageAlphaInfo alpha=CGImageGetAlphaInfo(image);CGColorSpaceRef cs=CGImageGetColorSpace(image);
   NSString*csname=(__bridge NSString*)CGColorSpaceGetName(cs);CGColorSpaceRef expected=CGColorSpaceCreateWithName(kCGColorSpaceExtendedSRGB);
   NSData*icc=CFBridgingRelease(CGColorSpaceCopyICCData(cs));NSData*ref=CFBridgingRelease(CGColorSpaceCopyICCData(expected));BOOL matched=CFEqual(cs,expected)||(icc&&[icc isEqualToData:ref]);CGColorSpaceRelease(expected);
   if(!matched||!(bitmap&kCGBitmapFloatComponents)||(bits!=16&&bits!=32)||pixel!=bits*4||(alpha!=kCGImageAlphaPremultipliedLast&&alpha!=kCGImageAlphaLast)){
    output(@{@"pass":@NO,@"error":@"Unexpected actual HDR image format or colorspace",@"colorSpace":csname?:@"unnamed",@"ICCMatchesExtendedSRGB":@(matched),@"bits":@(bits),@"pixelBits":@(pixel),@"bitmap":@(bitmap),@"alpha":@(alpha),@"width":@(w),@"height":@(h)},path);done=YES;return;}
   NSData*data=CFBridgingRelease(CGDataProviderCopyData(CGImageGetDataProvider(image)));const uint8_t*base=data.bytes;NSMutableData*packed=[NSMutableData new];for(size_t y=0;y<h;y++)[packed appendBytes:base+y*stride length:w*pixel/8];[packed writeToFile:[path stringByAppendingString:@".raw"] atomically:YES];
   NSString*names[]={@"black",@"gray100",@"gray203",@"gray1000",@"red100",@"green100",@"blue100",@"gray50"};NSMutableArray*samples=[NSMutableArray new];
   for(int patch=0;patch<8;patch++){NSMutableArray*rgb=[NSMutableArray new];for(int c=0;c<3;c++){double v[49];int n=0;for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++){
    size_t x=(size_t)((patch+.5)*w/8)+dx,y=h/2+dy;const uint8_t*p=base+y*stride+x*pixel/8;double a=bits==16?half(((uint16_t*)p)[3]):((float*)p)[3];double value=bits==16?half(((uint16_t*)p)[c]):((float*)p)[c];
    v[n++]=linear(a>0?value/a:value);
   }qsort(v,n,sizeof(double),compare);[rgb addObject:@(v[n/2])];}[samples addObject:@{@"name":names[patch],@"rgb":rgb}];}
   NSNumber*headroom=@0;for(NSScreen*s in NSScreen.screens)if([s.deviceDescription[@"NSScreenNumber"] unsignedIntValue]==display.displayID)headroom=@(s.maximumExtendedDynamicRangeColorComponentValue);
   output(@{@"encoding":@"linear",@"primaries":@"bt709",@"referenceWhiteNits":@203,@"samples":samples,@"boundary":@"macOS26 SCScreenshotManager HDR CGImage, display crop",@"colorSpace":csname?:@"unnamed",@"ICCMatchesExtendedSRGB":@(matched),@"captureHeadroom":headroom,@"displayID":@(display.displayID),@"width":@(w),@"height":@(h),@"bits":@(bits),@"bitmap":@(bitmap),@"alpha":@(alpha)},path);rc=0;done=YES;
  }];
 }];
 NSDate*deadline=[NSDate dateWithTimeIntervalSinceNow:20];while(!done&&deadline.timeIntervalSinceNow>0)[[NSRunLoop currentRunLoop]runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.05]];return rc;
 }output(@{@"pass":@NO,@"error":@"macOS26 required"},path);return 2;
}}
