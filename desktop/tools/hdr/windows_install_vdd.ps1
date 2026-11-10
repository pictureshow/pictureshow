# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Disposable CI runner only. Signed open-source VDD; no test-signing/reboot.
$ErrorActionPreference = 'Stop'
$dest = Join-Path $env:RUNNER_TEMP 'pictureshow-hdr-vdd'
New-Item -ItemType Directory -Force $dest | Out-Null
function Fetch-Checked($url, $name, $digest) {
  $path = Join-Path $dest $name
  Invoke-WebRequest $url -OutFile $path
  if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -ne $digest) { throw "$name digest mismatch" }
  Expand-Archive $path -DestinationPath $dest -Force
}
Fetch-Checked 'https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/download/25.7.23/VirtualDisplayDriver-x86.Driver.Only.zip' 'vdd.zip' 'e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a'
Fetch-Checked 'https://github.com/nefarius/nefcon/releases/download/v1.14.0/nefcon_v1.14.0.zip' 'nefcon.zip' 'a15557da24a9efca203158de3b43b0eaf982db231f0194031f1ed428bc13e669'
New-Item -ItemType Directory -Force C:\VirtualDisplayDriver | Out-Null
$settings = Join-Path $dest 'VirtualDisplayDriver/vdd_settings.xml'
(Get-Content $settings -Raw).Replace('<logging>false</logging>', '<logging>true</logging>') | Set-Content C:\VirtualDisplayDriver\vdd_settings.xml
# Root device creation and signed package install, the project's documented silent route.
& "$dest/x64/nefconw.exe" install "$dest/VirtualDisplayDriver/MttVDD.inf" 'Root\MttVDD'
Write-Host "nefcon exit $LASTEXITCODE"
Start-Sleep -Seconds 8
Get-PnpDevice -Class Display | Select-Object Status,FriendlyName,Problem | ConvertTo-Json
Get-CimInstance Win32_VideoController | Select-Object Name,VideoProcessor,DriverVersion,CurrentHorizontalResolution,CurrentVerticalResolution | ConvertTo-Json
