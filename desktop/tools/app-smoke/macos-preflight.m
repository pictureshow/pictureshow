/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * No windows, media, credentials or playback. Observe the hosted native loader
 * and graphics capabilities before the real app exercises the same adapter.
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <OpenGL/CGLRenderers.h>
#import <IOSurface/IOSurface.h>
#include <dlfcn.h>

static NSString *loaderFailure(const char *error) {
    if (!error) return @"none";
    NSString *message = [[NSString stringWithUTF8String:error] lowercaseString];
    for (NSString *reason in @[@"incompatible architecture", @"symbol not found",
                               @"library not loaded", @"code signature", @"image not found"])
        if ([message containsString:reason]) return reason;
    return @"unclassified loader failure"; // Keep machine paths out of receipts.
}

static NSDictionary *interopProbe(CGLContextObj context) {
    const unsigned width=64, height=64, rowBytes=width*8;
    NSMutableDictionary *result = [NSMutableDictionary dictionary];
    NSDictionary *properties = @{(id)kIOSurfaceWidth:@(width), (id)kIOSurfaceHeight:@(height),
        (id)kIOSurfaceBytesPerElement:@8, (id)kIOSurfaceBytesPerRow:@(rowBytes),
        (id)kIOSurfacePixelFormat:@((uint32_t)'RGhA')};
    IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef)properties);
    result[@"surfaceCreated"] = @(surface != NULL);
    if (!surface) { result[@"pass"] = @NO; return result; }
    GLuint texture=0, framebuffer=0;
    glGenTextures(1,&texture); glBindTexture(GL_TEXTURE_RECTANGLE,texture);
    CGLError bindError=CGLTexImageIOSurface2D(context,GL_TEXTURE_RECTANGLE,GL_RGBA16F,
        width,height,GL_RGBA,GL_HALF_FLOAT,surface,0);
    result[@"cglBindError"] = @(bindError);
    BOOL passed=NO;
    if (bindError==kCGLNoError) {
        glGenFramebuffers(1,&framebuffer); glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,texture,0);
        GLenum status=glCheckFramebufferStatus(GL_FRAMEBUFFER); result[@"framebufferStatus"]=@(status);
        if (status==GL_FRAMEBUFFER_COMPLETE) {
            // Diagnostic only: one synchronous known-color handoff and readback,
            // independent of mpv, windows, media and the production frame loop.
            const GLfloat values[4]={.25f,.5f,2.f,1.f};
            glClearBufferfv(GL_COLOR,0,values); glFinish(); result[@"glError"]=@(glGetError());
            id<MTLDevice> device=MTLCreateSystemDefaultDevice();
            MTLTextureDescriptor *description=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:width height:height mipmapped:NO];
            description.usage=MTLTextureUsageShaderRead; description.storageMode=MTLStorageModeShared;
            id<MTLTexture> shared=[device newTextureWithDescriptor:description iosurface:surface plane:0];
            result[@"metalSharedTextureCreated"]=@(shared!=nil);
            id<MTLCommandQueue> queue=[device newCommandQueue];
            id<MTLBuffer> buffer=[device newBufferWithLength:rowBytes*height options:MTLResourceStorageModeShared];
            if (shared && queue && buffer) {
                id<MTLCommandBuffer> command=[queue commandBuffer];
                id<MTLBlitCommandEncoder> blit=[command blitCommandEncoder];
                [blit copyFromTexture:shared sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                    sourceSize:MTLSizeMake(width,height,1) toBuffer:buffer destinationOffset:0
                    destinationBytesPerRow:rowBytes destinationBytesPerImage:rowBytes*height];
                [blit endEncoding]; [command commit]; [command waitUntilCompleted];
                result[@"metalCommandStatus"]=@(command.status);
                NSMutableArray *observed=[NSMutableArray array]; passed=command.status==MTLCommandBufferStatusCompleted;
                const uint16_t expected[4]={0x3400,0x3800,0x4000,0x3c00};
                const unsigned offsets[]={0,width-1,(height-1)*width,(height-1)*width+width-1,(height/2)*width+width/2};
                for (unsigned n=0;n<5;n++) {
                    const uint16_t *pixel=(const uint16_t*)buffer.contents+offsets[n]*4;
                    NSMutableArray *sample=[NSMutableArray array];
                    for (unsigned channel=0;channel<4;channel++) { [sample addObject:@(pixel[channel])]; if (pixel[channel]!=expected[channel]) passed=NO; }
                    [observed addObject:sample];
                }
                result[@"observedHalfFloatSamples"]=observed;
                result[@"expectedHalfFloat"]=@[@0x3400,@0x3800,@0x4000,@0x3c00];
            }
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER,0); glBindTexture(GL_TEXTURE_RECTANGLE,0);
    if (framebuffer) glDeleteFramebuffers(1,&framebuffer);
    if (texture) glDeleteTextures(1,&texture);
    CFRelease(surface); result[@"pass"]=@(passed); return result;
}

