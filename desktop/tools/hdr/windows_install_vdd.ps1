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
# Trust the signed package publisher on this disposable runner, as upstream's
# documented silent installer does. Do not weaken driver-signing enforcement.
$certificates = [System.Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
$certificates.Import([System.IO.File]::ReadAllBytes("$dest/VirtualDisplayDriver/mttvdd.cat"))
foreach ($cert in $certificates) {
  $file = Join-Path $dest "$($cert.Thumbprint).cer"
  [System.IO.File]::WriteAllBytes($file, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
  Import-Certificate -FilePath $file -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' | Out-Null
}
# Console variant waits synchronously and retains exact installation result.
& "$dest/x64/nefconc.exe" install "$dest/VirtualDisplayDriver/MttVDD.inf" 'Root\MttVDD'
$installExit = $LASTEXITCODE
Write-Host "nefcon exit $installExit"
if ($installExit -ne 0) { Write-Host "VDD not qualified: driver install exit $installExit (3010 means reboot required)." }
Start-Sleep -Seconds 8
Get-PnpDevice -Class Display | Select-Object Status,FriendlyName,InstanceId,Problem | ConvertTo-Json
Get-Content C:\Windows\INF\setupapi.dev.log -Tail 150
Get-CimInstance Win32_VideoController | Select-Object Name,VideoProcessor,DriverVersion,CurrentHorizontalResolution,CurrentVerticalResolution | ConvertTo-Json
