#!/usr/bin/env python3
"""Truncate a Matroska file just before its Cues element, producing a
Cues-less file for the probe gate's scan-fallback test. Cues is located
exactly via SeekHead, never by scanning media bytes.
Usage: strip_cues.py in.mkv out.mkv
"""
import sys


def read_vint(data, pos):
    first = data[pos]
    mask = 0x80
    length = 1
    while length <= 8 and first & mask == 0:
        mask >>= 1
        length += 1
    if length > 8 or pos + length > len(data):
        raise ValueError("bad vint at %d" % pos)
    return length


def parse_id(data, pos):
    length = read_vint(data, pos)
    if length > 4:
        raise ValueError("id too long")
    value = 0
    for i in range(length):
        value = (value << 8) | data[pos + i]
    return value, length


def parse_size(data, pos):
    length = read_vint(data, pos)
    first = data[pos]
    value = first & (0xFF >> length)
    for i in range(1, length):
        value = (value << 8) | data[pos + i]
    if value == (1 << (7 * length)) - 1:
        return -1, length
    return value, length


def parse_header(data, pos):
    eid, id_len = parse_id(data, pos)
    size, size_len = parse_size(data, pos + id_len)
    return eid, size, id_len + size_len


def children(data, start, end):
    pos = start
    while pos < end:
        eid, size, hlen = parse_header(data, pos)
        dstart = pos + hlen
        dend = len(data) if size < 0 else min(dstart + size, end)
        yield eid, dstart, dend
        if size < 0:
            break
        pos = dstart + size


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f:
        head = f.read(2 * 1024 * 1024)
    eid, size, hlen = parse_header(head, 0)
    assert eid == 0x1A45DFA3, "not EBML"
    ebml_end = hlen + size
    eid, size, hlen = parse_header(head, ebml_end)
    assert eid == 0x18538067, "no Segment"
    seg_data_start = ebml_end + hlen
    cues_pos = None
    for teid, tds, tde in children(head, seg_data_start, len(head)):
        if teid == 0x114D9B74:
            for seid, sds, sde in children(head, tds, tde):
                if seid == 0x4DBB:
                    seek_id = None
                    seek_pos = None
                    for feid, fds, fde in children(head, sds, sde):
                        if feid == 0x53AB:
                            seek_id = int.from_bytes(head[fds:fde], "big")
                        elif feid == 0x53AC:
                            seek_pos = int.from_bytes(head[fds:fde], "big")
                    if seek_id == 0x1C53BB6B and seek_pos is not None:
                        cues_pos = seek_pos
                        break
        if cues_pos is not None:
            break
    if cues_pos is None:
        raise SystemExit("no Cues entry in SeekHead")
    cut = seg_data_start + cues_pos
    with open(src, "rb") as f:
        data = f.read(cut)
    assert len(data) == cut
    with open(dst, "wb") as f:
        f.write(data)
    print("strip_cues: cut at %d (cues_pos=%d)" % (cut, cues_pos))


if __name__ == "__main__":
    main()
