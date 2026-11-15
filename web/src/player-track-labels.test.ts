/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { expect, test } from "vitest";
import { playerTrackLabel, playerTrackLabels } from "./player-track-labels";

test("muxer handlers fall back exactly as missing titles, with or without language", () => {
    const stream = {
        Type: "Audio",
        Index: 8,
        Codec: "eac3",
        Channels: 6,
        IsDefault: true,
        IsForced: true,
        IsHearingImpaired: true,
        IsExternal: true,
    };
    for (const Title of [
        "Core Media Audio",
        "cOrE mEdIa TeXt",
        "SoundHandler",
        "GPAC ISO Audio Handler",
        "Lavf61.7.100",
    ]) {
        for (const Language of [undefined, "en"]) {
            const bare = { ...stream, Language };
            expect(playerTrackLabel({ ...bare, Title }, 3), Title).toBe(
                playerTrackLabel(bare, 3),
            );
        }
    }
});

test("real titles containing handler words keep their existing labels", () => {
    for (const Title of [
        "Audio description",
        "Director's commentary",
        "Core Media Audio commentary",
        "The SoundHandler",
        "Lavf documentary",
        "Lavf61.7.100 commentary",
        "Stereo",
        "Surround",
    ]) {
        const stream = {
            Type: "Audio",
            Index: 8,
            Codec: "aac",
            Channels: 2,
            Title,
        };
        expect(playerTrackLabels([stream])[0], Title).toBe(
            `${Title} · AAC stereo · Track 1`,
        );
    }
});
