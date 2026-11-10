# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
$ErrorActionPreference = 'Stop'
$py = (Get-Command python).Source
$dll = Get-ChildItem native -Recurse -Filter '*mpv*.dll' | Select-Object -First 1
$env:MPV_LIBRARY = $dll.FullName
$env:PATH = "$($dll.DirectoryName);C:\Windows\System32;$env:JAVA_HOME\bin"
$env:SPIKE_NULL_AUDIO = '1'
$env:SPIKE_SOFTWARE_GPU = '1'
$env:SPIKE_LAYER_BRIDGE = (Resolve-Path desktop/build/pictureshow-layer.dll).Path
$env:SPIKE_HDR_HOLD_SECONDS = '20'
$env:SKIKO_RENDER_API = 'SOFTWARE'
$captures = @()
foreach ($media in @('pq', 'sdr')) {
  $out = "desktop-evidence/hdr-$media"
  New-Item -ItemType Directory -Force $out | Out-Null
  $env:SPIKE_HDR_READBACK_DIR = (Resolve-Path $out).Path
  $log = Join-Path (Resolve-Path $out) 'app.stdout.log'
  $err = Join-Path (Resolve-Path $out) 'app.stderr.log'
  $args = @('--enable-native-access=ALL-UNNAMED', '-cp', 'desktop/build/spike-runtime/lib/*', 'app.pictureshow.desktop.MainKt', 'layer', "desktop-fixtures/hdr/$media.mkv", $out)
  $app = Start-Process java -ArgumentList $args -PassThru -RedirectStandardOutput $log -RedirectStandardError $err
  try {
    $ready = Join-Path $out '01-window-native.json'
    $deadline = [DateTime]::UtcNow.AddSeconds(55)
    while (!(Test-Path $ready) -and !$app.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (!(Test-Path $ready)) { throw "App never reached stable playback; exit=$($app.ExitCode)" }
    foreach ($mode in @('window', 'display')) {
      $prefix = "$out/$mode"
      & desktop/build/hdr-capture.exe $mode $prefix *> "$out/$mode-capture.log"
      if ($LASTEXITCODE -eq 0) { $captures += $prefix }
    }
    $app.WaitForExit(60000) | Out-Null
    if (!$app.HasExited -or $app.ExitCode -ne 0) { throw "App teardown failed; exit=$($app.ExitCode)" }
    if (Test-Path "$out/swapchain.json") { $captures += "$out/swapchain" }
  } finally {
    if (!$app.HasExited) { Stop-Process -Id $app.Id -Force }
  }
}
foreach ($prefix in $captures) {
  & $py desktop/tools/hdr/windows_samples.py $prefix desktop-fixtures/hdr/manifest.json "$prefix-samples.json"
  if ($LASTEXITCODE -ne 0) { throw 'Numeric sample extraction failed' }
}
# Strict verification is a separate explicit gate: a successful capture is not an HDR PASS.
& $py desktop/tools/hdr/windows_verdict.py desktop-evidence desktop-fixtures/hdr/manifest.json
if ($LASTEXITCODE -ne 0) { throw 'HDR numeric gate not qualified; inspect verdict.json' }
