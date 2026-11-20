# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
param([string] $Output = 'desktop/build/windows-package')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$Output = (Resolve-Path $Output).Path
$evidence = "$Output/evidence"
$msix = Get-ChildItem $Output -Filter '*.msix' | Select-Object -First 1
$msi = Get-ChildItem $Output -Filter '*.msi' | Select-Object -First 1
if (!$msix -or !$msi) { throw 'Build both Windows installers first.' }
$archive = [System.IO.Compression.ZipFile]::OpenRead($msix.FullName)
try {
    if ($archive.GetEntry('AppxSignature.p7x')) { throw 'Expected an unsigned MSIX output' }
} finally { $archive.Dispose() }
if ((Get-AuthenticodeSignature $msi.FullName).Status -ne 'NotSigned') {
    throw 'Expected an unsigned MSI output'
}
$temporary = New-Item -ItemType Directory (Join-Path $env:RUNNER_TEMP ('pictureshow-packages-' + [guid]::NewGuid()))
$sdk = Get-ChildItem "${env:ProgramFiles(x86)}/Windows Kits/10/bin" -Directory |
    Where-Object { Test-Path "$($_.FullName)/x64/signtool.exe" } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$sdk) { throw 'Windows SDK SignTool not installed' }

$certOptions = @{
    Type = 'CodeSigningCert'
    Subject = 'CN=Legitimate LLC'
    KeyAlgorithm = 'RSA'
    KeyLength = 2048
    HashAlgorithm = 'SHA256'
    CertStoreLocation = 'Cert:\CurrentUser\My'
    NotAfter = (Get-Date).AddDays(1)
}
$certificate = New-SelfSignedCertificate @certOptions
$msiInstalled = $false
$package = $null
$mode = "$temporary/mode.bin"
try {
    $signed = "$temporary/Pictureshow-ci.msix"
    Copy-Item $msix.FullName $signed
    Export-Certificate -Cert $certificate -FilePath "$temporary/throwaway.cer" | Out-Null
    Import-Certificate -FilePath "$temporary/throwaway.cer" -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
    & "$($sdk.FullName)/x64/signtool.exe" sign /fd SHA256 /sha1 $certificate.Thumbprint $signed
    Add-AppxPackage -Path $signed
    $package = Get-AppxPackage -Name 'LegitimateLLC.Pictureshow'
    if (!$package) { throw 'The test MSIX was not registered' }
    $package | Select-Object Name, Version, Architecture, PackageFullName | ConvertTo-Json |
        Set-Content "$evidence/msix-installed.json"

    $env:PATH = "C:\ProgramData\chocolatey\bin;$env:PATH"
    $env:SPIKE_NULL_AUDIO = '1'
    $env:SPIKE_SOFTWARE_GPU = '1'
    $env:SKIKO_RENDER_API = 'SOFTWARE'
    & cl /nologo /EHsc /std:c++17 desktop/tools/app-smoke/windows-screen.cpp /link user32.lib "/OUT:$temporary/windows-screen.exe"
    $screen = & "$temporary/windows-screen.exe" --save $mode | ConvertFrom-Json
    $env:PICTURESHOW_SMOKE_WINDOW_X = [string]$screen.x
    $env:PICTURESHOW_SMOKE_WINDOW_Y = [string]$screen.y
    $env:PICTURESHOW_SMOKE_WINDOWS_STORE = '1'
    $validationErrors = [System.Collections.Generic.List[string]]::new()
    try {
        & python desktop/tools/app-smoke/run.py --launcher "$($package.InstallLocation)/Pictureshow.exe" `
            --work "$temporary/store-smoke" --evidence "$evidence/store-smoke" --timeout 420
    } catch { $validationErrors.Add("MSIX smoke: $_") }

    try {
        $process = Start-Process msiexec.exe -ArgumentList @('/i', ('"' + $msi.FullName + '"'), '/qn', '/norestart') -Wait -PassThru
        if ($process.ExitCode -ne 0) { throw "Direct MSI installation returned $($process.ExitCode)" }
        $msiInstalled = $true
        $programs = [Environment]::GetFolderPath('Programs')
        $shortcut = Get-ChildItem $programs -Filter 'Pictureshow.lnk' -Recurse | Select-Object -First 1
        if (!$shortcut) { throw 'The direct installer registered no Start menu shortcut' }
        $launcher = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut.FullName).TargetPath
        if (!(Test-Path -LiteralPath $launcher)) { throw 'The direct Start menu shortcut target is missing' }
        $env:PICTURESHOW_SMOKE_WINDOWS_STORE = '0'
        & python desktop/tools/app-smoke/run.py --launcher $launcher `
            --work "$temporary/direct-smoke" --evidence "$evidence/direct-smoke" --timeout 420
    } catch { $validationErrors.Add("Direct MSI smoke: $_") }

    try {
        $appcert = "${env:ProgramFiles(x86)}/Windows Kits/10/App Certification Kit/appcert.exe"
        if (!(Test-Path $appcert)) { throw 'Windows App Certification Kit is missing from the hosted SDK' }
        Remove-AppxPackage -Package $package.PackageFullName
        if (Get-AppxPackage -Name $package.Name) { throw 'MSIX uninstall left the package registered' }
        $package = $null
        & $appcert reset
        & $appcert test -appxpackagepath $signed -reportoutputpath "$evidence/wack.xml"
        if (!(Test-Path "$evidence/wack.xml")) { throw 'Windows App Certification Kit produced no verdict' }
        [xml]$report = Get-Content -LiteralPath "$evidence/wack.xml"
        $verdict = $report.DocumentElement.GetAttribute('OVERALL_RESULT')
        if ($verdict -ne 'PASS') {
            throw "Windows App Certification Kit returned '$verdict'. See wack.xml."
        }
        Write-Host "Windows App Certification Kit: $verdict"
    } catch { $validationErrors.Add("Store certification: $_") }
    if ($validationErrors.Count) { throw ($validationErrors -join [Environment]::NewLine) }

} finally {
    if (Test-Path $mode) { & "$temporary/windows-screen.exe" --restore $mode }
    if ($msiInstalled) {
        $process = Start-Process msiexec.exe -ArgumentList @('/x', ('"' + $msi.FullName + '"'), '/qn', '/norestart') -Wait -PassThru
        if ($process.ExitCode -ne 0 -or ($launcher -and (Test-Path $launcher)) -or ($shortcut -and (Test-Path $shortcut.FullName))) {
            throw "Direct MSI uninstall did not remove the executable and shortcut: $($process.ExitCode)"
        }
    }
    if ($package) {
        Remove-AppxPackage -Package $package.PackageFullName
        if (Get-AppxPackage -Name $package.Name) { throw 'MSIX uninstall left the package registered' }
    }
    foreach ($store in 'LocalMachine\TrustedPeople', 'CurrentUser\My') {
        $location = "Cert:\$store\$($certificate.Thumbprint)"
        if (Test-Path $location) { Remove-Item -LiteralPath $location }
    }
}
