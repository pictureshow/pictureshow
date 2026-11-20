# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
param([Parameter(Mandatory)] [string] $Work)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$msvc = Join-Path $env:VCToolsInstallDir 'bin/Hostx64/x64'
$env:PATH = "$msvc;$env:PATH"
$store = (Resolve-Path "$PSScriptRoot/StoreBridge.cpp").Path
Push-Location $Work
try {
    & cl /nologo /EHsc /MT /LD /std:c++20 $store /link windowsapp.lib ole32.lib user32.lib /OUT:pictureshow-store.dll
} finally { Pop-Location }
