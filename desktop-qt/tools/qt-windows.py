#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Install only LGPL Qt base/declarative from the current split official repo.
Aqt3.3 does not recognize the per-compiler Updates.xml layout for Qt6.12.
"""
import hashlib,subprocess,sys,urllib.request,xml.etree.ElementTree as ET
from pathlib import Path
base='https://download.qt.io/online/qtsdkrepository/windows_x86/desktop/qt6_6120/qt6_6120_msvc2022_64/'
r=ET.fromstring(urllib.request.urlopen(base+'Updates.xml').read())
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
for p in r.findall('PackageUpdate'):
 if p.findtext('Name')!='qt.qt6.6120.win64_msvc2022_64':continue
 version=p.findtext('Version');name=p.findtext('Name')
 for archive in p.findtext('DownloadableArchives').split(', '):
  if not archive.startswith(('qtbase-','qtdeclarative-')):continue
  url=base+name+'/'+version+archive
  expected=urllib.request.urlopen(url+'.sha1').read().decode().split()[0]
  dest=out/archive;urllib.request.urlretrieve(url,dest)
  if hashlib.sha1(dest.read_bytes()).hexdigest()!=expected:raise SystemExit('Qt archive checksum mismatch')
  subprocess.run(['7z','x',str(dest),'-o'+str(out),'-y'],check=True,stdout=subprocess.DEVNULL)
  dest.unlink()
qt=out/'6.12.0/msvc2022_64'
# Qt binary archive's qmake carries the original SDK prefix; relocatable CMake uses this file.
(qt/'bin/qt.conf').write_text('[Paths]\nPrefix=..\n')
print(qt)
