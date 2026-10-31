/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
/** Pure translation of the Android reference capability/profile policy. This is
 * for cross-platform contract evaluation, never browser capability discovery.
 * Runtime browser playback must keep using its actual MIME/MSE/native probes. */
const VIDEO = ["H264", "HEVC", "VP8", "VP9", "AV1", "MPEG2", "MPEG4", "VC1"];
const AUDIO = [
    "AAC",
    "AC3",
    "EAC3",
    "TRUEHD",
    "DTS",
    "FLAC",
    "ALAC",
    "OPUS",
    "VORBIS",
    "MP3",
    "MP2",
    "PCM",
];
const videoName: Record<string, string> = {
    H264: "h264",
    HEVC: "hevc",
    VP8: "vp8",
    VP9: "vp9",
    AV1: "av1",
    MPEG2: "mpeg2video",
    MPEG4: "mpeg4",
    VC1: "vc1",
};
const audioNames: Record<string, string[]> = {
    ...Object.fromEntries(AUDIO.map((c) => [c, [c.toLowerCase()]])),
    PCM: [
        "pcm_s16le",
        "pcm_s16be",
        "pcm_s24le",
        "pcm_s24be",
        "pcm_s32le",
        "pcm_f32le",
        "pcm_u8",
    ],
};
const PASSTHROUGH: Record<string, [string, number]> = {
    AC3: ["AC3", 6],
    EAC3: ["EAC3", 8],
    EAC3_JOC: ["EAC3", 8],
    DTS: ["DTS", 6],
    DTS_HD: ["DTS", 8],
    TRUEHD: ["TRUEHD", 8],
};
const RANGES = [
    "Unknown",
    "SDR",
    "HDR10",
    "HLG",
    "DOVI",
    "DOVIWithHDR10",
    "DOVIWithHLG",
    "DOVIWithSDR",
    "DOVIWithEL",
    "DOVIWithHDR10Plus",
    "DOVIWithELHDR10Plus",
    "DOVIInvalid",
    "HDR10Plus",
];
const CONTAINERS: [string, string[], string[], string[]][] = [
    [
        "mkv",
        ["H264", "HEVC", "VP8", "VP9", "AV1", "MPEG2", "MPEG4"],
        [
            "AAC",
            "AC3",
            "EAC3",
            "TRUEHD",
            "DTS",
            "FLAC",
            "ALAC",
            "OPUS",
            "VORBIS",
            "MP3",
            "MP2",
            "PCM",
        ],
        ["subrip", "srt", "ass", "ssa", "webvtt", "pgssub", "dvdsub", "dvbsub"],
    ],
    ["webm", ["VP8", "VP9", "AV1"], ["OPUS", "VORBIS"], ["webvtt"]],
    [
        "mp4,m4v,mov",
        ["H264", "HEVC", "VP9", "AV1", "MPEG2", "MPEG4"],
        [
            "AAC",
            "AC3",
            "EAC3",
            "TRUEHD",
            "DTS",
            "FLAC",
            "ALAC",
            "OPUS",
            "MP3",
            "PCM",
        ],
        ["mov_text", "webvtt", "ttml", "dvdsub"],
    ],
    [
        "ts,mpegts",
        ["H264", "HEVC", "MPEG2"],
        ["AAC", "AC3", "EAC3", "DTS", "MP3", "MP2"],
        ["dvbsub"],
    ],
    ["avi", ["H264", "MPEG4"], ["AAC", "AC3", "DTS", "MP3", "PCM"], []],
    ["mpeg,mpg", ["MPEG2", "H264"], ["AC3", "MP2", "MP3", "DTS"], []],
    ["flv", ["H264"], ["AAC", "MP3"], []],
    ["ogg,ogv", [], ["OPUS", "VORBIS", "FLAC"], []],
];
const TEXT_SUBTITLES = ["srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml"];
const union = <T>(a: T[], b: T[]) => [...new Set([...a, ...b])];
const levelNumber = (v: any) =>
    v == null ? null : v.major * 10 + (v.minor ?? 0);
const minLevel = (values: any[]) =>
    values
        .filter((v) => v != null)
        .sort((a, b) => levelNumber(a)! - levelNumber(b)!)[0] ?? null;
const maxLevel = (values: any[]) =>
    values
        .filter((v) => v != null)
        .sort((a, b) => levelNumber(b)! - levelNumber(a)!)[0] ?? null;
const sameSet = (a: any[], b: any[]) =>
    a.length === b.length && a.every((x) => b.includes(x));
