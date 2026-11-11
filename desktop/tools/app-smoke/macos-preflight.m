/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * No windows, media, credentials or playback. Observe the hosted native loader
 * and graphics capabilities before the real app exercises the same adapter.
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#include <dlfcn.h>

static NSString *loaderFailure(const char *error) {
    if (!error) return @"none";
    NSString *message = [[NSString stringWithUTF8String:error] lowercaseString];
    for (NSString *reason in @[@"incompatible architecture", @"symbol not found",
                               @"library not loaded", @"code signature", @"image not found"])
        if ([message containsString:reason]) return reason;
    return @"unclassified loader failure"; // Keep machine paths out of receipts.
}

static NSDictionary *contextProbe(BOOL core32) {
    CGLPixelFormatAttribute attributes[4] = {0};
    if (core32) {
        attributes[0] = kCGLPFAOpenGLProfile;
        attributes[1] = (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core;
    }
    CGLPixelFormatObj format = NULL; GLint count = 0;
    CGLError formatError = CGLChoosePixelFormat(attributes, &format, &count);
    CGLContextObj context = NULL;
    CGLError contextError = format ? CGLCreateContext(format, NULL, &context) : kCGLBadPixelFormat;
    NSMutableDictionary *result = [@{@"formatError":@(formatError), @"formatCount":@(count),
                                     @"contextError":@(contextError)} mutableCopy];
    if (context) {
        CGLSetCurrentContext(context);
        const GLubyte *renderer = glGetString(GL_RENDERER), *version = glGetString(GL_VERSION);
        result[@"renderer"] = renderer ? [NSString stringWithUTF8String:(const char *)renderer] : @"unavailable";
        result[@"version"] = version ? [NSString stringWithUTF8String:(const char *)version] : @"unavailable";
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
