# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
param(
    [Parameter(Mandatory)] [string] $Work,
    [Parameter(Mandatory)] [string] $Output,
    [Parameter(Mandatory)] [string] $Jdk,
    [Parameter(Mandatory)] [string] $Modules
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$version = $env:PICTURESHOW_VERSION ?? '1.0.0'
$build = $env:PICTURESHOW_BUILD_NUMBER ?? '0'
if ($version -notmatch '^[1-9][0-9]*\.[0-9]+\.[0-9]+$' -or $build -ne '0') {
    throw 'Use a three-part Pictureshow version. The Store reserves the fourth field, which must be zero.'
}
$env:JAVA_HOME = $Jdk
$msvc = Join-Path $env:VCToolsInstallDir 'bin/Hostx64/x64'
$env:PATH = "$Jdk/bin;$msvc;$env:PATH"
& ./gradlew.bat --no-daemon :desktop:spikeRuntime

# Generate native installer and Store artwork from the same source icon.
Add-Type -AssemblyName System.Drawing
$icon = [System.Drawing.Image]::FromFile((Resolve-Path app/src/main/res/mipmap-xxxhdpi/ic_launcher.png))
function Export-SquareIcon([int] $Size, [string] $Path) {
    $bitmap = [System.Drawing.Bitmap]::new($icon, $Size, $Size)
    try { $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png) }
    finally { $bitmap.Dispose() }
}
Export-SquareIcon 192 "$Work/launcher.png"
$png = [IO.File]::ReadAllBytes("$Work/launcher.png")
$ico = [IO.BinaryWriter]::new([IO.File]::Create("$Work/Pictureshow.ico"))
try {
    $ico.Write([byte[]](0, 0, 1, 0, 1, 0, 192, 192, 0, 0, 1, 0, 32, 0))
    $ico.Write([int]$png.Length)
    $ico.Write([int]22)
    $ico.Write($png)
} finally { $ico.Dispose() }

& jpackage --type app-image --name Pictureshow --dest $Work --input desktop/build/spike-runtime/lib `
    --main-jar desktop.jar --main-class app.pictureshow.desktop.PictureshowLauncherKt --app-version $version `
    --vendor 'Legitimate LLC' --add-modules $Modules --icon "$Work/Pictureshow.ico" `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --java-options '-Dcompose.interop.blending=true' `
    --java-options '-Dcompose.accessibility.enable=true' `
    --java-options '-Dspike.mpv.library=$APPDIR/../libmpv-2.dll' `
    --java-options '-Dspike.layer.bridge=$APPDIR/../pictureshow-layer.dll' `
    --java-options '-Dpictureshow.playback.sources=$APPDIR/PlaybackSources'
$image = "$Work/Pictureshow"
if (!(Select-String -Quiet -Pattern '\bjdk\.httpserver\b' "$image/runtime/release")) {
    throw 'The runtime needs jdk.httpserver for the playback proxy.'
}

$header = "$Work/native/prefix/include/mpv/client.h"
$content = (Get-Content $header -Raw).Replace('__declspec(dllexport)', '__declspec(dllimport)')
Set-Content -LiteralPath $header -Value $content
Push-Location $Work
try {
    $layer = (Resolve-Path "$PSScriptRoot/../../src/native/windows_layer.cpp").Path
    & cl /nologo /EHsc /MT /LD /std:c++17 /I native/prefix/include $layer /link d3d11.lib dxgi.lib user32.lib /OUT:pictureshow-layer.dll
} finally { Pop-Location }

$offer = "$image/app/PlaybackSources"
Copy-Item "$Work/PlaybackSources" -Destination $offer -Recurse
Copy-Item "$image/runtime/legal/java.base/LICENSE" "$offer/Temurin-LICENSE"
$python = if ($env:pythonLocation) { "$env:pythonLocation/python.exe" } else { 'python' }
& $python desktop/tools/packaging/audit.py --target $image --evidence $offer --prefix "$Work/native/prefix/bin" `
    --supplemental "$Work/native/ucrt64" --binary "$Work/pictureshow-layer.dll"
& $python desktop/tools/packaging/audit.py --target $image --evidence $offer

# The direct channel uses the standard Windows Installer to register a Start
# menu shortcut and uninstall entry, with no certificate trust step.
$wix = Get-ChildItem "${env:ProgramFiles(x86)}" -Directory -Filter 'WiX Toolset v3*' | Sort-Object Name -Descending | Select-Object -First 1
if ($wix) { $env:PATH = "$($wix.FullName)/bin;$env:PATH" }
& jpackage --type msi --app-image $image --name Pictureshow --app-version $version --vendor 'Legitimate LLC' `
    --dest $Output --win-per-user-install --win-menu --win-menu-group Pictureshow `
    --win-upgrade-uuid 2fcb61b8-69fc-4e9a-a890-9b9764839790
$msi = Get-Item "$Output/Pictureshow-$version.msi"

# Build the Store package from the exact same runtime with the Store-only bridge.
$sdk = Get-ChildItem "${env:ProgramFiles(x86)}/Windows Kits/10/bin" -Directory |
    Where-Object { Test-Path "$($_.FullName)/x64/makeappx.exe" } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$sdk) { throw 'Windows SDK MakeAppx not installed' }
Copy-Item "$Work/pictureshow-store.dll" "$image/pictureshow-store.dll"
$cfg = "$image/app/Pictureshow.cfg"
$config = (Get-Content -LiteralPath $cfg -Raw).Replace('[JavaOptions]', "[JavaOptions]`njava-options=-Dpictureshow.windows.store.library=`$APPDIR/../pictureshow-store.dll")
Set-Content -LiteralPath $cfg -Value $config
[xml] $manifest = Get-Content "$PSScriptRoot/AppxManifest.xml"
$identity = $manifest.Package.Identity
$identity.Version = "$version.0"
if ([bool]$env:PICTURESHOW_STORE_IDENTITY_NAME -ne [bool]$env:PICTURESHOW_STORE_PUBLISHER) {
    throw 'Supply both Store package identity fields from Partner Center.'
}
if ($env:PICTURESHOW_STORE_IDENTITY_NAME) {
    $identity.Name = $env:PICTURESHOW_STORE_IDENTITY_NAME
    $identity.Publisher = $env:PICTURESHOW_STORE_PUBLISHER
}
$manifest.Save("$image/AppxManifest.xml")
New-Item -ItemType Directory "$image/Assets" | Out-Null
Export-SquareIcon 50 "$image/Assets/StoreLogo.png"
Export-SquareIcon 150 "$image/Assets/Square150x150Logo.png"
Export-SquareIcon 44 "$image/Assets/Square44x44Logo.png"
$icon.Dispose()
$msix = "$Output/Pictureshow-$version-x64.msix"
& "$($sdk.FullName)/x64/makeappx.exe" pack /d $image /p $msix /h SHA256 /o

$evidence = New-Item -Force -ItemType Directory "$Output/evidence"
Copy-Item "$offer/library-imports.json","$offer/ffmpeg-runtime-config.json","$offer/mpv-buildoptions.json" $evidence
Copy-Item "$image/runtime/release" "$evidence/java-runtime-modules.txt"
Get-FileHash -Algorithm SHA256 -Path $msi.FullName,$msix,"$Output/temurin-sources.tar.gz" |
    ForEach-Object { "$($_.Hash.ToLowerInvariant())  $(Split-Path $_.Path -Leaf)" } |
    Set-Content "$Output/SHA256SUMS"