function decoder(value: any) {
    return {
        maxLevel: null,
        maxFrameRateAtMaxSize: value.maxFrameRate,
        maxBitDepth: 8,
        hdrFormats: [],
        dolbyVisionProfiles: [],
        hardware: true,
        sustainablePerformance: null,
        profileCapabilities: [],
        ...value,
    };
}
function conservative(d: any) {
    d = decoder(d);
    if (d.profiles.length) return d;
    if (d.profileCapabilities.length)
        return {
            ...d,
            profiles: [
                ...new Set(d.profileCapabilities.map((p: any) => p.profile)),
            ],
        };
    const fallback: Record<string, string[]> = {
        H264: ["Baseline", "Constrained Baseline"],
        HEVC: ["Main"],
        AV1: ["Main"],
        VP8: ["0"],
        VP9: ["Profile 0"],
        MPEG2: ["Simple"],
        VC1: ["Simple"],
        MPEG4: ["Simple Profile"],
    };
    const base = ["H264", "HEVC"].includes(d.codec)
        ? { major: 1 }
        : d.codec === "AV1"
          ? { major: 2 }
          : null;
    return {
        ...d,
        profiles: fallback[d.codec],
        maxLevel: base ? minLevel([base, d.maxLevel]) : null,
        maxBitDepth: Math.min(d.maxBitDepth, 8),
        hdrFormats: [],
        dolbyVisionProfiles: [],
    };
}
function sustainable(d: any) {
    const p = d.sustainablePerformance;
    return d.codec === "AV1" && !d.hardware && p
        ? {
              ...d,
              maxWidth: Math.min(d.maxWidth, p.width),
              maxHeight: Math.min(d.maxHeight, p.height),
              maxFrameRate: Math.min(d.maxFrameRate, p.frameRate),
              maxFrameRateAtMaxSize: Math.min(
                  d.maxFrameRateAtMaxSize,
                  p.frameRate,
              ),
          }
        : d;
}
function merge(a: any, b: any) {
    return {
        ...a,
        profiles: union(a.profiles, b.profiles),
        maxLevel: maxLevel([a.maxLevel, b.maxLevel]),
        maxWidth: Math.max(a.maxWidth, b.maxWidth),
        maxHeight: Math.max(a.maxHeight, b.maxHeight),
        maxFrameRate: Math.max(a.maxFrameRate, b.maxFrameRate),
        maxFrameRateAtMaxSize: Math.max(
            a.maxFrameRateAtMaxSize,
            b.maxFrameRateAtMaxSize,
        ),
        maxBitDepth: Math.max(a.maxBitDepth, b.maxBitDepth),
        hdrFormats: union(a.hdrFormats, b.hdrFormats),
        dolbyVisionProfiles: union(
            a.dolbyVisionProfiles,
            b.dolbyVisionProfiles,
        ),
    };
}
const volume = (d: any) => d.maxWidth * d.maxHeight * d.maxFrameRate;
function selectVideo(decoders: any[]) {
    const candidates = decoders
        .map(decoder)
        .filter(
            (d) => d.codec !== "AV1" || d.hardware || d.sustainablePerformance,
        )
        .map(sustainable);
    return Object.fromEntries(
        VIDEO.flatMap((codec) => {
            const available = candidates.filter((d) => d.codec === codec);
            if (!available.length) return [];
            const hardware = available.filter((d) => d.hardware),
                preferred = hardware.length ? hardware : available;
            return [
                [
                    codec,
                    codec === "AV1" && !hardware.length
                        ? preferred.reduce((a, b) =>
                              volume(a) >= volume(b) ? a : b,
                          )
                        : preferred.reduce(merge),
                ],
            ];
        }),
    );
}
const condition = (
    Property: string,
    Value: string,
    Condition = "LessThanEqual",
    IsRequired = false,
) => ({ Condition, Property, Value, IsRequired });
const equalsAny = (p: string, v: string[]) =>
    condition(p, v.join("|"), "EqualsAny");
