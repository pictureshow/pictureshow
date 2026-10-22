# Media3 discovers optional audio extensions by their stable class names and constructor.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { public <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { public static *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegDecoder { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
