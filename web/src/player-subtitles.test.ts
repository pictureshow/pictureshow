/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { expect, test } from "vitest";
import { validSubtitleText } from "./player-subtitles";
test("optional converted text rejects malformed cue timing but accepts identifiers, notes and settings", () => {
    expect(
        validSubtitleText("WEBVTT\n\nbad timestamp --> wrong timestamp\nbad\n"),
    ).toBe(false);
    expect(
        validSubtitleText("WEBVTT\n\n00:01.000 --> 00:00.000\nbackwards\n"),
    ).toBe(false);
    expect(
        validSubtitleText(
            "WEBVTT\n\nNOTE ignored\n\ncue-id\n00:01.000 --> 02:03:04.500 line:20%\ncaption\n",
        ),
    ).toBe(true);
    expect(
        validSubtitleText(
            "\uFEFFWEBVTT\r\n\r\n00:00.000 --> 00:40.000\r\ntext\r\n",
        ),
    ).toBe(true);
    expect(
        validSubtitleText(
            "WEBVTT\n\nNOTE\nvalid comment\n\n00:00.000 --> 00:01.000\ntext\n",
        ),
    ).toBe(true);
    expect(validSubtitleText("WEBVTT\n\n")).toBe(true);
});