const codecProfile = (
    Type: string,
    Codec: string,
    Conditions: any[],
    ApplyConditions: any[] = [],
    Container?: string,
) => ({
    Type,
    Conditions,
    ApplyConditions,
    Codec,
    ...(Container ? { Container } : {}),
});
function serverLevel(codec: string, level: any): number | null {
    if (level == null) return null;
    const n = level.major * 10 + (level.minor ?? 0);
    return codec === "H264"
        ? n
        : codec === "HEVC"
          ? n * 3
          : codec === "AV1"
            ? (level.major - 2) * 4 + (level.minor ?? 0)
            : null;
}
function relaxHevc(d: any) {
    return (
        d.codec === "HEVC" &&
        d.hardware &&
        d.maxLevel != null &&
        (serverLevel(d.codec, d.maxLevel) ?? 0) >= 153 &&
        d.maxWidth >= 3840 &&
        d.maxHeight >= 2160 &&
        d.maxFrameRateAtMaxSize >= 60
    );
}
function rangeTypes(d: any, display: any): string[] {
    const shown = d.hdrFormats.filter((f: string) =>
            (display.hdrFormats ?? []).includes(f),
        ),
        hdr10 = shown.includes("HDR10"),
        hlg = shown.includes("HLG"),
        dvCodec = ["HEVC", "AV1"].includes(d.codec),
        dv = dvCodec && shown.includes("DOLBY_VISION"),
        r = new Set(["SDR"]);
    if (hdr10) r.add("HDR10");
    if (hlg) r.add("HLG");
    if (shown.includes("HDR10_PLUS")) r.add("HDR10Plus");
    if (dvCodec) {
        r.add("DOVIWithSDR");
        if (dv || hdr10) {
            r.add("DOVIWithHDR10");
            r.add("DOVIWithHDR10Plus");
        }
        if (dv || hlg) r.add("DOVIWithHLG");
    }
    if (dv) {
        if (d.dolbyVisionProfiles.includes(d.codec === "HEVC" ? 5 : 10))
            r.add("DOVI");
        if (d.codec === "HEVC" && d.dolbyVisionProfiles.includes(7)) {
            r.add("DOVIWithEL");
            r.add("DOVIWithELHDR10Plus");
        }
    }
    return RANGES.filter((k) => r.has(k));
}
function videoProfiles(d: any, display: any): any[] {
    const required = d.codec === "AV1" && !d.hardware;
    const conditions: any[] = [];
    if (d.profiles.length)
        conditions.push(
            equalsAny(
                "VideoProfile",
                d.profiles.map((p: string) => p.toLowerCase()),
            ),
        );
    const level = serverLevel(d.codec, d.maxLevel);
    if (level != null && !relaxHevc(d))
        conditions.push(condition("VideoLevel", String(level)));
    conditions.push(
        condition("VideoBitDepth", String(d.maxBitDepth)),
        condition("Width", String(d.maxWidth), "LessThanEqual", required),
        condition("Height", String(d.maxHeight), "LessThanEqual", required),
        condition(
            "VideoFramerate",
            String(d.maxFrameRate),
            "LessThanEqual",
            required,
        ),
    );
    if (required)
        conditions.push(
            condition("VideoFramerate", "1", "GreaterThanEqual", true),
        );
    conditions.push(equalsAny("VideoRangeType", rangeTypes(d, display)));
    const codec = videoName[d.codec],
        base = codecProfile("Video", codec, conditions),
        extras: any[] = [];
    if (relaxHevc(d)) {
        const reported = serverLevel(d.codec, d.maxLevel);
        const scopes: [any[], number | null][] = [
            [
                [
                    condition("Width", "1920"),
                    condition("Height", "1080"),
                    condition("VideoFramerate", "30"),
                ],
                186,
            ],
            [[condition("Width", "1921", "GreaterThanEqual")], reported],
            [[condition("Height", "1081", "GreaterThanEqual")], reported],
            [
                [
                    condition(
                        "VideoFramerate",
                        "30.000000000000004",
                        "GreaterThanEqual",
                    ),
                ],
                reported,
            ],
            [[condition("VideoFramerate", "0")], reported],
        ];
        for (const [scope, cap] of scopes)
            extras.push(
                codecProfile(
                    "Video",
                    codec,
                    [condition("VideoLevel", String(cap))],
                    scope,
                ),
            );
    }
    if (required)
        extras.push(
            codecProfile("Video", codec, conditions, [
                condition("Width", String(d.maxWidth)),
                condition("Height", String(d.maxHeight)),
                condition("VideoFramerate", String(d.maxFrameRate)),
            ]),
        );
    return [
        base,
        ...(d.maxWidth > 1920 && d.maxFrameRateAtMaxSize < d.maxFrameRate
            ? [
                  codecProfile(
                      "Video",
                      codec,
                      [
                          condition(
                              "VideoFramerate",
                              String(d.maxFrameRateAtMaxSize),
                          ),
                      ],
                      [condition("Width", "1921", "GreaterThanEqual")],
                  ),
              ]
            : []),
        ...extras,
    ];
}
function dominates(a: any, b: any) {
    return (
        a.maxWidth >= b.maxWidth &&
        a.maxHeight >= b.maxHeight &&
        a.maxFrameRate >= b.maxFrameRate &&
        a.maxFrameRateAtMaxSize >= b.maxFrameRateAtMaxSize &&
        a.maxBitDepth >= b.maxBitDepth &&
        b.hdrFormats.every((v: string) => a.hdrFormats.includes(v)) &&
        b.dolbyVisionProfiles.every((v: number) =>
            a.dolbyVisionProfiles.includes(v),
        ) &&
        (a.maxLevel == null ||
            (b.maxLevel != null &&
                levelNumber(a.maxLevel)! >= levelNumber(b.maxLevel)!))
    );
}
function guardedVideo(
    reported: any[],
    selected: Record<string, any>,
    display: any,
) {
    return Object.entries(selected).flatMap(([codec, summary]) => {
        const available = reported.filter(
                (d) =>
                    d.codec === codec &&
                    (d.hardware || codec !== "AV1" || d.sustainablePerformance),
            ),
            hardware = available.filter((d) => d.hardware),
            preferred = hardware.length ? hardware : available;
        const tuples = preferred
            .flatMap((d) => {
                if (!d.profileCapabilities.length)
                    return d.profiles.length
                        ? d.profiles.map((p: string) => ({
                              ...d,
                              profiles: [p],
                          }))
                        : [d];
                const common = new Map<string, any>();
                for (const t of d.profileCapabilities) {
                    const key = t.profile.toLowerCase() + ":" + t.maxBitDepth;
                    common.set(
                        key,
                        minLevel(
                            d.profileCapabilities
                                .filter(
                                    (x: any) =>
                                        x.profile.toLowerCase() +
                                            ":" +
                                            x.maxBitDepth ===
                                        key,
                                )
                                .map((x: any) => x.maxLevel),
                        ),
                    );
                }
                return d.profileCapabilities.map((t: any) => ({
                    ...d,
                    profiles: [t.profile],
                    maxLevel: common.get(
                        t.profile.toLowerCase() + ":" + t.maxBitDepth,
                    ),
                    maxBitDepth: t.maxBitDepth,
                    hdrFormats: union(
                        t.hdrFormats ?? [],
                        d.hdrFormats.includes("DOLBY_VISION") &&
                            t.maxBitDepth >= 10
                            ? ["DOLBY_VISION"]
                            : [],
                    ),
                    profileCapabilities: [],
                }));
            })
            .map(sustainable);
        const heterogeneous =
            preferred.length > 1 ||
            preferred.some((d) =>
                d.profileCapabilities.some(
                    (t: any) =>
                        levelNumber(t.maxLevel) !== levelNumber(d.maxLevel) ||
                        t.maxBitDepth !== d.maxBitDepth ||
                        !sameSet(t.hdrFormats ?? [], d.hdrFormats),
                ),
            );
        if (!heterogeneous) return videoProfiles(summary, display);
        const groups = new Map<string | null, any[]>();
        for (const t of tuples) {
            const profile = t.profiles.length === 1 ? t.profiles[0] : null;
            groups.set(profile, [...(groups.get(profile) ?? []), t]);
        }
        const scoped = [...groups.entries()].flatMap(
            ([profile, alternatives]) => {
                const actual =
                    alternatives.find((c) =>
                        alternatives.every((b) => dominates(c, b)),
                    ) ??
                    alternatives.reduce((a, b) =>
                        volume(a) >= volume(b) ? a : b,
                    );
                return videoProfiles(actual, display).map((p) => ({
                    ...p,
                    ApplyConditions: [
                        ...(profile
                            ? [
                                  equalsAny("VideoProfile", [
                                      profile.toLowerCase(),
                                  ]),
                              ]
                            : []),
                        ...p.ApplyConditions,
                    ],
                }));
            },
        );
        return tuples.every((t) => !t.profiles.length)
            ? scoped
            : [...videoProfiles(summary, display), ...scoped];
    });
}
export function referenceProfile(capabilities: any): any {
    const reported = capabilities.videoDecoders.map(conservative),
        video = selectVideo(reported),
        limits: Record<string, number> = {};
    for (const d of capabilities.audioDecoders)
        limits[d.codec] = Math.max(limits[d.codec] ?? 0, d.maxChannels);
    if (!("PCM" in limits)) limits.PCM = 8;
    const enabled = capabilities.settings?.passthroughEnabled ?? false,
        outputs = capabilities.audioOutput.passthrough ?? [];
    if (enabled)
        for (const p of outputs) {
            const [codec, channels] = PASSTHROUGH[p];
            limits[codec] = Math.max(limits[codec] ?? 0, channels);
        }
    const audio = Object.fromEntries(
        AUDIO.filter((a) => a in limits).map((a) => [a, limits[a]]),
    );
    const bitrate = capabilities.settings?.maxStreamingBitrate ?? 200000000;
    if (!(bitrate > 0)) throw new RangeError("Bitrate must be positive.");
    const direct = CONTAINERS.flatMap(([Container, vs, as]) => {
        const v = VIDEO.filter((c) => vs.includes(c) && c in video),
            a = AUDIO.filter((c) => as.includes(c) && c in audio);
        return v.length && a.length
            ? [
                  {
                      Container,
                      AudioCodec: a.flatMap((c) => audioNames[c]).join(","),
                      VideoCodec: v.map((c) => videoName[c]).join(","),
                      Type: "Video",
                  },
              ]
            : [];
    });
    const targets = enabled
            ? outputs.map((p: string) => PASSTHROUGH[p][0])
            : [],
        audioOrder = [
            ...(targets.includes("EAC3")
                ? ["EAC3", "AC3", "AAC"]
                : targets.includes("AC3")
                  ? ["AC3", "AAC", "EAC3"]
                  : ["AAC", "AC3", "EAC3"]),
            "MP3",
            "OPUS",
            "FLAC",
            "ALAC",
            "DTS",
            "TRUEHD",
        ].filter((c) => c in audio),
        videoOrder = ["H264", "HEVC", "AV1", "VP9"].filter((c) => c in video),
        tsV = videoOrder.filter((c) => ["H264", "HEVC"].includes(c)),
        tsA = audioOrder.filter((c) =>
            ["AAC", "AC3", "EAC3", "MP3"].includes(c),
        );
    const rawSelected = selectVideo(capabilities.videoDecoders),
        sw = rawSelected.H264,
        point = sw?.sustainablePerformance;
    const size = [
            condition("Width", String(capabilities.display.maxWidth)),
            condition("Height", String(capabilities.display.maxHeight)),
        ],
        rate =
            videoOrder[0] === "H264" &&
            sw?.hardware === false &&
            sw.maxFrameRate < 30 &&
            point &&
            point.frameRate >= 30 &&
            capabilities.display.maxWidth <= point.width &&
            capabilities.display.maxHeight <= point.height
                ? [condition("VideoFramerate", "30", "GreaterThanEqual")]
                : [];
    const transcode =
        tsV.length && tsA.length
            ? [
                  {
                      Container: "ts",
                      Type: "Video",
                      VideoCodec: tsV.map((c) => videoName[c]).join(","),
                      AudioCodec: tsA.flatMap((c) => audioNames[c]).join(","),
                      Protocol: "hls",
                      EstimateContentLength: false,
                      EnableMpegtsM2TsMode: false,
                      TranscodeSeekInfo: "Auto",
                      CopyTimestamps: false,
                      Context: "Streaming",
                      EnableSubtitlesInManifest: false,
                      MaxAudioChannels: String(
                          Math.max(
                              capabilities.audioOutput.pcmChannels,
                              enabled
                                  ? Math.max(
                                        0,
                                        ...outputs.map(
                                            (p: string) => PASSTHROUGH[p][1],
                                        ),
                                    )
                                  : 0,
                          ),
                      ),
                      MinSegments: 1,
                      SegmentLength: 0,
                      BreakOnNonKeyFrames: false,
                      Conditions: [...size, ...rate],
                      EnableAudioVbrEncoding: true,
                  },
              ]
            : [];
    const audioProfiles = Object.entries(audio).map(([c, channels]) =>
        codecProfile("VideoAudio", audioNames[c].join(","), [
            condition("AudioChannels", String(channels)),
        ]),
    );
    if ((audio.EAC3 ?? 0) > 6)
        audioProfiles.push(
            codecProfile(
                "VideoAudio",
                "eac3",
                [condition("AudioChannels", "6")],
                [],
                "ts,mpegts",
            ),
        );
    const subtitles = [
        ...CONTAINERS.flatMap(([Container, , , subtitles]) =>
            subtitles.map((Format) => ({ Format, Method: "Embed", Container })),
        ),
        ...TEXT_SUBTITLES.map((Format) => ({ Format, Method: "External" })),
        ...["pgssub", "dvdsub", "dvbsub"].map((Format) => ({
            Format,
            Method: "Encode",
        })),
    ];
    return {
        Name: capabilities.name,
        MaxStreamingBitrate: bitrate,
        MaxStaticBitrate: bitrate,
        DirectPlayProfiles: direct,
        TranscodingProfiles: transcode,
        ContainerProfiles: [],
        CodecProfiles: [
            ...guardedVideo(reported, video, capabilities.display),
            ...audioProfiles,
        ],
        SubtitleProfiles: subtitles,
    };
}
function contains(profileList: string | undefined | null, value: string) {
    if (!profileList?.trim()) return true;
    const values = value.split(",").map((s) => s.trim().toLowerCase());
    return profileList
        .split(",")
        .some((s) => values.includes(s.trim().toLowerCase()));
}
function satisfies(c: any, combo: any) {
    const key: Record<string, string> = {
        AudioChannels: "audioChannels",
        VideoBitDepth: "bitDepth",
        VideoProfile: "videoProfile",
        VideoRangeType: "rangeType",
        Width: "width",
        Height: "height",
        VideoFramerate: "frameRate",
        VideoLevel: "level",
    };
    const actual =
        c.Property === "VideoFramerate"
            ? (combo.frameRate ?? 0)
            : combo[key[c.Property]];
    if (actual == null) return !c.IsRequired;
    const a = String(actual),
        b = c.Value ?? "";
    switch (c.Condition) {
        case "Equals":
            return a.toLowerCase() === b.toLowerCase();
        case "NotEquals":
            return a.toLowerCase() !== b.toLowerCase();
        case "LessThanEqual":
            return Number(a) <= Number(b);
        case "GreaterThanEqual":
            return Number(a) >= Number(b);
        case "EqualsAny": {
            const opts = b.toLowerCase().split("|");
            return (
                opts.includes(a.toLowerCase()) ||
                (c.Property === "VideoRangeType" &&
                    a === "HDR10Plus" &&
                    opts.includes("hdr10"))
            );
        }
        default:
            return false;
    }
}
export function referenceCompatibility(profile: any, combo: any): string[] {
    const reasons = new Set<string>(),
        video = profile.DirectPlayProfiles.filter(
            (p: any) => p.Type === "Video",
        ),
        containers = video.filter((p: any) =>
            contains(p.Container, combo.container),
        );
    if (!containers.length) reasons.add("ContainerNotSupported");
    const matching = containers.filter(
        (p: any) =>
            combo.videoCodec == null ||
            contains(p.VideoCodec, combo.videoCodec),
    );
    if (containers.length && !matching.length)
        reasons.add("VideoCodecNotSupported");
    if (
        matching.length &&
        combo.audioCodec != null &&
        !matching.some((p: any) => contains(p.AudioCodec, combo.audioCodec))
    )
        reasons.add("AudioCodecNotSupported");
    const reason: Record<string, string> = {
        AudioChannels: "AudioChannelsNotSupported",
        VideoBitDepth: "VideoBitDepthNotSupported",
        VideoProfile: "VideoProfileNotSupported",
        VideoRangeType: "VideoRangeTypeNotSupported",
        Width: "VideoResolutionNotSupported",
        Height: "VideoResolutionNotSupported",
        VideoFramerate: "VideoFramerateNotSupported",
        VideoLevel: "VideoLevelNotSupported",
    };
    for (const [type, codec] of [
        ["Video", combo.videoCodec],
        ["VideoAudio", combo.audioCodec],
    ])
        if (codec != null)
            for (const p of profile.CodecProfiles.filter(
                (p: any) =>
                    p.Type === type &&
                    contains(p.Codec, codec) &&
                    (p.Container == null ||
                        contains(p.Container, combo.container)),
            ))
                if (p.ApplyConditions.every((c: any) => satisfies(c, combo)))
                    for (const c of p.Conditions.filter(
                        (c: any) => !satisfies(c, combo),
                    ))
                        reasons.add(reason[c.Property] ?? "UnknownReason");
    for (const sub of combo.subtitles ?? [])
        if (
            !profile.SubtitleProfiles.some(
                (p: any) =>
                    p.Format?.toLowerCase() === sub.toLowerCase() &&
                    ((p.Method === "Embed" &&
                        contains(p.Container, combo.container)) ||
                        (p.Method === "External" &&
                            [...TEXT_SUBTITLES, "mov_text"].includes(sub))),
            )
        )
            reasons.add("SubtitleCodecNotSupported");
    return [...reasons].sort();
}
