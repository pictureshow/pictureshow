# JogAmp (JOGL and GlueGen)

The desktop app uses JOGL and GlueGen 2.6.0 (`org.jogamp.jogl:jogl-all-main`, `org.jogamp.gluegen:gluegen-rt-main`)
for its OpenGL video surface. Their Maven POMs name several licenses but their jars carry no license text, so the
desktop license catalog (`desktop/licenses.gradle`) uses the projects' own `LICENSE.txt` files at tag v2.6.0:

- `jogl.LICENSE.txt`: https://github.com/sgothel/jogl/blob/v2.6.0/LICENSE.txt
- `gluegen.LICENSE.txt`: https://github.com/sgothel/gluegen/blob/v2.6.0/LICENSE.txt
