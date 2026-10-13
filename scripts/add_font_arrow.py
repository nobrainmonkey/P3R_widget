#!/usr/bin/env python3
"""Adds a right arrow (U+2192) to the bundled Barlow Condensed fonts (SIL OFL 1.1).

The widget's tag chip draws 「RANK 4→5」 and 「魅力 +4 → Lv.2」 in Barlow, which has no arrow: every
Android version then borrowed a different fallback glyph (a full-size one on 12+, a small low one on
8-11). This draws the arrow in Barlow's own weight instead: a bar as thick as its minus sign on the
minus sign's axis, and a 45-degree head as tall as its plus sign.

Standard library only. Rewrites glyf/loca/hmtx/hhea/maxp/cmap/post (and drops DSIG, whose
signature no longer matches); does nothing when the font already maps U+2192.

    python3 scripts/add_font_arrow.py android/app/src/main/res/font/barlow_condensed_bold.ttf ...
"""
import math
import struct
import sys

ARROW = 0x2192
ARROW_NAME = b'arrowright'
MINUS = 0x2212
PLUS = 0x2B
SIDE_BEARING = 30
HEAD_REACH = 0.9  # head half-height relative to the plus sign's half-height
ARROW_LENGTH = 3.2  # bar plus head, in head half-heights (about 1.3 digits)


def read_tables(data):
    count = struct.unpack('>H', data[4:6])[0]
    tables = {}
    for i in range(count):
        tag, _, offset, length = struct.unpack('>4sIII', data[12 + 16 * i:28 + 16 * i])
        tables[tag.decode('latin1')] = bytearray(data[offset:offset + length])
    return tables


def cmap_mapping(cmap):
    """Unicode BMP mapping of the first format 4 subtable."""
    count = struct.unpack('>H', cmap[2:4])[0]
    for i in range(count):
        _, _, offset = struct.unpack('>HHI', cmap[4 + 8 * i:12 + 8 * i])
        if struct.unpack('>H', cmap[offset:offset + 2])[0] != 4:
            continue
        p = offset
        seg2 = struct.unpack('>H', cmap[p + 6:p + 8])[0]
        seg = seg2 // 2
        ends = struct.unpack('>%dH' % seg, cmap[p + 14:p + 14 + seg2])
        starts = struct.unpack('>%dH' % seg, cmap[p + 16 + seg2:p + 16 + 2 * seg2])
        deltas = struct.unpack('>%dh' % seg, cmap[p + 16 + 2 * seg2:p + 16 + 3 * seg2])
        ranges_at = p + 16 + 3 * seg2
        ranges = struct.unpack('>%dH' % seg, cmap[ranges_at:ranges_at + seg2])
        mapping = {}
        for s in range(seg):
            for ch in range(starts[s], ends[s] + 1):
                if ch == 0xFFFF:
                    continue
                if ranges[s] == 0:
                    glyph = (ch + deltas[s]) & 0xFFFF
                else:
                    at = ranges_at + 2 * s + ranges[s] + 2 * (ch - starts[s])
                    glyph = struct.unpack('>H', cmap[at:at + 2])[0]
                    if glyph:
                        glyph = (glyph + deltas[s]) & 0xFFFF
                if glyph:
                    mapping[ch] = glyph
        return mapping
    raise SystemExit('no format 4 cmap')


