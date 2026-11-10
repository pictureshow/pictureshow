/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 * Numeric capture of the real DWM window/display. Requires Windows 10 1903+.
 * cl /EHsc /std:c++20 windows_capture.cpp /link d3d11.lib dxgi.lib windowsapp.lib user32.lib
 */
#define NOMINMAX
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_6.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <fstream>
#include <filesystem>
#include <iostream>
#include <vector>
#include <thread>
#include <chrono>
using namespace winrt;
using namespace Windows::Graphics::Capture;
using namespace Windows::Graphics::DirectX;
static void displays(bool enable) {
 UINT np=0,nm=0;LONG rc=GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS,&np,&nm);
 if(rc!=ERROR_SUCCESS) {std::cout<<"{\"displayConfigError\":"<<rc<<"}\n";return;}
 std::vector<DISPLAYCONFIG_PATH_INFO> p(np);std::vector<DISPLAYCONFIG_MODE_INFO> m(nm);
 rc=QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS,&np,p.data(),&nm,m.data(),nullptr);
 if(rc!=ERROR_SUCCESS) {std::cout<<"{\"queryDisplayConfigError\":"<<rc<<"}\n";return;}
 for(UINT i=0;i<np;++i){
  DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO info={};info.header.type=DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO;info.header.size=sizeof(info);info.header.adapterId=p[i].targetInfo.adapterId;info.header.id=p[i].targetInfo.id;
  LONG got=DisplayConfigGetDeviceInfo(&info.header);LONG set=-1;
  if(enable&&got==ERROR_SUCCESS&&info.advancedColorSupported&&!info.advancedColorEnabled){DISPLAYCONFIG_SET_ADVANCED_COLOR_STATE s={};s.header=info.header;s.header.type=DISPLAYCONFIG_DEVICE_INFO_SET_ADVANCED_COLOR_STATE;s.header.size=sizeof(s);s.enableAdvancedColor=1;set=DisplayConfigSetDeviceInfo(&s.header);DisplayConfigGetDeviceInfo(&info.header);}
  std::cout<<"{\"target\":"<<info.header.id<<",\"getError\":"<<got<<",\"hdrSupported\":"<<info.advancedColorSupported<<",\"hdrEnabled\":"<<info.advancedColorEnabled<<",\"bitsPerChannel\":"<<info.bitsPerColorChannel<<",\"setError\":"<<set<<"}\n";
 }
}
int wmain(int argc,wchar_t **argv) {
 try {
  init_apartment(apartment_type::multi_threaded);
  bool enable=argc>1&&std::wstring(argv[1])==L"--enable-hdr";
  if(enable||argc<3){displays(enable);return 0;}
  std::wstring mode=argv[1];HWND window=FindWindowW(nullptr,L"Pictureshow HDR video");
  if(!window)throw hresult_error(HRESULT_FROM_WIN32(ERROR_NOT_FOUND),L"Pictureshow HDR video HWND not found");
  auto interop=get_activation_factory<GraphicsCaptureItem,IGraphicsCaptureItemInterop>();GraphicsCaptureItem item{nullptr};
  if(mode==L"display")check_hresult(interop->CreateForMonitor(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),guid_of<GraphicsCaptureItem>(),put_abi(item)));
  else check_hresult(interop->CreateForWindow(window,guid_of<GraphicsCaptureItem>(),put_abi(item)));
  com_ptr<ID3D11Device> device;com_ptr<ID3D11DeviceContext> context;
  // WARP supports the hosted software-rendered test; real devices can use hardware.
  HRESULT hr=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,device.put(),nullptr,context.put());
  if(FAILED(hr))check_hresult(D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,device.put(),nullptr,context.put()));
  auto dxgi=device.as<IDXGIDevice>();com_ptr<IInspectable> inspect;check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(),inspect.put()));auto wd=inspect.as<Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice>();
  auto pool=Direct3D11CaptureFramePool::CreateFreeThreaded(wd,DirectXPixelFormat::R16G16B16A16Float,2,item.Size());auto session=pool.CreateCaptureSession(item);session.IsCursorCaptureEnabled(false);session.StartCapture();
  Direct3D11CaptureFrame frame{nullptr};
  for(int i=0;i<200&&!frame;++i){frame=pool.TryGetNextFrame();if(!frame)std::this_thread::sleep_for(std::chrono::milliseconds(50));}
  if(!frame)throw hresult_error(E_FAIL,L"No WGC frame in 10 seconds");
  auto access=frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();com_ptr<ID3D11Texture2D> texture;check_hresult(access->GetInterface(__uuidof(ID3D11Texture2D),texture.put_void()));
  D3D11_TEXTURE2D_DESC desc={};texture->GetDesc(&desc);if(desc.Format!=DXGI_FORMAT_R16G16B16A16_FLOAT)throw hresult_error(E_FAIL,L"Capture lost FP16 format");
  auto size=frame.ContentSize();desc.Usage=D3D11_USAGE_STAGING;desc.BindFlags=0;desc.MiscFlags=0;desc.CPUAccessFlags=D3D11_CPU_ACCESS_READ;com_ptr<ID3D11Texture2D> staging;check_hresult(device->CreateTexture2D(&desc,nullptr,staging.put()));context->CopyResource(staging.get(),texture.get());D3D11_MAPPED_SUBRESOURCE map={};check_hresult(context->Map(staging.get(),0,D3D11_MAP_READ,0,&map));
  std::ofstream raw(std::filesystem::path(std::wstring(argv[2])+L".rgba16f"),std::ios::binary);for(int y=0;y<size.Height;++y)raw.write((char*)map.pData+y*map.RowPitch,size.Width*8);context->Unmap(staging.get(),0);raw.close();
  RECT r={};GetWindowRect(window,&r);MONITORINFO mi={sizeof(mi)};GetMonitorInfo(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&mi);
  int x=mode==L"display"?r.left-mi.rcMonitor.left:0,y=mode==L"display"?r.top-mi.rcMonitor.top:0;
  std::ofstream meta(std::filesystem::path(std::wstring(argv[2])+L".json"));meta<<"{\"captureAPI\":\"Windows.Graphics.Capture\",\"mode\":\""<<(mode==L"display"?"display":"window")<<"\",\"format\":\"RGBA16Float\",\"encoding\":\"linear\",\"primaries\":\"bt709\",\"referenceWhiteNits\":80,\"width\":"<<size.Width<<",\"height\":"<<size.Height<<",\"videoBounds\":["<<x<<","<<y<<","<<r.right-r.left<<","<<r.bottom-r.top<<"]}\n";
  frame.Close();session.Close();pool.Close();displays(false);return 0;
 }catch(hresult_error const&e){std::wcerr<<L"HRESULT "<<std::hex<<e.code()<<L": "<<e.message().c_str()<<L"\n";return 2;}
}
