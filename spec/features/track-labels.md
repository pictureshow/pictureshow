# Track titles and muxer handler names (PLAY-08)

A muxer's handler name is not a track title. Before composing a label, treat a title equal to one of the
following names (case-insensitive, whole string) as absent. Do not trim or collapse whitespace for matching,
remove substrings, or guess from words such as Audio, Video, Text, Stereo, Surround, Commentary or Description.
A descriptive title containing a handler name remains a title. Apply this rule with and without a language,
and for every stream type; handler metadata can survive remuxing into another container.

The existing missing-title policy then supplies language, codec and channel layout, menu-order track number,
collision disambiguation and default/forced/SDH/external badges. All other label rules stay unchanged.
Do not rewrite the stream metadata or use DisplayTitle as a replacement title.

## Whole names

- `Core Media Audio`
- `Core Media Video`
- `Core Media Text`
- `Core Media Subtitle`
- `Core Media Closed Caption`
- `Core Media Metadata`
- `DataHandler`
- `PictureHandler`
- `VideoHandler`
- `SoundHandler`
- `ClosedCaptionHandler`
- `SubtitleHandler`
- `HintHandler`
- `TimeCodeHandler`
- `GoPro MET`
- `CAM meta`
- `CAM dbgi`
- `GPAC ISO Video Handler`
- `GPAC ISO Auxiliary Video Handler`
- `GPAC ISO Picture Sequence Handler`
- `GPAC ISO Audio Handler`
- `GPAC ISO Hint Handler`
- `GPAC Timed MetaData Handler`
- `GPAC MPEG-4 OD Handler`
- `GPAC MPEG-4 OCR Handler`
- `GPAC MPEG-4 Scene Description Handler`
- `GPAC MPEG-4 MPEG-7 Handler`
- `GPAC MPEG-4 OCI Handler`
- `GPAC MPEG-4 IPMP Handler`
- `GPAC MPEG-4 MPEG-J Handler`
- `GPAC Streaming Text Handler`
- `GPAC MPEG Subtitle Handler`
- `GPAC DIMS Handler`
- `GPAC TMCD Handler`
- `GPAC IsoMedia Handler`

Also drop `Lavf` and a full `Lavf<major>.<minor>.<micro>` identifier, with one or more ASCII decimal digits in
each component (for example `Lavf61.7.100`). This is a whole-string, case-insensitive format match, not a
`Lavf*` prefix test. Keep `Lavf61`, `Lavf61.7`, suffixes, extra components and titles such as `Lavf documentary`.

## Evidence and scope

Reviewed 2026-11-15. These are metadata facts reimplemented here, not borrowed client or muxer code.

- Apple's [QuickTime handler component name](https://developer.apple.com/documentation/quicktime-file-format/handler_reference_atom/component_name)
  describes the media handler that created the media; it may be empty. It is distinct from a viewer's track title.
- Apple Core Media Audio/Video/Text/Subtitle/Closed Caption occur in the original media report on the
  [Plex forum](https://forums.plex.tv/t/cannot-get-plex-to-fill-in-media-info-for-4-files/551258).
  Core Media Text also occurs in an [Apple-encoded file report to FFmpeg](https://ffmpeg.org/pipermail/ffmpeg-user/2020-March/047709.html).
  Core Media Metadata occurs in the original file metadata attached to
  [PhotoPrism discussion 4044](https://github.com/photoprism/photoprism/discussions/4044).
- FFmpeg's [MOV muxer, mov_write_hdlr_tag](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/movenc.c)
  writes DataHandler through CAM dbgi above, unless a nonempty handler_name overrides the default.
  Its [version header](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/version.h) defines the
  Lavf plus three-part version identifier. Lavf is an encoder/muxer identifier, not itself a default hdlr
  value in mov_write_hdlr_tag; if it reaches Title, it still supplies no track description.
  The [ISOM language table](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/isom.c) is a separate
  concern; this rule does not infer or alter a stream's language.
- GPAC/MP4Box's [Track_CreateMedia](https://github.com/gpac/gpac/blob/master/src/isomedia/track.c)
  writes the GPAC names above, including Streaming Text and MPEG Subtitle rather than an invented TextHandler.
- HandBrake's [audio/subtitle muxing](https://github.com/HandBrake/HandBrake/blob/master/libhb/muxavformat.c)
  writes a supplied name as title and, for MP4, handler_name; unnamed tracks use libavformat's defaults.
  There is no reason to discard arbitrary HandBrake audio names such as Stereo or Surround.
- mkvmerge's [generic packetizer](https://codeberg.org/mbunkus/mkvtoolnix/src/branch/main/src/merge/generic_packetizer.cpp)
  writes a track name only when it has one; its [QuickTime reader](https://codeberg.org/mbunkus/mkvtoolnix/src/branch/main/src/input/r_qtmp4.cpp)
  passes through an available input name. It does not invent a generic mkvmerge track title. Preserve real
  names carried by these tools, filtering only the listed generated names if they arrive in Title.

## Shared contract

The `track-labels` cases in `spec/vectors/application.json` cover every listed name, mixed case, Lavf versions,
real-title near matches, missing language/format, badges and duplicate labels. Kotlin's
`ApplicationParityVectorsTest` and web's `npm run test:vectors` already dispatch this operation into production
label composition. Every implementation, including the future Apple port, must consume the same expectations.
