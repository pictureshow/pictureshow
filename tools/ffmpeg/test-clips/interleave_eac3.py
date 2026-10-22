#!/usr/bin/env python3
"""Interleaves an E-AC-3 independent substream with a dependent one, frame by frame.

usage: interleave_eac3.py <independent .eac3> <dependent .eac3> <output .eac3>
"""
import sys


def frames(data):
    pos = 0
    while pos + 4 <= len(data):
        if data[pos] != 0x0B or data[pos + 1] != 0x77:
            raise ValueError(f"no sync word at byte {pos}")
        size = ((((data[pos + 2] & 0x07) << 8) | data[pos + 3]) + 1) * 2
        yield data[pos:pos + size]
        pos += size


def main():
    independent = list(frames(open(sys.argv[1], "rb").read()))
    dependent = list(frames(open(sys.argv[2], "rb").read()))
    if any((f[2] >> 6) != 0 for f in independent) or any((f[2] >> 6) != 1 for f in dependent):
        raise ValueError("expected independent frames, then dependent frames")
    count = min(len(independent), len(dependent))
    with open(sys.argv[3], "wb") as out:
        for i in range(count):
            out.write(independent[i])
            out.write(dependent[i])
    print(f"{count} frames interleaved")


if __name__ == "__main__":
    main()
