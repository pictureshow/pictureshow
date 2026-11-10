/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Small D3D11 render/readback adapter, using mpv's ISC-licensed public API headers.
 */
#include <windows.h>
#include <d3d11.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_d3d11.h>
#include <cstdlib>
#include <cstring>
struct Surface {
    HMODULE library = nullptr;
    ID3D11Device *device = nullptr;
    ID3D11DeviceContext *context = nullptr;
    ID3D11Texture2D *target = nullptr, *staging = nullptr;
    mpv_render_context *render = nullptr;
    decltype(&mpv_render_context_create) create = nullptr;
    decltype(&mpv_render_context_render) draw = nullptr;
    decltype(&mpv_render_context_free) destroy = nullptr;
    int width = 0, height = 0;
};
extern "C" __declspec(dllexport) void pictureshow_d3d_close(Surface *s) {
    if (!s) return;
    if (s->render) s->destroy(s->render);
    if (s->staging) s->staging->Release();
    if (s->target) s->target->Release();
    if (s->context) s->context->Release();
    if (s->device) s->device->Release();
    if (s->library) FreeLibrary(s->library);
    delete s;
}
extern "C" __declspec(dllexport) Surface *pictureshow_d3d_create(mpv_handle *mpv, int software) {
    auto *s = new Surface();
    s->library = LoadLibraryA(std::getenv("MPV_LIBRARY"));
    if (!s->library) { delete s; return nullptr; }
    s->create = (decltype(s->create))GetProcAddress(s->library, "mpv_render_context_create");
    s->draw = (decltype(s->draw))GetProcAddress(s->library, "mpv_render_context_render");
    s->destroy = (decltype(s->destroy))GetProcAddress(s->library, "mpv_render_context_free");
    if (!s->create || !s->draw || !s->destroy) { pictureshow_d3d_close(s); return nullptr; }
    auto driver = software ? D3D_DRIVER_TYPE_WARP : D3D_DRIVER_TYPE_HARDWARE;
    HRESULT hr = D3D11CreateDevice(nullptr, driver, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        nullptr, 0, D3D11_SDK_VERSION, &s->device, nullptr, &s->context);
    if (FAILED(hr)) { pictureshow_d3d_close(s); return nullptr; }
    mpv_d3d11_init_params init = {s->device};
    mpv_render_param params[] = {{MPV_RENDER_PARAM_API_TYPE, (void*)MPV_RENDER_API_TYPE_D3D11},
        {MPV_RENDER_PARAM_D3D11_INIT_PARAMS, &init}, {MPV_RENDER_PARAM_INVALID, nullptr}};
    if (s->create(&s->render, mpv, params) < 0) { pictureshow_d3d_close(s); return nullptr; }
    return s;
}
extern "C" __declspec(dllexport) int pictureshow_d3d_frame(Surface *s, int width, int height, unsigned char *rgba) {
    if (width != s->width || height != s->height) {
        if (s->target) { s->target->Release(); s->target = nullptr; }
        if (s->staging) { s->staging->Release(); s->staging = nullptr; }
        D3D11_TEXTURE2D_DESC desc = {};
        desc.Width = width; desc.Height = height; desc.MipLevels = 1; desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT; desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        if (FAILED(s->device->CreateTexture2D(&desc, nullptr, &s->target))) return -1;
        desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        if (FAILED(s->device->CreateTexture2D(&desc, nullptr, &s->staging))) return -2;
        s->width = width; s->height = height;
    }
    mpv_d3d11_fbo fbo = {s->target};
    mpv_render_param params[] = {{MPV_RENDER_PARAM_D3D11_FBO, &fbo}, {MPV_RENDER_PARAM_INVALID, nullptr}};
    int status = s->draw(s->render, params);
    if (status < 0) return status;
    s->context->CopyResource(s->staging, s->target);
    D3D11_MAPPED_SUBRESOURCE map = {};
    if (FAILED(s->context->Map(s->staging, 0, D3D11_MAP_READ, 0, &map))) return -3;
    for (int y = 0; y < height; ++y) std::memcpy(rgba + y * width * 4, (char*)map.pData + y * map.RowPitch, width * 4);
    s->context->Unmap(s->staging, 0);
    return 0;
}
