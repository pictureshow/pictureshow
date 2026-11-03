#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
# Two original continuous test signals; container stop kills both children.
set -eu
pids=()
finish() { for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait || true; }
trap finish EXIT
trap 'exit 0' TERM INT
for channel in a b; do
  mkdir -p "/output/$channel"
  frequency=440; color=blue
  if [ "$channel" = b ]; then frequency=880; color=red; fi
  /usr/lib/jellyfin-ffmpeg/ffmpeg -nostdin -hide_banner -loglevel warning \
    -re -f lavfi -i "color=c=$color:s=320x180:r=24" \
    -re -f lavfi -i "sine=frequency=$frequency:sample_rate=48000" \
    -vf "drawtext=text='Scope $channel %{pts\:hms} frame %{n}':x=8:y=30:fontsize=16:fontcolor=white" \
    -c:v libx264 -threads 1 -preset ultrafast -g 48 -sc_threshold 0 -pix_fmt yuv420p \
    -c:a aac -b:a 64k -f hls -hls_time 2 -hls_list_size 6 -hls_flags delete_segments+independent_segments+temp_file \
    -hls_segment_filename "/output/$channel/seg-%06d.ts" -y "/output/$channel/index.m3u8" &
  pids+=("$!")
done
# A producer failure exits and tears down its sibling. No restart policy or timer.
wait -n
