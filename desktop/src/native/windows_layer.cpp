/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * libmpv D3D11 -> FP16 scRGB flip swapchain. No staging texture, Map or CPU frame copy.
 */
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_6.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_d3d11.h>
#include <string>
#include <sstream>
#include <cstdlib>
#include <atomic>
struct Layer {
    HWND overlay=nullptr, video=nullptr;
    HMODULE lib=nullptr;
    ID3D11Device *device=nullptr;
    ID3D11DeviceContext *context=nullptr;
    IDXGISwapChain4 *swap=nullptr;
    mpv_render_context *render=nullptr;
    decltype(&mpv_render_context_create) create;
    decltype(&mpv_render_context_render) draw;
    decltype(&mpv_render_context_free) destroy;
    decltype(&mpv_render_context_report_swap) report;
    int w=0,h=0; std::atomic<unsigned long> frames{0};
    UINT support=0; HRESULT color=E_FAIL,metadata=E_FAIL;
    bool warp=false;
    std::string receipt;
};
#define API extern "C" __declspec(dllexport)
API void ps_sync(Layer *s) {
    RECT r;GetWindowRect(s->overlay,&r);
    SetWindowPos(s->video,s->overlay,r.left,r.top,r.right-r.left,r.bottom-r.top,SWP_NOACTIVATE|SWP_SHOWWINDOW);
}
API void ps_destroy(Layer *s) {
    if(!s)return;
    if(s->render)s->destroy(s->render);
    SetWindowLongPtr(s->overlay,GWLP_HWNDPARENT,0);
    if(s->swap)s->swap->Release();
    if(s->context)s->context->Release();
    if(s->device)s->device->Release();
    if(s->video)DestroyWindow(s->video);
    if(s->lib)FreeLibrary(s->lib);
    delete s;
}
API Layer *ps_create(void *window,void *mpv) {
    Layer *s=new Layer(); s->overlay=(HWND)window;
    s->lib=LoadLibraryA(std::getenv("MPV_LIBRARY"));
    if(!s->lib){delete s;return nullptr;}
    s->create=(decltype(s->create))GetProcAddress(s->lib,"mpv_render_context_create");
    s->draw=(decltype(s->draw))GetProcAddress(s->lib,"mpv_render_context_render");
    s->destroy=(decltype(s->destroy))GetProcAddress(s->lib,"mpv_render_context_free");
    s->report=(decltype(s->report))GetProcAddress(s->lib,"mpv_render_context_report_swap");
    s->warp=std::getenv("SPIKE_SOFTWARE_GPU")!=nullptr;
    HRESULT hr=D3D11CreateDevice(nullptr,s->warp?D3D_DRIVER_TYPE_WARP:D3D_DRIVER_TYPE_HARDWARE,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,&s->device,nullptr,&s->context);
    if(FAILED(hr)){ps_destroy(s);return nullptr;}
    WNDCLASSW wc={};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandle(nullptr);wc.lpszClassName=L"PictureshowHDRVideo";RegisterClassW(&wc);
    s->video=CreateWindowExW(WS_EX_NOACTIVATE|WS_EX_TOOLWINDOW,wc.lpszClassName,L"Pictureshow HDR video",WS_POPUP,0,0,960,640,nullptr,nullptr,wc.hInstance,nullptr);
    SetWindowLongPtr(s->overlay,GWLP_HWNDPARENT,(LONG_PTR)s->video);
    ps_sync(s);
    IDXGIDevice *dxgi;IDXGIAdapter *adapter;IDXGIFactory2 *factory;
    s->device->QueryInterface(__uuidof(IDXGIDevice),(void**)&dxgi);dxgi->GetAdapter(&adapter);adapter->GetParent(__uuidof(IDXGIFactory2),(void**)&factory);
    DXGI_SWAP_CHAIN_DESC1 desc={};desc.Width=960;desc.Height=640;desc.Format=DXGI_FORMAT_R16G16B16A16_FLOAT;desc.SampleDesc.Count=1;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_DISCARD;desc.AlphaMode=DXGI_ALPHA_MODE_IGNORE;
    IDXGISwapChain1 *base=nullptr;
    hr=factory->CreateSwapChainForHwnd(s->device,s->video,&desc,nullptr,nullptr,&base);
    factory->Release();adapter->Release();dxgi->Release();
    if(FAILED(hr)){ps_destroy(s);return nullptr;}
    base->QueryInterface(__uuidof(IDXGISwapChain4),(void**)&s->swap);base->Release();
    if(!s->swap){ps_destroy(s);return nullptr;}
    s->swap->CheckColorSpaceSupport(DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709,&s->support);
    s->color=s->swap->SetColorSpace1(DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709);
    // scRGB carries linear values and has no HDR10 static metadata. Explicitly clear it.
    s->metadata=s->swap->SetHDRMetaData(DXGI_HDR_METADATA_TYPE_NONE,0,nullptr);
    if(FAILED(s->color)||FAILED(s->metadata)){ps_destroy(s);return nullptr;}
    mpv_d3d11_init_params init={s->device};
    mpv_render_param p[]={{MPV_RENDER_PARAM_API_TYPE,(void*)MPV_RENDER_API_TYPE_D3D11},{MPV_RENDER_PARAM_D3D11_INIT_PARAMS,&init},{MPV_RENDER_PARAM_INVALID,nullptr}};
    if(s->create(&s->render,(mpv_handle*)mpv,p)<0){ps_destroy(s);return nullptr;}
    s->w=960;s->h=640;return s;
}
API int ps_frame(Layer *s,int w,int h) {
    MSG msg;while(PeekMessage(&msg,nullptr,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessage(&msg);}
    if(w!=s->w||h!=s->h){s->context->ClearState();HRESULT hr=s->swap->ResizeBuffers(0,w,h,DXGI_FORMAT_UNKNOWN,0);if(FAILED(hr))return -1;s->w=w;s->h=h;}
    ID3D11Texture2D *target=nullptr;if(FAILED(s->swap->GetBuffer(0,__uuidof(ID3D11Texture2D),(void**)&target)))return -2;
    mpv_d3d11_fbo fbo={target};mpv_render_param p[]={{MPV_RENDER_PARAM_D3D11_FBO,&fbo},{MPV_RENDER_PARAM_INVALID,nullptr}};
    int rc=s->draw(s->render,p);target->Release();if(rc<0)return rc;
    if(FAILED(s->swap->Present(1,0)))return -3;
    s->report(s->render);s->frames++;return 0;
}
API const char *ps_receipt(Layer *s) {
    RECT r,vr;GetWindowRect(s->overlay,&r);GetWindowRect(s->video,&vr);
    MONITORINFO mi={sizeof(mi)};GetMonitorInfo(MonitorFromWindow(s->overlay,MONITOR_DEFAULTTONEAREST),&mi);
    IDXGIOutput *output=nullptr;DXGI_OUTPUT_DESC1 od={};
    if(SUCCEEDED(s->swap->GetContainingOutput(&output))){IDXGIOutput6 *o6=nullptr;if(SUCCEEDED(output->QueryInterface(__uuidof(IDXGIOutput6),(void**)&o6))){o6->GetDesc1(&od);o6->Release();}output->Release();}
    auto rect=[](RECT a){std::ostringstream t;t<<"["<<a.left<<","<<a.top<<","<<a.right<<","<<a.bottom<<"]";return t.str();};
    std::ostringstream o;o<<"{\"format\":\"R16G16B16A16_FLOAT\",\"colorSpace\":\"RGB_FULL_G10_NONE_P709\",\"SetColorSpace1HRESULT\":"<<s->color<<",\"CheckColorSpaceSupport\":"<<s->support<<",\"metadataType\":\"NONE (scRGB)\",\"SetHDRMetaDataHRESULT\":"<<s->metadata<<",\"warp\":"<<(s->warp?"true":"false")<<",\"outputColorSpace\":"<<od.ColorSpace<<",\"outputMaxLuminance\":"<<od.MaxLuminance<<",\"windowBounds\":"<<rect(r)<<",\"videoBounds\":"<<rect(vr)<<",\"monitorBounds\":"<<rect(mi.rcMonitor)<<",\"borderless\":"<<((GetWindowLongPtr(s->overlay,GWL_STYLE)&WS_CAPTION)?"false":"true")<<",\"submittedFrames\":"<<s->frames.load()<<",\"perFrameCPUReadbacks\":0}";
    s->receipt=o.str();return s->receipt.c_str();
}
API void ps_fullscreen(Layer *,int) {}
