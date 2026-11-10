// SPDX-License-Identifier: MPL-2.0
// Independent DXGI API-state probe; explicitly NOT the Qt presenting swapchain.
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_6.h>
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QFile>
#include <cstdio>
int main(int argc,char **argv){QCoreApplication app(argc,argv);QJsonArray results;bool scrgbPassed=false;ID3D11Device*d=nullptr;ID3D11DeviceContext*c=nullptr;
 HRESULT hr=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,&d,nullptr,&c);if(FAILED(hr))return 2;
 IDXGIDevice*dx=nullptr;IDXGIAdapter*a=nullptr;IDXGIFactory2*f=nullptr;d->QueryInterface(IID_PPV_ARGS(&dx));dx->GetAdapter(&a);a->GetParent(IID_PPV_ARGS(&f));
 for(bool hdr10:{false,true}){DXGI_SWAP_CHAIN_DESC1 desc{};desc.Width=640;desc.Height=360;desc.Format=hdr10?DXGI_FORMAT_R10G10B10A2_UNORM:DXGI_FORMAT_R16G16B16A16_FLOAT;desc.SampleDesc.Count=1;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;desc.AlphaMode=DXGI_ALPHA_MODE_IGNORE;
 IDXGISwapChain1*s=nullptr;hr=f->CreateSwapChainForComposition(d,&desc,nullptr,&s);QJsonObject r{{"probe","independent composition chain; not Qt presentation"},{"mode",hdr10?"HDR10":"scRGB"},{"createHresult",qint64(hr)}};
 if(SUCCEEDED(hr)){IDXGISwapChain3*s3=nullptr;IDXGISwapChain4*s4=nullptr;s->QueryInterface(IID_PPV_ARGS(&s3));s->QueryInterface(IID_PPV_ARGS(&s4));DXGI_SWAP_CHAIN_DESC1 actual{};s->GetDesc1(&actual);r["actualFormat"]=int(actual.Format);auto space=hdr10?DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020:DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709;UINT support=0;if(s3){auto check=s3->CheckColorSpaceSupport(space,&support);r["checkHresult"]=qint64(check);r["support"]=int(support);r["setColorSpaceHresult"]=qint64(s3->SetColorSpace1(space));r["requestedColorSpace"]=int(space);s3->Release();}
 if(s4){DXGI_HDR_METADATA_HDR10 meta{};meta.RedPrimary[0]=34000;meta.RedPrimary[1]=16000;meta.GreenPrimary[0]=13250;meta.GreenPrimary[1]=34500;meta.BluePrimary[0]=7500;meta.BluePrimary[1]=3000;meta.WhitePoint[0]=15635;meta.WhitePoint[1]=16450;meta.MaxMasteringLuminance=1000*10000;meta.MinMasteringLuminance=1;meta.MaxContentLightLevel=1000;meta.MaxFrameAverageLightLevel=400;
 r["metadataType"]=hdr10?"HDR10":"NONE (scRGB carries absolute linear light without HDR10 static metadata)";r["setMetadataHresult"]=qint64(s4->SetHDRMetaData(hdr10?DXGI_HDR_METADATA_TYPE_HDR10:DXGI_HDR_METADATA_TYPE_NONE,hdr10?sizeof(meta):0,hdr10?&meta:nullptr));s4->Release();}s->Release();}
 if(!hdr10)scrgbPassed=r["actualFormat"].toInt()==int(DXGI_FORMAT_R16G16B16A16_FLOAT)&&r.contains("setColorSpaceHresult")&&r["setColorSpaceHresult"].toDouble()!=qint64(E_FAIL)&&r["setColorSpaceHresult"].toDouble()>=0&&r.contains("setMetadataHresult")&&r["setMetadataHresult"].toDouble()>=0;results.append(r);}
 f->Release();a->Release();dx->Release();c->Release();d->Release();auto data=QJsonDocument(QJsonObject{{"driver","WARP"},{"presented",false},{"results",results}}).toJson();std::fwrite(data.constData(),1,size_t(data.size()),stdout);return scrgbPassed?0:2;}
