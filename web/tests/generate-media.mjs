import { mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
const dir = join(tmpdir(), "pictureshow-web-media");
mkdirSync(dir, { recursive: true });
execFileSync("ffmpeg", [
    "-hide_banner",
    "-loglevel",
    "error",
    "-y",
    "-f",
    "lavfi",
    "-i",
    "testsrc2=size=320x180:rate=24",
    "-f",
    "lavfi",
    "-i",
    "sine=frequency=220:sample_rate=48000",
    "-t",
    "40",
    "-c:v",
    "libx264",
    "-preset",
    "ultrafast",
    "-pix_fmt",
    "yuv420p",
    "-g",
    "48",
    "-c:a",
    "aac",
    "-ac",
    "2",
    "-movflags",
    "+faststart",
    join(dir, "movie.mp4"),
]);
execFileSync("ffmpeg", [
    "-hide_banner",
    "-loglevel",
    "error",
    "-y",
    "-i",
    join(dir, "movie.mp4"),
    "-c",
    "copy",
    "-hls_time",
    "2",
    "-hls_list_size",
    "0",
    "-hls_segment_filename",
    join(dir, "segment%d.ts"),
    join(dir, "master.m3u8"),
]);
