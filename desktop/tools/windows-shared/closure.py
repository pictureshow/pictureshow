# SPDX-License-Identifier: MPL-2.0
"""Collect only imported DLLs; fail if any non-system dependency is unresolved."""
import hashlib, json, os, pathlib, re, shutil, subprocess, sys
root = pathlib.Path.cwd()
ffmpeg = pathlib.Path(sys.argv[1])
output = root / 'native/runtime'
output.mkdir(parents=True, exist_ok=True)
evidence = root / 'desktop-evidence/licensing'
evidence.mkdir(parents=True, exist_ok=True)
def native(path):
    return pathlib.Path(subprocess.check_output(['cygpath', '-w', path], text=True).strip())
ucrt = native('/ucrt64')
search = [root/'native/install/bin', ffmpeg/'bin', ucrt/'bin']
index = {p.name.lower(): p for folder in reversed(search) for p in folder.glob('*.dll')}
system = pathlib.Path(os.environ['WINDIR'])/'System32'
systems = {p.name.lower() for p in system.glob('*')}
queue = list((root/'native/install/bin').glob('*.dll'))
assert len(queue) == 1, queue
queue += list((root/'native/install/bin').glob('mpv.exe'))
graph, origins, packages = {}, {}, set()
while queue:
    file = queue.pop()
    if file.name.lower() in graph:
        continue
    text = subprocess.check_output(['objdump', '-p', str(file)], text=True)
    imports = re.findall(r'DLL Name:\s*(\S+)', text)
    graph[file.name.lower()] = imports
    shutil.copy2(file, output/file.name)
    origins[file.name] = {'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                          'source': str(file.relative_to(root)) if file.is_relative_to(root) else str(file)}
    if file.is_relative_to(ucrt):
        unix = subprocess.check_output(['cygpath', '-u', str(file)], text=True).strip()
        package = subprocess.check_output(['pacman', '-Qqo', unix], text=True).strip()
        packages.add(package)
    for name in imports:
        lower = name.lower()
        if lower in index:
            queue.append(index[lower])
        elif lower in systems or lower.startswith(('api-ms-win-', 'ext-ms-win-')):
            pass
        else:
            raise RuntimeError(f'{file.name}: unresolved dependency {name}')
mpv = next(k for k in graph if 'mpv' in k and k.endswith('.dll'))
direct = '\n'.join(graph[mpv]).lower()
for dependency in ['avcodec-', 'avformat-', 'avfilter-', 'avutil-', 'swscale-', 'swresample-', 'libass-', 'libplacebo-']:
    assert dependency in direct, f'{mpv} does not import separate {dependency} DLL'
(evidence/'windows-pe-imports.json').write_text(json.dumps(graph, indent=2))
(evidence/'runtime-manifest.json').write_text(json.dumps(origins, indent=2))
(evidence/'windows-pe-imports.txt').write_text('\n\n'.join(k+'\n  '+'\n  '.join(v) for k,v in sorted(graph.items())))
license_root = evidence/'msys-runtime-licenses'
for package in sorted(packages):
    info = subprocess.check_output(['pacman', '-Qi', package], text=True)
    assert not re.search(r'^Name\s*:.*(?:ffmpeg|rubberband|libcdio|dvd)', info, re.M), info
    dest = license_root/package
    dest.mkdir(parents=True, exist_ok=True)
    (dest/'package.txt').write_text(info)
    for file in subprocess.check_output(['pacman', '-Qlq', package], text=True).splitlines():
        source = native(file)
        if '/share/licenses/' in file and source.is_file():
            shutil.copy2(source, dest/source.name)
for name in ['LICENSE.LGPL', 'Copyright']:
    shutil.copy2(root/'native/source/mpv-0.42.0'/name, evidence/('mpv-'+name))
for file in ffmpeg.glob('*LICENSE*'):
    if file.is_file(): shutil.copy2(file, evidence/('ffmpeg-'+file.name))
print(json.dumps({'runtimeFiles': len(graph), 'msysPackages': sorted(packages)}, indent=2))
