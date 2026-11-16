# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Build and run an isolated helper-frontmost native surface regression."""
import os
import json
import signal
from pathlib import Path
import plistlib
import subprocess
import sys


def main():
    if sys.platform != 'darwin':
        raise RuntimeError('Native focus regression needs macOS')
    prefix, library, build, output = map(Path, sys.argv[1:])
    for name in ('FocusHost', 'FocusHelper'):
        app = build / f'{name}.app'
        binary = app / 'Contents' / 'MacOS' / 'macos-focus'
        binary.parent.mkdir(parents=True, exist_ok=True)
        (app / 'Contents' / 'Info.plist').write_bytes(plistlib.dumps({
            'CFBundleIdentifier': f'app.pictureshow.smoke.{name.lower()}',
            'CFBundleExecutable': binary.name,
            'CFBundleName': name,
            'CFBundlePackageType': 'APPL',
        }))
        if name == 'FocusHost':
            subprocess.run(['clang++', '-std=c++17', '-O2', '-fobjc-arc',
                            'desktop/tools/app-smoke/macos-focus.mm',
                            f'-I{prefix / "include"}', f'-L{prefix / "lib"}', '-lmpv',
                            f'-Wl,-rpath,{prefix / "lib"}', '-framework', 'Cocoa',
                            '-o', str(binary)], check=True)
        else:
            import shutil
            shutil.copy2(build / 'FocusHost.app/Contents/MacOS/macos-focus', binary)
        subprocess.run(['codesign', '--force', '--sign', '-', str(app)], check=True,
                       stdout=subprocess.DEVNULL)
    output.parent.mkdir(parents=True, exist_ok=True)
    # A crash before native cleanup must not leave a foreground helper behind.
    output.unlink(missing_ok=True)
    try:
        subprocess.run([str(build / 'FocusHost.app/Contents/MacOS/macos-focus'),
                        str(library.resolve()), str((build / 'FocusHelper.app').resolve()),
                        str(output.resolve())], check=True, timeout=35, env=dict(os.environ))
    finally:
        receipt = json.loads(output.read_text()) if output.exists() else {}
        helper_pid = receipt.get('helperPID')
        if helper_pid and not receipt.get('helperStopped'):
            command = subprocess.run(['ps', '-p', str(helper_pid), '-o', 'command='],
                                     capture_output=True, text=True).stdout
            if 'FocusHelper.app/Contents/MacOS/macos-focus' in command and '--helper' in command:
                os.kill(helper_pid, signal.SIGTERM)


if __name__ == '__main__':
    main()
