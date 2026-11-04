`copied-audio-preroll.ts` is an original generated FFmpeg test pattern with silent AAC 5.1. It contains no library media.

To regenerate (FFmpeg 8):

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=60 -f lavfi -i anullsrc=cl=5.1:r=48000 -t 3 -c:v libx264 -preset ultrafast -g 120 -c:a aac -b:a 128k input.mkv
ffmpeg -ss 0.733 -i input.mkv -t 1.2 -vf fps=30 -c:v libx264 -preset fast -bf 2 -c:a copy -map_metadata -1 -f mpegts copied-audio-preroll.ts
```

First audio PTS is 1.400 s. First video PTS is 2.133 s (DTS 2.066333 s). This exercises preroll and reordered video at the real TS extractor/sample queue boundary.

`aac-stereo.ts` and `aac-fdk-surround.ts` are original one-second silent AAC transport streams. They compare native AAC stereo with FDK AAC 5.1 at the actual extractor and HLS-to-codec boundary. With an FFmpeg build that enables `libfdk_aac`:

```sh
ffmpeg -f lavfi -i anullsrc=cl=stereo:r=48000 -t 1 -c:a aac -b:a 128k -map_metadata -1 -f mpegts aac-stereo.ts
ffmpeg -f lavfi -i anullsrc=cl=5.1:r=48000 -t 1 -c:a libfdk_aac -b:a 320k -map_metadata -1 -f mpegts aac-fdk-surround.ts
```

Both 5.1 encoders produce the same two-byte AudioSpecificConfig (`11b0`) when extracted from ADTS. Stereo produces `1190`.

`embedded-text.mp4` remuxes the original generated H.264/AAC test pattern above and an original
SubRip cue (0–3 s, “Embedded caption”) to MP4 with `mov_text`. Generated with FFmpeg `-c:v copy
-c:a copy -c:s mov_text -movflags +faststart`. It covers native container text selection after
external subtitle preparation was isolated; no household media is included.

`server-seek.ts` is an original six-second 64×64 H.264/AAC stereo test pattern. It provides
real samples beyond the server-seek fixture's two-second residual clipping point (the short
preroll fixture ends before that point). Generated with FFmpeg:

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=30 -f lavfi -i anullsrc=r=48000:cl=stereo -t 6 -c:v libx264 -preset ultrafast -threads 1 -g 60 -c:a aac -b:a 64k -map_metadata -1 -f mpegts server-seek.ts
```
