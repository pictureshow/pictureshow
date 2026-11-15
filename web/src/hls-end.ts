/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */

/**
 * Jellyfin's HLS playlist always declares a final segment for the remainder of
 * the runtime, even when that remainder is shorter than one video frame (a
 * 60.031 s file at 24 fps leaves 0.031 s). The running transcode never writes
 * that segment, so the server starts a new transcode for it, seeking a few
 * seconds earlier; the bytes it returns start before the previous segment ends
 * and the browser's demuxer rejects them ("Parsed buffers not in DTS
 * sequence"), a fatal media error a few seconds before the credits end.
 *
 * A closed (VOD) playlist whose last segment is shorter than this holds no
 * whole frame at 10 fps or more, so it is dropped before hls.js parses it.
 */
export const SUB_FRAME_TAIL_SECONDS = 0.1;

export function trimSubFrameTail(playlist: string): string {
    if (!/^#EXT-X-ENDLIST\s*$/m.test(playlist)) return playlist;
    const lines = playlist.split(/\r?\n/);
    const infs = lines
        .map((line, i) => (line.startsWith("#EXTINF:") ? i : -1))
        .filter((i) => i >= 0);
    // A one-segment playlist keeps its only segment, however short.
    if (infs.length < 2) return playlist;
    const last = infs[infs.length - 1];
    const seconds = Number.parseFloat(lines[last].slice("#EXTINF:".length));
    if (!(seconds >= 0) || seconds >= SUB_FRAME_TAIL_SECONDS) return playlist;
    // The segment is its #EXTINF line plus the tags and URI after it.
    let uri = last + 1;
    while (
        uri < lines.length &&
        (lines[uri].startsWith("#") || !lines[uri].trim())
    )
        uri++;
    if (uri >= lines.length) return playlist;
    return [...lines.slice(0, last), ...lines.slice(uri + 1)].join("\n");
}

/**
 * True when a stall or a decoder error is the natural end of the media rather
 * than a failure: the final fragment is buffered, the clock has reached the end
 * of what was buffered, and that is within 1.5 s of the item's duration. Some
 * servers' last segments end a little before the declared runtime, so `ended`
 * never fires there and the element waits forever.
 */
export function atNaturalEnd(state: {
    position: number;
    duration: number;
    bufferedEnd: number | undefined;
    finalBuffered: boolean;
}): boolean {
    const { position, duration, bufferedEnd, finalBuffered } = state;
    if (!finalBuffered || !(duration > 0) || bufferedEnd === undefined)
        return false;
    return position >= bufferedEnd - 0.3 && duration - position <= 1.5;
}