def format4(mapping):
    """A format 4 subtable: one delta-coded segment per run of consecutive codes and glyphs."""
    segments = []
    for ch in sorted(mapping):
        glyph = mapping[ch]
        if segments and segments[-1][1] == ch - 1 and segments[-1][2] + (ch - segments[-1][0]) == glyph:
            segments[-1][1] = ch
        else:
            segments.append([ch, ch, glyph])
    segments.append([0xFFFF, 0xFFFF, 1])
    seg = len(segments)
    power = 2 ** int(math.floor(math.log2(seg)))
    search, selector = 2 * power, int(math.log2(power))
    ends = [s[1] for s in segments]
    starts = [s[0] for s in segments]
    deltas = [((s[2] - s[0]) + 0x10000) % 0x10000 if s[0] != 0xFFFF else 1 for s in segments]
    body = struct.pack('>%dH' % seg, *ends) + b'\0\0' + struct.pack('>%dH' % seg, *starts)
    body += struct.pack('>%dH' % seg, *deltas) + struct.pack('>%dH' % seg, *([0] * seg))
    length = 14 + len(body)
    return struct.pack('>7H', 4, length, 0, 2 * seg, search, selector, 2 * seg - search) + body


def glyph_bbox(tables, glyph, short_loca):
    loca, glyf = tables['loca'], tables['glyf']
    if short_loca:
        start = struct.unpack('>H', loca[2 * glyph:2 * glyph + 2])[0] * 2
    else:
        start = struct.unpack('>I', loca[4 * glyph:4 * glyph + 4])[0]
    return struct.unpack('>hhhh', glyf[start + 2:start + 10])


def arrow_glyph(minus_box, plus_box):
    """One contour, clockwise: bar from the left, then the head's upper arm, tip, lower arm."""
    _, low, _, high = minus_box
    thickness = high - low
    axis = (high + low) / 2.0
    half = thickness / 2.0
    reach = round((plus_box[3] - plus_box[1]) / 2.0 * HEAD_REACH)
    inner = thickness * math.sqrt(2)  # horizontal width of a 45-degree arm
    left = SIDE_BEARING
    tip = left + round(ARROW_LENGTH * reach)
    points = [
        (left, axis - half), (left, axis + half),
        (tip - inner - half, axis + half), (tip - inner - reach, axis + reach), (tip - reach, axis + reach),
        (tip, axis),
        (tip - reach, axis - reach), (tip - inner - reach, axis - reach), (tip - inner - half, axis - half),
    ]
    points = [(int(round(x)), int(round(y))) for x, y in points]
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    data = struct.pack('>hhhhh', 1, min(xs), min(ys), max(xs), max(ys))
    data += struct.pack('>HH', len(points) - 1, 0) + bytes([1] * len(points))
    last = 0
    for x in xs:
        data += struct.pack('>h', x - last)
        last = x
    last = 0
    for y in ys:
        data += struct.pack('>h', y - last)
        last = y
    if len(data) % 2:
        data += b'\0'
    return data, tip + SIDE_BEARING, min(xs), len(points)


