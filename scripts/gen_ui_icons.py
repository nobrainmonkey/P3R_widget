"""Generates the solid, angular P3R-style ui_ic_* vector drawables.

Usage: python3 scripts/gen_ui_icons.py android/app/src/main/res/drawable

Every icon is a list of paths; a path is a list of (polygon, hole) pairs. Outer polygons are
written counter-clockwise and holes clockwise so the default nonZero fill unions overlapping
pieces and punches holes. Tab icons get a forward slant (skewX about 10 degrees).
"""
import math
import os
import sys

OUT = sys.argv[1]
K10 = math.tan(math.radians(10))


def area(p):
    a = 0.0
    for i in range(len(p)):
        x1, y1 = p[i]
        x2, y2 = p[(i + 1) % len(p)]
        a += x1 * y2 - x2 * y1
    return a / 2


def orient(p, hole):
    # Screen coordinates (y down): positive shoelace area = clockwise on screen.
    cw = area(p) > 0
    if hole != cw:
        return p
    return list(reversed(p))


def skew(p, k=K10, cy=12.0, dx=0.0):
    return [(x + (cy - y) * k + dx, y) for x, y in p]


def move(p, dx, dy):
    return [(x + dx, y + dy) for x, y in p]


def rect(x1, y1, x2, y2):
    return [(x1, y1), (x2, y1), (x2, y2), (x1, y2)]


def para(x1, y1, x2, y2, s):
    """Parallelogram: rect whose top edge is shifted right by s (bottom left by 0)."""
    return [(x1 + s, y1), (x2 + s, y1), (x2, y2), (x1, y2)]


def ngon(cx, cy, r, n, start_deg=-90):
    return [(cx + r * math.cos(math.radians(start_deg + 360 * i / n)),
             cy + r * math.sin(math.radians(start_deg + 360 * i / n))) for i in range(n)]


def rot(p, deg, cx, cy):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in p]


def cross(cx, cy, arm, half):
    """Plus outline (12 vertices)."""
    L, h = arm, half
    pts = [(h, -L), (h, -h), (L, -h), (L, h), (h, h), (h, L), (-h, L), (-h, h), (-L, h), (-L, -h), (-h, -h), (-h, -L)]
    return [(cx + x, cy + y) for x, y in pts]


def stroke(pts, w):
    """Open polyline as a polygon: miter joins, butt caps."""
    h = w / 2
    n = len(pts)
    dirs = []
    for i in range(n - 1):
        dx, dy = pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]
        l = math.hypot(dx, dy)
        dirs.append((dx / l, dy / l))
    left, right = [], []
    for i in range(n):
        if i == 0:
            d = dirs[0]
            nx, ny = -d[1], d[0]
            left.append((pts[i][0] + nx * h, pts[i][1] + ny * h))
            right.append((pts[i][0] - nx * h, pts[i][1] - ny * h))
        elif i == n - 1:
            d = dirs[-1]
            nx, ny = -d[1], d[0]
            left.append((pts[i][0] + nx * h, pts[i][1] + ny * h))
            right.append((pts[i][0] - nx * h, pts[i][1] - ny * h))
        else:
            d0, d1 = dirs[i - 1], dirs[i]
            n0 = (-d0[1], d0[0])
            n1 = (-d1[1], d1[0])
            mx, my = n0[0] + n1[0], n0[1] + n1[1]
            ml = math.hypot(mx, my)
            mx, my = mx / ml, my / ml
            cosv = mx * n0[0] + my * n0[1]
            m = h / cosv
            left.append((pts[i][0] + mx * m, pts[i][1] + my * m))
            right.append((pts[i][0] - mx * m, pts[i][1] - my * m))
    return left + list(reversed(right))


def arc_ring(cx, cy, r_in, r_out, a0, a1, step=30):
    """Thick polygonal arc from angle a0 to a1 (degrees, screen: clockwise from 3 o'clock)."""
    angs = [a0]
    a = (math.floor(a0 / step) + 1) * step if a1 > a0 else (math.ceil(a0 / step) - 1) * step
    if a1 > a0:
        while a < a1:
            angs.append(a)
            a += step
    else:
        while a > a1:
            angs.append(a)
            a -= step
    angs.append(a1)
    outer = [(cx + r_out * math.cos(math.radians(t)), cy + r_out * math.sin(math.radians(t))) for t in angs]
    inner = [(cx + r_in * math.cos(math.radians(t)), cy + r_in * math.sin(math.radians(t))) for t in angs]
    return outer + list(reversed(inner))


