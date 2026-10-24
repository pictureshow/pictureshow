`copied-audio-preroll.ts` is an original generated FFmpeg test pattern with silent AAC 5.1. It contains no library media.

To regenerate (FFmpeg 8):

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=60 -f lavfi -i anullsrc=cl=5.1:r=48000 -t 3 -c:v libx264 -preset ultrafast -g 120 -c:a aac -b:a 128k input.mkv
ffmpeg -ss 0.733 -i input.mkv -t 1.2 -vf fps=30 -c:v libx264 -preset fast -bf 2 -c:a copy -map_metadata -1 -f mpegts copied-audio-preroll.ts
```

First audio PTS is 1.400 s. First video PTS is 2.133 s (DTS 2.066333 s). This exercises preroll and reordered video at the real TS extractor/sample queue boundary.
