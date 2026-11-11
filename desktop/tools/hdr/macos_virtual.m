/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Local diagnostic only. Original implementation, private signatures measured
 * from this Mac's ObjC runtime; creation requires explicit --create argument.
 * No mirroring, physical display reconfiguration, gamma tables or HDR toggles.
 */
#import <AppKit/AppKit.h>
#include <dlfcn.h>
#include <signal.h>
@interface CGVirtualDisplayMode : NSObject
@property(readonly) unsigned int transferFunction;
-(instancetype)initWithWidth:(unsigned int)w height:(unsigned int)h refreshRate:(double)hz transferFunction:(unsigned int)tf;
@end
@interface CGVirtualDisplayDescriptor : NSObject
@property unsigned int vendorID,productID,serialNumber,maxPixelsWide,maxPixelsHigh;
@property(retain) NSString *name;
@property CGSize sizeInMillimeters;
@property CGPoint redPrimary,greenPrimary,bluePrimary,whitePoint;
@property(retain) dispatch_queue_t queue;
@end
@interface CGVirtualDisplaySettings : NSObject
@property(retain) NSArray *modes;
@property unsigned int hiDPI,rotation;
@property BOOL isReference;
@end
@interface CGVirtualDisplay : NSObject
@property(readonly) unsigned int displayID;
-(instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor*)descriptor;
-(BOOL)applySettings:(CGVirtualDisplaySettings*)settings;
@end
static int (*supports)(CGDirectDisplayID),(*enabled)(CGDirectDisplayID);
static unsigned int (*bits)(CGDirectDisplayID),(*pixelBits)(CGDirectDisplayID);
static volatile sig_atomic_t stopping;
static void signalHandler(int n){stopping=1;}
static void receipt(const char *phase,CGDirectDisplayID id) {
 CGRect r=CGDisplayBounds(id);NSScreen *screen=nil;
 for(NSScreen *s in NSScreen.screens)if([s.deviceDescription[@"NSScreenNumber"] unsignedIntValue]==id){screen=s;break;}
 CGColorSpaceRef cs=CGDisplayCopyColorSpace(id);NSString *name=cs?(__bridge NSString*)CGColorSpaceGetName(cs):@"";
 NSDictionary *result=@{@"phase":[NSString stringWithUTF8String:phase],@"displayID":@(id),@"main":@(CGDisplayIsMain(id)),@"online":@(CGDisplayIsOnline(id)),@"mirrored":@(CGDisplayIsInMirrorSet(id)),@"bounds":NSStringFromRect(r),@"x":@(r.origin.x),@"y":@(r.origin.y),@"width":@(r.size.width),@"height":@(r.size.height),@"hdrSupported":@(supports?supports(id):-1),@"hdrEnabled":@(enabled?enabled(id):-1),@"bitsPerSample":@(bits?bits(id):0),@"bitsPerPixel":@(pixelBits?pixelBits(id):0),@"colorSpace":name?:@"unnamed",@"headroom":@(screen?screen.maximumExtendedDynamicRangeColorComponentValue:0),@"potentialHeadroom":@(screen?screen.maximumPotentialExtendedDynamicRangeColorComponentValue:0)};
 NSData *data=[NSJSONSerialization dataWithJSONObject:result options:0 error:nil];fwrite(data.bytes,1,data.length,stdout);puts("");fflush(stdout);if(cs)CFRelease(cs);
}
int main(int argc,const char **argv){@autoreleasepool{
 void *sl=dlopen("/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight",RTLD_NOW);
 supports=dlsym(sl,"SLSDisplaySupportsHDRMode");enabled=dlsym(sl,"SLSDisplayIsHDRModeEnabled");bits=dlsym(sl,"SLDisplayBitsPerSample");pixelBits=dlsym(sl,"SLDisplayBitsPerPixel");
 CGDirectDisplayID ids[16];uint32_t count=0;CGGetOnlineDisplayList(16,ids,&count);
 for(uint32_t i=0;i<count;i++)receipt("before",ids[i]);
 if(argc<2 || strcmp(argv[1],"--create")!=0)return 0;
 unsigned tf=argc>2?(unsigned)strtoul(argv[2],NULL,10):1;int seconds=argc>3?atoi(argv[3]):30;
 // Existing HDR calls retain 1280x720 and their 120-second ceiling. Explicit
 // custom dimensions allow the hosted UI smoke one finite 900-second lease.
 unsigned width=argc>4?(unsigned)strtoul(argv[4],NULL,10):1280;
 unsigned height=argc>5?(unsigned)strtoul(argv[5],NULL,10):720;
 if(tf>1 || seconds<1 || seconds>(argc==6?900:120) || argc==5 || argc>6 || width<640 || width>4096 || height<480 || height>2160)return 2;
 signal(SIGINT,signalHandler);signal(SIGTERM,signalHandler);
 CGDirectDisplayID mainBefore=CGMainDisplayID();CGRect mainRect=CGDisplayBounds(mainBefore);
 CGVirtualDisplayDescriptor *d=[CGVirtualDisplayDescriptor new];d.name=@"Pictureshow136 virtual PQ probe";
 d.vendorID=0x5053;d.productID=136;d.serialNumber=13601;d.maxPixelsWide=width;d.maxPixelsHigh=height;
 d.sizeInMillimeters=CGSizeMake(340,191);d.redPrimary=CGPointMake(.708,.292);d.greenPrimary=CGPointMake(.170,.797);d.bluePrimary=CGPointMake(.131,.046);d.whitePoint=CGPointMake(.3127,.3290);d.queue=dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0);
 CGVirtualDisplay *vd=[[CGVirtualDisplay alloc]initWithDescriptor:d];if(!vd){fputs("Virtual display creation refused\n",stderr);return 3;}
 CGVirtualDisplaySettings *s=[CGVirtualDisplaySettings new];s.hiDPI=0;s.rotation=0;s.isReference=(tf==1);
 CGVirtualDisplayMode *mode=[[CGVirtualDisplayMode alloc]initWithWidth:width height:height refreshRate:60 transferFunction:tf];s.modes=@[mode];
 if(![vd applySettings:s]){fputs("Virtual display settings refused\n",stderr);return 4;}
 // Position only the new virtual display; leave all existing displays untouched.
 CGDisplayConfigRef config;CGError ce=CGBeginDisplayConfiguration(&config);
 if(ce==kCGErrorSuccess){ce=CGConfigureDisplayOrigin(config,vd.displayID,(int)CGRectGetMaxX(mainRect)+50,(int)mainRect.origin.y);if(ce==kCGErrorSuccess)ce=CGCompleteDisplayConfiguration(config,kCGConfigureForSession);else CGCancelDisplayConfiguration(config);}
 fprintf(stderr,"virtualID=%u transferFunction=%u originTransaction=%d pid=%d\n",vd.displayID,mode.transferFunction,ce,getpid());
 NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:seconds];
 while(!stopping && deadline.timeIntervalSinceNow>0){[[NSRunLoop currentRunLoop]runUntilDate:[NSDate dateWithTimeIntervalSinceNow:1]];receipt("virtual",vd.displayID);if(CGMainDisplayID()!=mainBefore){fputs("Unexpected main-display change; ending probe\n",stderr);return 5;}}
 vd=nil;s=nil;d=nil;
 // Exiting process is definitive cleanup even if WindowServer retains internals.
 return 0;
}}
