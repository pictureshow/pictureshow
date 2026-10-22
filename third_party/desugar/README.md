# Core library desugaring

Android's `com.android.tools:desugar_jdk_libs:2.1.5` contributes runtime `j$` classes outside the release runtime
classpath. Its published jar has no embedded license files. These license and exception texts come from
[the upstream 2.1.5 release commit](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef).
The build includes them in Settings → About Pictureshow → Open-source licenses, together with notices found
inside dependency archives. The GPLv2 Classpath Exception permits linking independent modules under their own licenses.
