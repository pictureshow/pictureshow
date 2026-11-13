/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Opt-in diagnostics. No file I/O in the per-frame callbacks.
 */
#pragma once
#include <memory>
#include <vector>
#include <cstdio>
#include <cstdint>
#include <mutex>
#include <condition_variable>
#include <string>
#include <chrono>
#import <QuartzCore/QuartzCore.h>

struct MacFrameTrace {
    enum Kind { Render, Fence, Submit, Completed, Presented, Wake, Visibility };
    struct Event { Kind kind; uint64_t frame; double host,a,b,c,d; };
    std::mutex mutex;
    std::condition_variable wake;
    std::vector<Event> events;
    std::string path;
    unsigned pending=0;
    bool overflow=false;
    explicit MacFrameTrace(const char *p):path(p) { events.reserve(100000); }
    void add(Kind kind,uint64_t frame,double a=0,double b=0,double c=0,double d=0) {
        std::lock_guard<std::mutex> lock(mutex);
        if(events.size()<200000) events.push_back({kind,frame,CACurrentMediaTime(),a,b,c,d});
        else overflow=true;
    }
    void expectPresentation() { std::lock_guard<std::mutex> lock(mutex); pending++; }
    void presented(uint64_t frame,double actual,double drawable) {
        std::lock_guard<std::mutex> lock(mutex);
        if(events.size()<200000) events.push_back({Presented,frame,CACurrentMediaTime(),actual,drawable,0,0});
        else overflow=true;
        pending--; wake.notify_all();
    }
    // A drawable can be skipped (presentedTime=0), or its handler can be absent
    // at teardown. Record both explicitly, without guessing a display time.
    void write() {
        std::unique_lock<std::mutex> lock(mutex);
        wake.wait_for(lock,std::chrono::seconds(2),[this]{return pending==0;});
        FILE *f=fopen(path.c_str(),"w"); if(!f) return;
        fprintf(f,"{\"event\":\"traceStatus\",\"overflow\":%s,\"pendingPresentedHandlers\":%u}\n",overflow?"true":"false",pending);
        const char *names[]={"render","fence","submit","completed","presented","wake","visibility"};
        const char *fields[]={"\"begin\":%.9f,\"end\":%.9f,\"target\":%.9f,\"qos\":%.0f",
            "\"issued\":%.9f,\"ready\":%.9f,\"slot\":%.0f,\"unused\":%.0f",
            "\"target\":%.9f,\"drawableBegin\":%.9f,\"drawableEnd\":%.9f,\"drawableID\":%.0f",
            "\"gpuStart\":%.9f,\"gpuEnd\":%.9f,\"status\":%.0f,\"unused\":%.0f",
            "\"actual\":%.9f,\"drawableID\":%.0f,\"unused1\":%.0f,\"unused2\":%.0f",
            "\"unused1\":%.0f,\"unused2\":%.0f,\"unused3\":%.0f,\"unused4\":%.0f",
            "\"occlusionVisible\":%.0f,\"applicationActive\":%.0f,\"frontmostPID\":%.0f,\"windowNumber\":%.0f"};
        for(const auto &e:events) {
            fprintf(f,"{\"event\":\"%s\",\"frame\":%llu,\"host\":%.9f,",names[e.kind],(unsigned long long)e.frame,e.host);
            fprintf(f,fields[e.kind],e.a,e.b,e.c,e.d); fputs("}\n",f);
        }
        fclose(f);
    }
};
