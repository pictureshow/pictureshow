# SPDX-License-Identifier: MPL-2.0
import ctypes, json, os, pathlib
runtime = pathlib.Path('native/runtime').resolve()
handle = os.add_dll_directory(str(runtime))
report = {}
for prefix, symbol in [('avcodec-', 'avcodec'), ('avformat-', 'avformat'), ('avfilter-', 'avfilter'), ('avutil-', 'avutil'), ('swscale-', 'swscale'), ('swresample-', 'swresample')]:
    dll = next(runtime.glob(prefix+'*.dll'))
    lib = ctypes.CDLL(str(dll))
    result = {}
    for suffix in ['license', 'configuration']:
        function = getattr(lib, symbol+'_'+suffix)
        function.restype = ctypes.c_char_p
        result[suffix] = function().decode()
    assert result['license'].startswith('LGPL'), result
    assert '--enable-gpl' not in result['configuration'], result
    assert '--enable-nonfree' not in result['configuration'], result
    assert '--enable-shared' in result['configuration'], result
    report[dll.name] = result
pathlib.Path('desktop-evidence/licensing/ffmpeg-runtime-config.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