def checksum(data):
    padded = data + b'\0' * (-len(data) % 4)
    return sum(struct.unpack('>%dI' % (len(padded) // 4), padded)) & 0xFFFFFFFF


def write_font(tables):
    """The font file with head.checkSumAdjustment computed as the spec says (zero while summing)."""
    tables['head'][8:12] = b'\0\0\0\0'
    tags = sorted(tables)
    count = len(tags)
    power = 2 ** int(math.floor(math.log2(count)))
    header = struct.pack('>IHHHH', 0x00010000, count, 16 * power, int(math.log2(power)), 16 * count - 16 * power)
    offset = 12 + 16 * count
    directory = b''
    body = b''
    head_at = 0
    for tag in tags:
        data = bytes(tables[tag])
        if tag == 'head':
            head_at = offset
        directory += struct.pack('>4sIII', tag.encode('latin1'), checksum(data), offset, len(data))
        padded = data + b'\0' * (-len(data) % 4)
        body += padded
        offset += len(padded)
    font = bytearray(header + directory + body)
    adjustment = (0xB1B0AFBA - checksum(bytes(font))) & 0xFFFFFFFF
    font[head_at + 8:head_at + 12] = struct.pack('>I', adjustment)
    return bytes(font)


def patch(path):
    data = open(path, 'rb').read()
    tables = read_tables(data)
    mapping = cmap_mapping(tables['cmap'])
    if ARROW in mapping:
        print(path, 'already has U+2192')
        return
    head, maxp, hhea = tables['head'], tables['maxp'], tables['hhea']
    short_loca = struct.unpack('>h', head[50:52])[0] == 0
    glyphs = struct.unpack('>H', maxp[4:6])[0]
    metrics = struct.unpack('>H', hhea[34:36])[0]

    glyph, advance, lsb, point_count = arrow_glyph(glyph_bbox(tables, mapping[MINUS], short_loca),
                                                   glyph_bbox(tables, mapping[PLUS], short_loca))
    new = glyphs  # id of the arrow glyph

    # glyf + loca
    glyf_end = len(tables['glyf'])
    tables['glyf'] += glyph
    if short_loca:
        if (glyf_end + len(glyph)) // 2 > 0xFFFF:
            raise SystemExit('glyf too large for short loca')
        tables['loca'] = tables['loca'][:2 * (glyphs + 1)] + struct.pack('>H', (glyf_end + len(glyph)) // 2)
    else:
        tables['loca'] = tables['loca'][:4 * (glyphs + 1)] + struct.pack('>I', glyf_end + len(glyph))

    # hmtx/hhea: full metrics for every glyph, the arrow last
    hmtx = tables['hmtx']
    longs = [struct.unpack('>Hh', hmtx[4 * i:4 * i + 4]) for i in range(metrics)]
    tail = [struct.unpack('>h', hmtx[4 * metrics + 2 * j:4 * metrics + 2 * j + 2])[0] for j in range(glyphs - metrics)]
    longs += [(longs[-1][0], side) for side in tail]
    longs.append((advance, lsb))
    tables['hmtx'] = bytearray(b''.join(struct.pack('>Hh', a, s) for a, s in longs))
    hhea[34:36] = struct.pack('>H', glyphs + 1)
    hhea[10:12] = struct.pack('>H', max(struct.unpack('>H', hhea[10:12])[0], advance))

    # maxp
    maxp[4:6] = struct.pack('>H', glyphs + 1)
    if struct.unpack('>I', maxp[0:4])[0] == 0x00010000:
        maxp[6:8] = struct.pack('>H', max(struct.unpack('>H', maxp[6:8])[0], point_count))

    # cmap: every encoding record points at one rebuilt format 4 subtable
    mapping[ARROW] = new
    cmap = tables['cmap']
    records = [struct.unpack('>HH', cmap[4 + 8 * i:8 + 8 * i]) for i in range(struct.unpack('>H', cmap[2:4])[0])]
    sub = format4(mapping)
    out = struct.pack('>HH', 0, len(records))
    for platform, encoding in records:
        out += struct.pack('>HHI', platform, encoding, 4 + 8 * len(records))
    tables['cmap'] = bytearray(out + sub)

    # post: name the glyph (format 2), or nothing to do (format 3)
    post = tables['post']
    if struct.unpack('>I', post[0:4])[0] == 0x00020000:
        count = struct.unpack('>H', post[32:34])[0]
        indices = list(struct.unpack('>%dH' % count, post[34:34 + 2 * count]))
        names = bytes(post[34 + 2 * count:])
        custom, p = 0, 0
        while p < len(names):
            p += 1 + names[p]
            custom += 1
        indices.append(258 + custom)
        tables['post'] = bytearray(post[:32] + struct.pack('>H', count + 1) + struct.pack('>%dH' % (count + 1), *indices)
                                   + names + bytes([len(ARROW_NAME)]) + ARROW_NAME)

    # OS/2: the Arrows block (bit 37)
    os2 = tables['OS/2']
    os2[46:50] = struct.pack('>I', struct.unpack('>I', os2[46:50])[0] | (1 << 5))

    tables.pop('DSIG', None)
    open(path, 'wb').write(write_font(tables))
    print(path, 'U+2192 -> glyph', new, 'advance', advance)


if __name__ == '__main__':
    for font in sys.argv[1:]:
        patch(font)