def arrow_head(cx, cy, r_in, r_out, a, direction, length, spread):
    """Triangle at angle a on a ring, pointing along the tangent (direction +1 = clockwise)."""
    t = math.radians(a)
    rx, ry = math.cos(t), math.sin(t)
    tx, ty = -ry * direction, rx * direction
    rm = (r_in + r_out) / 2
    bx, by = cx + rm * rx, cy + rm * ry
    half = (r_out - r_in) / 2 + spread
    p1 = (bx + rx * half, by + ry * half)
    p2 = (bx - rx * half, by - ry * half)
    tip = (bx + tx * length, by + ty * length)
    return [p1, tip, p2]


def clip(poly, a, b, c):
    """Keeps the part of poly where a*x + b*y + c <= 0 (Sutherland-Hodgman)."""
    out = []
    n = len(poly)
    for i in range(n):
        p, q = poly[i], poly[(i + 1) % n]
        fp, fq = a * p[0] + b * p[1] + c, a * q[0] + b * q[1] + c
        if fp <= 0:
            out.append(p)
        if (fp <= 0) != (fq <= 0):
            t = fp / (fp - fq)
            out.append((p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))
    return out


def band(poly, y_left, y_right, gap):
    """Splits poly along the line from (0, y_left) to (24, y_right), leaving a gap; returns (above, below)."""
    slope = (y_right - y_left) / 24.0
    # line: y = y_left + slope * x  ->  slope*x - y + y_left = 0
    above = clip(poly, -slope, 1, -(y_left - gap / 2))     # y <= line - gap/2
    below = clip(poly, slope, -1, (y_left + gap / 2))      # y >= line + gap/2
    return above, below