static NSDictionary *contextProbe(BOOL core32) {
    CGLPixelFormatAttribute attributes[6] = {0};
    BOOL forceSoftware = [NSProcessInfo.processInfo.environment[@"SPIKE_SOFTWARE_GPU"] isEqualToString:@"1"];
    if (core32) {
        attributes[0] = kCGLPFAOpenGLProfile;
        attributes[1] = (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core;
        if (forceSoftware) {
            attributes[2] = kCGLPFARendererID;
            attributes[3] = (CGLPixelFormatAttribute)kCGLRendererGenericFloatID;
        }
    }
    CGLPixelFormatObj format = NULL; GLint count = 0;
    CGLError formatError = CGLChoosePixelFormat(attributes, &format, &count);
    CGLContextObj context = NULL;
    CGLError contextError = format ? CGLCreateContext(format, NULL, &context) : kCGLBadPixelFormat;
    NSMutableDictionary *result = [@{@"formatError":@(formatError), @"formatCount":@(count),
                                     @"genericSoftwareRendererRequested":@(core32 && forceSoftware),
                                     @"contextError":@(contextError)} mutableCopy];
    if (context) {
        CGLSetCurrentContext(context);
        const GLubyte *renderer = glGetString(GL_RENDERER), *version = glGetString(GL_VERSION);
        result[@"renderer"] = renderer ? [NSString stringWithUTF8String:(const char *)renderer] : @"unavailable";
        result[@"version"] = version ? [NSString stringWithUTF8String:(const char *)version] : @"unavailable";
        if (core32) result[@"rgba16FloatIOSurfaceToMetal"]=interopProbe(context);
        CGLSetCurrentContext(NULL); CGLDestroyContext(context);
    }
    if (format) CGLDestroyPixelFormat(format);
    return result;
}

int main(int argc, const char **argv) { @autoreleasepool {
    if (argc > 2) return 2;
    NSMutableDictionary *receipt = [NSMutableDictionary dictionary];
#if defined(__arm64__)
    receipt[@"architecture"] = @"arm64";
#elif defined(__x86_64__)
    receipt[@"architecture"] = @"x86_64";
#endif
    void *adapter = argc == 2 ? dlopen(argv[1], RTLD_NOW | RTLD_LOCAL) : NULL;
    receipt[@"adapterProbeRequested"] = @(argc == 2);
    receipt[@"adapterLoaded"] = argc == 2 ? (id)@(adapter != NULL) : (id)NSNull.null;
    receipt[@"loaderFailure"] = argc == 2 ? loaderFailure(adapter ? NULL : dlerror()) : @"not requested";
    NSMutableArray *missing = [NSMutableArray array];
    if (adapter) {
        for (NSString *symbol in @[@"ps_find_window", @"ps_edr_reference_white", @"ps_create",
                                   @"ps_sync", @"ps_frame", @"ps_finish", @"ps_destroy"])
            if (!dlsym(adapter, symbol.UTF8String)) [missing addObject:symbol];
    }
    receipt[@"missingAdapterSymbols"] = missing;
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    receipt[@"metalDevice"] = @(device != nil);
    receipt[@"metalDeviceName"] = device.name ?: @"unavailable";
    CGLPixelFormatAttribute attributes[] = {kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core, kCGLPFAAccelerated, 0};
    CGLPixelFormatObj pixelFormat = NULL; GLint count = 0;
    CGLError formatError = CGLChoosePixelFormat(attributes, &pixelFormat, &count);
    receipt[@"acceleratedCore32FormatError"] = @(formatError);
    receipt[@"acceleratedCore32FormatCount"] = @(count);
    CGLContextObj context = NULL;
    CGLError contextError = pixelFormat ? CGLCreateContext(pixelFormat, NULL, &context) : kCGLBadPixelFormat;
    receipt[@"acceleratedCore32ContextError"] = @(contextError);
    if (context) {
        CGLSetCurrentContext(context);
        const GLubyte *renderer = glGetString(GL_RENDERER);
        receipt[@"glRenderer"] = renderer ? [NSString stringWithUTF8String:(const char *)renderer] : @"unavailable";
        CGLSetCurrentContext(NULL); CGLDestroyContext(context);
    }
    if (pixelFormat) CGLDestroyPixelFormat(pixelFormat);
    receipt[@"core32WithoutAccelerationRequirement"] = contextProbe(YES);
    receipt[@"legacyWithoutAccelerationRequirement"] = contextProbe(NO);
    if (adapter) dlclose(adapter);
    NSData *json = [NSJSONSerialization dataWithJSONObject:receipt options:NSJSONWritingPrettyPrinted error:nil];
    fwrite(json.bytes, 1, json.length, stdout); puts("");
    return 0;
} }