def cut(poly, p0, p1, gap):
    """Splits poly by the line p0->p1 leaving a gap; returns (left of the line, right of the line)."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    l = math.hypot(dx, dy)
    nx, ny = -dy / l, dx / l
    c0 = -(nx * p0[0] + ny * p0[1])
    g = gap / 2
    side_a = clip(poly, nx, ny, c0 + g)      # n.p + c0 <= -g
    side_b = clip(poly, -nx, -ny, -c0 + g)   # n.p + c0 >= g
    return side_a, side_b


def inside(pt, poly):
    x, y = pt
    c = False
    for i in range(len(poly)):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % len(poly)]
        if (y1 > y) != (y2 > y):
            xi = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if xi > x:
                c = not c
    return c


def check(name, pieces):
    outers = [p for p, h in pieces if not h]
    for p, h in pieces:
        if h:
            for v in p:
                if not any(inside(v, o) for o in outers):
                    raise SystemExit("%s: hole vertex %s outside its fill" % (name, v))


def fmt(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_data(pieces):
    out = []
    for poly, hole in pieces:
        p = orient(poly, hole)
        out.append("M" + " L".join(fmt(x) + "," + fmt(y) for x, y in p) + " Z")
    return " ".join(out)


def write(name, comment, paths):
    body = []
    for pieces in paths:
        check(name, pieces)
        body.append('    <path android:fillColor="#FFFFFFFF" android:pathData="%s" />' % path_data(pieces))
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- UI module icon (original geometry): %s Solid, angular P3R glyph; tinted in code. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp"\n'
           '    android:viewportWidth="24" android:viewportHeight="24">\n'
           '%s\n'
           '</vector>\n') % (comment, "\n".join(body))
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write(xml)


def solid(*polys):
    return [(p, False) for p in polys]


def S(pieces, k=K10, dx=0.0):
    return [(skew(p, k, 12.0, dx), h) for p, h in pieces]


ICONS = {}


def icon(name, comment):
    def deco(fn):
        ICONS[name] = (comment, fn)
        return fn
    return deco


# ---------------------------------------------------------------- arrows and marks

def chevron_left(tip_x, t, half=7.0):
    return [(tip_x, 12), (tip_x + half, 12 - half), (tip_x + half + t, 12 - half), (tip_x + t, 12),
            (tip_x + half + t, 12 + half), (tip_x + half, 12 + half)]


@icon("ui_ic_back", "a sharp chevron pointing back.")
def _():
    return [solid(chevron_left(6.2, 5.0))]


@icon("ui_ic_chevron", "a sharp chevron pointing forward.")
def _():
    p = [(24 - x, y) for x, y in chevron_left(6.2, 5.0)]
    return [solid(p)]


@icon("ui_ic_up", "a sharp chevron pointing up.")
def _():
    p = [(y, x) for x, y in chevron_left(6.2, 5.0)]
    return [solid(p)]


@icon("ui_ic_down", "a sharp chevron pointing down.")
def _():
    p = [(y, 24 - x) for x, y in chevron_left(6.2, 5.0)]
    return [solid(p)]


@icon("ui_ic_close", "a cross with square-cut arms.")
def _():
    return [solid(rot(cross(12, 12, 9.2, 1.7), 45, 12, 12))]


@icon("ui_ic_plus", "a plus with square-cut arms.")
def _():
    return [solid(cross(12, 12, 8.0, 1.6))]


@icon("ui_ic_minus", "a bar.")
def _():
    return [solid(rect(4, 10.4, 20, 13.6))]


@icon("ui_ic_check", "a thick angular check mark.")
def _():
    return [solid(stroke([(3.6, 11.8), (9.4, 17.6), (20.6, 5.8)], 3.6))]


@icon("ui_ic_star", "a filled five-point star.")
def _():
    pts = []
    for i in range(10):
        r = 10.2 if i % 2 == 0 else 4.1
        a = math.radians(-90 + 36 * i)
        pts.append((12 + r * math.cos(a), 13 + r * math.sin(a)))
    return [solid(pts)]


# ---------------------------------------------------------------- objects

@icon("ui_ic_backspace", "a backspace key with a cross cut out.")
def _():
    key = [(1.8, 12), (7.6, 4.5), (22, 4.5), (22, 19.5), (7.6, 19.5)]
    x = rot(cross(14.4, 12, 4.4, 1.25), 45, 14.4, 12)
    return [[(key, False), (x, True)]]


@icon("ui_ic_backup", "an arrow down into a tray (export / restore).")
def _():
    arrow = [(10.3, 2.5), (13.7, 2.5), (13.7, 8.6), (18.4, 8.6), (12, 15.4), (5.6, 8.6), (10.3, 8.6)]
    tray = [(2.6, 12.6), (5.8, 12.6), (5.8, 18), (18.2, 18), (18.2, 12.6), (21.4, 12.6), (21.4, 21.2), (2.6, 21.2)]
    return [solid(arrow, tray)]


@icon("ui_ic_edit", "a pencil.")
def _():
    # Along the pencil axis from the tip (4,20) towards (20,4).
    ax, ay = 3.4, 20.6
    d = (1 / math.sqrt(2), -1 / math.sqrt(2))
    nrm = (d[1] * -1, d[0])  # perpendicular

    def P(u, v):
        return (ax + d[0] * u + nrm[0] * v, ay + d[1] * u + nrm[1] * v)
    w = 2.75
    tip = [P(0, 0), P(5.0, -w), P(5.0, w)]
    body = [P(5.9, -w), P(16.6, -w), P(16.6, w), P(5.9, w)]
    cap = [P(17.8, -w), P(21.4, -w), P(21.4, w), P(17.8, w)]
    return [solid(tip, body, cap)]


@icon("ui_ic_episode", "a clapperboard for 羁绊剧情.")
def _():
    body = rect(3.2, 10.4, 20.8, 21)
    clapper = [(2.6, 9.0), (2.0, 5.4), (19.8, 2.0), (20.4, 5.6)]
    seg1, rest = cut(clapper, (7.6, 9.0), (9.6, 2.0), 1.5)
    seg2, seg3 = cut(rest, (13.0, 9.0), (15.0, 2.0), 1.5)
    line1 = rect(6.6, 13.6, 17.4, 15.6)
    line2 = rect(6.6, 17.2, 13.4, 19.0)
    return [solid(seg1, seg2, seg3), [(body, False), (line1, True), (line2, True)]]


@icon("ui_ic_filter", "a funnel.")
def _():
    return [solid([(2.6, 4), (21.4, 4), (14.2, 12.6), (14.2, 19.2), (9.8, 21.6), (9.8, 12.6)])]


@icon("ui_ic_free", "an hourglass for 自由活动.")
def _():
    top = rect(5.2, 2.2, 18.8, 4.6)
    bot = rect(5.2, 19.4, 18.8, 21.8)
    upper = [(6.8, 5.6), (17.2, 5.6), (13.0, 12), (11.0, 12)]
    lower = [(11.0, 12), (13.0, 12), (17.2, 18.4), (6.8, 18.4)]
    upper_hole = [(9.4, 7.4), (14.6, 7.4), (12, 10.6)]
    return [[(top, False), (bot, False), (upper, False), (upper_hole, True), (lower, False)]]


@icon("ui_ic_history", "a clock face with its hands cut out.")
def _():
    face = ngon(12, 12, 10, 12, -90)
    hands = stroke([(12, 4.8), (12, 12.6), (16.8, 15.6)], 2.6)
    return [[(face, False), (hands, True)]]


@icon("ui_ic_info", "an angular plate with an i cut out.")
def _():
    plate = ngon(12, 12, 10.2, 8, -90 + 22.5)
    dot = rect(10.6, 5.4, 13.4, 8.2)
    stem = [(9.6, 10), (13.4, 10), (13.4, 16.2), (14.6, 16.2), (14.6, 18.4), (9.4, 18.4), (9.4, 16.2), (10.6, 16.2), (10.6, 12.2), (9.6, 12.2)]
    return [[(plate, False), (dot, True), (stem, True)]]


def yen_holes():
    # Non-overlapping hole pieces for a yen sign.
    left = [(6.9, 4.6), (9.4, 4.6), (12, 8.9), (14.6, 4.6), (17.1, 4.6), (13.2, 11.0), (13.2, 11.6), (16.4, 11.6),
            (16.4, 13.4), (13.2, 13.4), (13.2, 14.6), (16.4, 14.6), (16.4, 16.4), (13.2, 16.4), (13.2, 19.6),
            (10.8, 19.6), (10.8, 16.4), (7.6, 16.4), (7.6, 14.6), (10.8, 14.6), (10.8, 13.4), (7.6, 13.4),
            (7.6, 11.6), (10.8, 11.6), (10.8, 11.0)]
    return left


@icon("ui_ic_job", "a coin with a yen sign cut out for 打工.")
def _():
    coin = ngon(12, 12, 10.4, 8, -90 + 22.5)
    return [[(coin, False), (yen_holes(), True)]]


@icon("ui_ic_locked", "a padlock with a keyhole cut out.")
def _():
    body = rect(4.2, 10.2, 19.8, 21.6)
    shackle = [(6.6, 10.2), (6.6, 6.0), (9.4, 2.4), (14.6, 2.4), (17.4, 6.0), (17.4, 10.2),
               (14.8, 10.2), (14.8, 6.8), (13.4, 4.9), (10.6, 4.9), (9.2, 6.8), (9.2, 10.2)]
    keyhole = [(10.5, 12.8), (13.5, 12.8), (13.5, 15.6), (13.0, 19.0), (11.0, 19.0), (10.5, 15.6)]
    return [[(body, False), (keyhole, True)], solid(shackle)]


@icon("ui_ic_unlock", "a key for 解锁社群.")
def _():
    bow = ngon(7.4, 12, 5.6, 6, 0)
    hole = rect(6.0, 10.6, 8.8, 13.4)
    shaft = rect(12.2, 10.6, 22.2, 13.4)
    t1 = rect(16.2, 13.4, 18.4, 17.2)
    t2 = rect(19.8, 13.4, 22.2, 16.0)
    return [[(bow, False), (hole, True)], solid(shaft, t1, t2)]


@icon("ui_ic_note", "a folded note with a heart cut out.")
def _():
    page = [(4.6, 2.4), (13.0, 2.4), (13.0, 9.2), (19.4, 9.2), (19.4, 21.6), (4.6, 21.6)]
    fold = [(14.4, 2.6), (19.4, 7.6), (14.4, 7.6)]
    heart = [(12, 19.0), (7.8, 14.8), (7.8, 12.8), (9.4, 11.2), (10.8, 11.2), (12, 12.4), (13.2, 11.2), (14.6, 11.2),
             (16.2, 12.8), (16.2, 14.8)]
    return [[(page, False), (heart, True)], solid(fold)]


@icon("ui_ic_pin", "a location pin with a diamond cut out.")
def _():
    pin = [(8.4, 2), (15.6, 2), (19.4, 6.2), (19.4, 10.6), (12, 22.4), (4.6, 10.6), (4.6, 6.2)]
    hole = [(12, 5.6), (14.6, 8.4), (12, 11.2), (9.4, 8.4)]
    return [[(pin, False), (hole, True)]]


@icon("ui_ic_replan", "one circular arrow round a start mark, \"from the start again\" (从头重新安排); ui_ic_sync's two arrows are 同步现状's.")
def _():
    cx, cy = 12, 12.8
    ring = arc_ring(cx, cy, 6.6, 9.2, 180, -90, 30)
    head = arrow_head(cx, cy, 6.6, 9.2, 270, -1, 4.2, 2.0)
    play = [(10.0, 9.4), (15.6, 12.8), (10.0, 16.2)]
    return [solid(ring, head), solid(play)]


@icon("ui_ic_sync", "two circular arrows for 同步现状.")
def _():
    cx, cy = 12, 12
    a = arc_ring(cx, cy, 6.6, 9.2, 190, 318, 30)
    ha = arrow_head(cx, cy, 6.6, 9.2, 318, 1, 4.2, 2.0)
    b = arc_ring(cx, cy, 6.6, 9.2, 10, 138, 30)
    hb = arrow_head(cx, cy, 6.6, 9.2, 138, 1, 4.2, 2.0)
    return [solid(a, ha, b, hb)]


@icon("ui_ic_settings", "three sliders with slanted knobs.")
def _():
    bars = []
    knobs = []
    for x, y in ((8.6, 5.2), (15.4, 12), (8.0, 18.8)):
        bars.append(rect(2.4, y - 0.85, 21.6, y + 0.85))
        knobs.append(para(x - 2.2, y - 3.1, x + 2.2, y + 3.1, 1.1))
    return [solid(*(bars + knobs))]


@icon("ui_ic_social", "a slanted card with a spark cut out (社群).")
def _():
    card = [(7.4, 2.2), (20.8, 2.2), (16.6, 21.8), (3.2, 21.8)]
    star = []
    for i in range(8):
        r = 5.6 if i % 2 == 0 else 1.5
        a = math.radians(-90 + 45 * i)
        star.append((12 + r * math.cos(a), 12 + r * math.sin(a)))
    return [[(card, False), (star, True)]]


@icon("ui_ic_stat", "a rising arrow over level bars (个人能力值).")
def _():
    head = [(12, 2), (21, 11), (3, 11)]
    b1 = rect(8.4, 12.4, 15.6, 15.8)
    b2 = rect(8.4, 17.2, 15.6, 21.8)
    return [solid(head, b1, b2)]


@icon("ui_ic_tower", "the Tartarus tower: a spire with two pinnacles and an arched gate.")
def _():
    t = [(12, 0.6), (13.2, 6.4), (15.6, 4.4), (15.0, 10.0), (16.2, 10.8), (17.8, 22.8), (13.5, 22.8), (13.5, 18.8),
         (12, 16.8), (10.5, 18.8), (10.5, 22.8), (6.2, 22.8), (7.8, 10.8), (9.0, 10.0), (8.4, 4.4), (10.8, 6.4)]
    return [solid(t)]


@icon("ui_ic_widget", "three tiles and a plus (add a home-screen widget).")
def _():
    t1 = rect(3, 3, 10.6, 10.6)
    t2 = rect(13.4, 3, 21, 10.6)
    t3 = rect(3, 13.4, 10.6, 21)
    plus = cross(17.2, 17.2, 4.0, 1.4)
    return [solid(t1, t2, t3, plus)]


# ---------------------------------------------------------------- tab icons (slanted)

@icon("ui_ic_tab_today", "a calendar page with a slanted date block (今天).")
def _():
    header = rect(4, 4.4, 20, 8.6)
    rings = [rect(7.2, 2, 9.6, 6), rect(14.4, 2, 16.8, 6)]
    body = rect(4, 9.8, 20, 21.4)
    date = para(9.0, 12.4, 15.6, 18.6, 1.8)
    return [S(solid(header, *rings)), S([(body, False), (date, True)])]


@icon("ui_ic_tab_schedule", "three slanted bars, a list (日程).")
def _():
    pieces = []
    for i, (y, end) in enumerate(((5.8, 20.2), (12, 20.2), (18.2, 16.4))):
        pieces.append(para(3.0, y - 1.8, 6.0, y + 1.8, 0.0))
        pieces.append(para(7.6, y - 1.8, end, y + 1.8, 0.0))
    return [S(solid(*pieces))]


@icon("ui_ic_tab_links", "a tarot card with an inner frame and a star (社群).")
def _():
    card = rect(5.2, 2, 18.8, 22)
    frame_o = rect(7.0, 3.8, 17.0, 20.2)
    frame_i = rect(8.0, 4.8, 16.0, 19.2)
    star = []
    for i in range(8):
        rx, ry = (2.8, 5.2) if i % 2 == 0 else (1.0, 1.0)
        a = math.radians(-90 + 45 * i)
        star.append((12 + rx * math.cos(a), 12 + ry * math.sin(a)))
    return [S([(card, False), (frame_o, True)]), S([(frame_i, False), (star, True)])]


@icon("ui_ic_tab_tips", "a slanted note card with an exclamation mark (提示).")
def _():
    card = [(3.6, 3), (16.4, 3), (20.4, 7), (20.4, 21), (3.6, 21)]
    stem = [(10.4, 6.4), (13.6, 6.4), (13.0, 14.4), (11.0, 14.4)]
    dot = rect(10.8, 16.0, 13.2, 18.4)
    return [S([(card, False), (stem, True), (dot, True)])]


@icon("ui_ic_mask", "the masquerade mask for 全书 and Persona reminders.")
def _():
    left = [(12, 8.6), (8.6, 6.6), (4.8, 6.4), (1.4, 4.0), (2.2, 9.8), (4.4, 14.6), (8.2, 17.0), (10.8, 16.0), (12, 13.6)]
    right = [(24 - x, y) for x, y in reversed(left[1:-1])]
    outline = left + right
    eye_l = [(4.6, 9.4), (8.6, 9.8), (10.4, 11.4), (7.2, 12.6)]
    eye_r = [(24 - x, y) for x, y in eye_l]
    outline, eye_l, eye_r = (move(q, 0, 1.2) for q in (outline, eye_l, eye_r))
    return [S([(outline, False), (eye_l, True), (eye_r, True)])]


@icon("ui_ic_tab_me", "a head-and-shoulders figure cut angularly (我的).")
def _():
    head = [(8.6, 3.6), (11.2, 1.8), (14.6, 2.4), (16.4, 5.0), (16.0, 8.8), (13.2, 11.8), (10.6, 11.8), (8.8, 10.0), (7.0, 10.4), (7.4, 6.4)]
    body = [(3.6, 21.8), (4.8, 16.4), (8.4, 13.4), (10.4, 13.4), (12, 16.4), (13.6, 13.4), (15.6, 13.4), (19.2, 16.4), (20.4, 21.8)]
    return [S(solid(head)), S(solid(body))]


SLANTED = {"ui_ic_tab_today", "ui_ic_tab_schedule", "ui_ic_tab_links", "ui_ic_tab_tips", "ui_ic_mask", "ui_ic_tab_me"}

for name, (comment, fn) in ICONS.items():
    paths = [p for p in fn() if p]
    xs = [x for pieces in paths for poly, _ in pieces for x, _y in poly]
    ys = [y for pieces in paths for poly, _ in pieces for _x, y in poly]
    if name in SLANTED:
        # the slant moves the glyph sideways; centre it again on the viewport
        dx = 12 - (min(xs) + max(xs)) / 2
        paths = [[(move(poly, dx, 0), h) for poly, h in pieces] for pieces in paths]
        xs = [x + dx for x in xs]
    if min(xs) < 0.2 or max(xs) > 23.8 or min(ys) < 0.2 or max(ys) > 23.8:
        raise SystemExit("%s out of bounds: x %.2f..%.2f y %.2f..%.2f" % (name, min(xs), max(xs), min(ys), max(ys)))
    print("%-20s x %5.2f..%5.2f  y %5.2f..%5.2f" % (name, min(xs), max(xs), min(ys), max(ys)))
    write(name, comment, paths)
print(len(ICONS), "icons")
