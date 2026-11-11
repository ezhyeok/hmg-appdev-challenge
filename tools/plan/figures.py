"""Figures for the planning document: SVG written to docs/plan/assets/, PNG rendered at 2x.

    python3 -I tools/plan/figures.py            # all figures
    python3 -I tools/plan/figures.py gauge      # one figure

Rendering needs `rsvg-convert` and the Pretendard font (falls back to Noto Sans CJK KR).
Colors: Pleos informative_positive as the single accent, Pleos grayscale for everything
else; orange/red appear only where they carry the gauge-stage meaning (SOON / NOW).
"""
import os
import subprocess
import sys

sys.dont_write_bytecode = True   # keep tools/plan free of __pycache__
from xml.sax.saxutils import escape

FONT = "Pretendard, 'Noto Sans CJK KR', NanumGothic, sans-serif"
INK, INK2, INK3, MUTED = "#131417", "#44464E", "#686A72", "#A4A8AE"
LINE, LINE2, SURF, SURF2, WHITE = "#E0E1E6", "#C7C9CE", "#F7F8FA", "#EDEEF2", "#FFFFFF"
ACCENT, ACCENT_D, TINT, TINT2 = "#0064FF", "#0050CC", "#EAF2FF", "#CFE0FF"
ORANGE, RED, RED_TINT, ORANGE_TINT = "#FF8A00", "#FE3D16", "#FFE9E3", "#FFF1DE"
FILLED = "#44464E"            # Pleos button_filled_enabled (light)
BASIC = "#EDEEF0"             # Pleos button_basic_enabled (#131417 5% on white)


# ------------------------------------------------------------------ primitives

def tw(s, size, bold=False):
    """Approximate rendered width of s in px for Pretendard."""
    w = 0.0
    for ch in s:
        o = ord(ch)
        if 0xAC00 <= o <= 0xD7A3 or 0x3130 <= o <= 0x318F:
            w += 0.92
        elif ch in '“”‘’':
            w += 0.32
        elif ch in '·':
            w += 0.30
        elif ch == ' ':
            w += 0.26
        elif ch.isdigit():
            w += 0.58
        elif ch.isupper():
            w += 0.64
        elif ch.isalpha():
            w += 0.53
        elif o > 0x2000:
            w += 0.9
        else:
            w += 0.34
    return w * size * (1.04 if bold else 1.0)


def num_w(value, size):
    """Width of a heavy-weight number (Pretendard ExtraBold digits are wide)."""
    return sum(0.66 * size if ch.isdigit() else tw(ch, size, True) for ch in value)


def wrap(s, size, maxw, bold=False):
    lines, cur = [], ""
    for word in s.split(' '):
        cand = (cur + ' ' + word).strip()
        if cur and tw(cand, size, bold) > maxw:
            lines.append(cur)
            cur = word
        else:
            cur = cand
    if cur:
        lines.append(cur)
    return lines


def T(x, y, s, size=14, weight=400, fill=INK, anchor="start", extra=""):
    return ('<text x="%.1f" y="%.1f" font-size="%.1f" font-weight="%d" fill="%s" text-anchor="%s"%s>%s</text>'
            % (x, y, size, weight, fill, anchor, (' ' + extra) if extra else '', escape(s)))


def ML(x, y, lines, size=13, lh=None, weight=400, fill=INK, anchor="start"):
    lh = lh or size * 1.38
    return ''.join(T(x, y + i * lh, l, size, weight, fill, anchor) for i, l in enumerate(lines))


def R(x, y, w, h, rx=0, fill=WHITE, stroke=None, sw=1, dash=None, opacity=None):
    a = '<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="%.1f" fill="%s"' % (x, y, w, h, rx, fill)
    if stroke:
        a += ' stroke="%s" stroke-width="%.1f"' % (stroke, sw)
    if dash:
        a += ' stroke-dasharray="%s"' % dash
    if opacity is not None:
        a += ' opacity="%.2f"' % opacity
    return a + '/>'


def L(x1, y1, x2, y2, stroke=LINE2, sw=1, dash=None, marker=None, cap="butt"):
    a = ('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="%.1f" stroke-linecap="%s"'
         % (x1, y1, x2, y2, stroke, sw, cap))
    if dash:
        a += ' stroke-dasharray="%s"' % dash
    if marker:
        a += ' marker-end="url(#%s)"' % marker
    return a + '/>'


def C(cx, cy, r, fill=WHITE, stroke=None, sw=1):
    a = '<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"' % (cx, cy, r, fill)
    if stroke:
        a += ' stroke="%s" stroke-width="%.1f"' % (stroke, sw)
    return a + '/>'


def PATH(d, fill="none", stroke=INK, sw=1, dash=None, marker=None, cap="round", join="round"):
    a = '<path d="%s" fill="%s" stroke="%s" stroke-width="%.1f" stroke-linecap="%s" stroke-linejoin="%s"' % (
        d, fill, stroke, sw, cap, join)
    if dash:
        a += ' stroke-dasharray="%s"' % dash
    if marker:
        a += ' marker-end="url(#%s)"' % marker
    return a + '/>'


DEFS = ('<defs>'
        '<marker id="arrA" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>'
        '<marker id="arrG" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>'
        '<marker id="arrK" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>'
        '</defs>') % (ACCENT, INK3, INK2)


def svg(w, h, body):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
            'font-family="%s">%s<rect width="%d" height="%d" fill="#FFFFFF"/>%s</svg>'
            % (w, h, w, h, FONT, DEFS, w, h, ''.join(body)))


def chip(x, y, s, size=12, fill=TINT, color=ACCENT_D, h=24, weight=600, pad=10):
    w = tw(s, size, weight >= 600) + pad * 2
    return R(x, y, w, h, h / 2, fill) + T(x + w / 2, y + h / 2 + size * 0.36, s, size, weight, color, "middle"), w


def badge_num(cx, cy, n, r=11, fill=ACCENT):
    return C(cx, cy, r, fill) + T(cx, cy + 4.6, str(n), 13, 700, WHITE, "middle")


def wc_icon(cx, cy, r=12, fill=ACCENT):
    return C(cx, cy, r, fill) + T(cx, cy + 3.6, "WC", r * 0.78, 800, WHITE, "middle")


# ------------------------------------------------------------------ figure 1: human gauge

def gauge_card(x, y, w, h, label, value, unit, frac, tick_frac, reason, stage):
    b = [R(x, y, w, h, 14, WHITE, LINE2, 1.5)]
    b.append(T(x + 18, y + 30, label, 14.5, 700, INK2))
    b.append(T(x + w - 18, y + 30, stage, 12.5, 600, INK3, "end"))
    b.append(T(x + 18, y + 80, value, 46, 800, INK))
    b.append(T(x + 24 + num_w(value, 46), y + 80, unit, 19, 600, INK2))
    bx, by, bw = x + 18, y + 98, w - 36
    b.append(R(bx, by, bw, 12, 6, SURF2))
    b.append(R(bx, by, bw * frac, 12, 6, ACCENT))
    tx = bx + bw * tick_frac
    b.append(L(tx, by - 5, tx, by + 17, INK3, 2))
    b.append(T(x + 18, y + 136, reason, 13, 500, INK3))
    return ''.join(b)


def fig_gauge():
    W, H = 850, 400
    b = []
    # left: the car already has a range gauge
    b.append(R(0.75, 0.75, 232, H - 1.5, 18, SURF, LINE, 1.5))
    c, w_ = chip(16, 16, "차", fill=SURF2, color=INK2)
    b.append(c)
    b.append(T(16 + w_ + 8, 33, "주행가능거리", 14, 700, INK2))
    cx, cy, r = 116, 168, 70
    b.append(PATH("M %.1f %.1f A %d %d 0 0 1 %.1f %.1f" % (cx - r, cy, r, r, cx + r, cy), stroke=SURF2, sw=14))
    import math
    ang = math.radians(180 - 0.58 * 180)
    ex, ey = cx + r * math.cos(ang), cy - r * math.sin(ang)
    b.append(PATH("M %.1f %.1f A %d %d 0 0 1 %.1f %.1f" % (cx - r, cy, r, r, ex, ey), stroke=INK2, sw=14))
    nx, ny = cx + 52 * math.cos(ang), cy - 52 * math.sin(ang)
    b.append(L(cx, cy, nx, ny, INK, 3, cap="round"))
    b.append(C(cx, cy, 6, INK))
    b.append(T(cx - r, cy + 24, "E", 13, 700, INK3, "middle"))
    b.append(T(cx + r, cy + 24, "F", 13, 700, INK3, "middle"))
    b.append(T(cx - 6, 238, "412", 34, 800, INK, "middle"))
    b.append(T(cx + 30, 238, "km", 16, 600, INK2, "start"))
    b.append(ML(cx, 290, ["연료가 바닥나기 전에", "경고등이 먼저 켜진다"], 13, 20, 500, INK3, "middle"))

    # arrow
    b.append(L(240, 172, 288, 172, ACCENT, 2.5, marker="arrA"))
    b.append(T(264, 158, "사람에게도", 13, 700, ACCENT, "middle"))

    # right: human range
    x0 = 296
    b.append(R(x0 + 0.75, 0.75, W - x0 - 1.5, H - 1.5, 18, WHITE, LINE, 1.5))
    c, w_ = chip(x0 + 16, 16, "사람", fill=TINT, color=ACCENT_D)
    b.append(c)
    b.append(T(x0 + 16 + w_ + 8, 33, "다음 화장실·휴식까지 남은 시간", 14, 700, INK))
    cw = (W - x0 - 16 * 2 - 16) / 2.0
    b.append(gauge_card(x0 + 16, 50, cw, 150, "화장실", "39", "분", 39 / 90.0, 20 / 90.0, "6세 아이 기준", "확인 단계"))
    b.append(gauge_card(x0 + 32 + cw, 50, cw, 150, "피로", "72", "분", 72 / 120.0, 20 / 120.0, "연속 운전 0:48",
                        "여유 단계"))
    # stage strip (restroom gauge)
    sy = 228
    b.append(T(x0 + 16, sy, "화장실 게이지 단계 (남은 시간)", 12.5, 700, INK2))
    sx, sw_ = x0 + 16, W - x0 - 32
    ppm = sw_ / 60.0
    segs = [(0, 10, RED, "지금", "10분 미만"), (10, 20, ORANGE, "곧", "10~20분"),
            (20, 45, ACCENT, "확인", "20~45분"), (45, 60, ACCENT, "여유", "45분 초과")]
    for a, z, col, name, rng in segs:
        x1 = sx + a * ppm + (1 if a > 0 else 0)
        x2 = sx + z * ppm - (1 if z < 60 else 0)
        b.append(R(x1, sy + 10, x2 - x1, 12, 3, col))
        mid = (x1 + x2) / 2
        b.append(T(mid, sy + 40, name, 13, 700, INK, "middle"))
        b.append(T(mid, sy + 57, rng, 12, 500, INK3, "middle"))
    # inputs
    iy = 312
    b.append(T(x0 + 16, iy, "계산에 쓰는 것", 12.5, 700, INK2))
    xx, yy = x0 + 16, iy + 10
    for s in ["탑승자 프로필", "마지막 화장실·휴식", "기어·차속(주행 시간)", "시간대·수면", "운전 패턴",
              "경로 앞 휴게소·정체"]:
        wv = tw(s, 12, True) + 20
        if xx + wv > W - 16:
            xx, yy = x0 + 16, yy + 30
        c, wv = chip(xx, yy, s, 12)
        b.append(c)
        xx += wv + 6
    return svg(W, H, b)


# ------------------------------------------------------------------ figure 2: storyboard timeline

def fig_storyboard():
    W, H = 850, 556
    X0, X1, TMAX = 70, 806, 70.0
    DY = 20

    def tx(t):
        return X0 + (X1 - X0) * t / TMAX

    b = []
    b.append(T(16, 26, "경로", 14, 700, INK2))
    b.append(T(52, 26, "앞으로의 주행 시간 기준 · 추석 서울→부산, 6세 아이 동승", 12.5, 500, INK3))
    ry = 104
    b.append(L(tx(0), ry, tx(TMAX) - 4, ry, LINE2, 6, cap="round", marker=None))
    b.append(PATH("M %.1f %.1f l -9 -6 l 0 12 z" % (tx(TMAX) + 4, ry), fill=LINE2, stroke=LINE2, sw=1))
    # congestion
    b.append(R(tx(14), ry - 6, tx(54) - tx(14), 12, 4, RED))
    b.append(T((tx(14) + tx(54)) / 2, ry - 16, "정체 40분 · 안에 화장실 없음", 13, 700, INK, "middle"))
    # now
    b.append(C(tx(0), ry, 8, INK, WHITE, 2.5))
    b.append(T(tx(0), ry + 30, "지금", 13, 700, INK, "middle"))
    b.append(T(tx(0), ry + 47, "기흥 부근", 12, 500, INK3, "middle"))
    # question bubble at t=9
    qx = tx(8.6)
    b.append(R(qx - 44, ry - 58, 88, 24, 12, ACCENT))
    b.append(T(qx, ry - 41.5, "4km 전 질문", 12, 700, WHITE, "middle"))
    b.append(PATH("M %.1f %.1f l 6 0 l -6 9 l -6 -9 z" % (qx, ry - 34.5), fill=ACCENT, stroke=ACCENT, sw=1))
    b.append(L(qx, ry - 26, qx, ry - 9, ACCENT, 1.5, dash="3 3"))
    # Anseong rest area
    b.append(wc_icon(tx(12), ry, 13))
    b.append(T(tx(12), ry + 32, "안성휴게소", 13.5, 800, INK, "middle"))
    b.append(T(tx(12), ry + 49, "12분 · 정체 전 마지막", 12, 600, ACCENT_D, "middle"))
    # first rest area after the jam
    b.append(C(tx(58), ry, 12, WHITE, ACCENT, 2.5))
    b.append(T(tx(58), ry + 4, "WC", 9, 800, ACCENT, "middle"))
    b.append(T(tx(58), ry + 32, "정체 뒤 첫 휴게소", 13, 700, INK, "middle"))
    b.append(T(tx(58), ry + 49, "58분", 12, 600, INK3, "middle"))
    b.append(T(tx(TMAX), ry + 32, "부산 방향", 12, 500, INK3, "end"))

    # chart
    top, bot = 196 + DY, 352 + DY
    vmax = 100.0

    def ty(v):
        return bot - (bot - top) * v / vmax

    b.append(T(16, 176 + DY, "아이의 화장실 게이지 (남은 분)", 14, 700, INK2))
    # legend
    lx = 520
    b.append(L(lx, 171 + DY, lx + 26, 171 + DY, ACCENT, 3))
    b.append(T(lx + 32, 175 + DY, "안성휴게소에 들르면", 12.5, 500, INK))
    b.append(L(lx + 160, 171 + DY, lx + 186, 171 + DY, INK3, 2.5, dash="5 4"))
    b.append(T(lx + 192, 175 + DY, "지나치면", 12.5, 500, INK))
    b.append(R(tx(14), top, tx(54) - tx(14), bot - top, 0, RED_TINT))
    b.append(T(tx(54) - 8, top + 18, "정체 구간", 12, 700, "#B4321A", "end"))
    for v, name in [(45, "확인"), (20, "곧"), (10, "지금")]:
        b.append(L(X0, ty(v), X1, ty(v), LINE, 1))
        b.append(T(X1 + 6, ty(v) + 4, name, 11.5, 600, INK3))
    b.append(L(X0, bot, X1, bot, LINE2, 1.2))
    for v in (0, 10, 20, 45, 90):
        b.append(T(X0 - 8, ty(v) + 4, str(v), 11.5, 500, INK3, "end"))
    for t in range(0, 71, 10):
        b.append(T(tx(t), bot + 18, "%d분" % t if t else "0", 11.5, 500, INK3, "middle"))
    # skip path (dashed): 39 -> 0 at t=39
    b.append(PATH("M %.1f %.1f L %.1f %.1f" % (tx(0), ty(39), tx(39), ty(0)), stroke=INK3, sw=2.5, dash="6 5"))
    b.append(C(tx(39), ty(0), 6, RED, WHITE, 2))
    b.append(T(tx(39) + 10, ty(0) - 10, "지나치면 정체 안에서 0분", 12.5, 700, INK))
    # stop path (solid): 39 -> 27 at t=12, refill to 90, then down
    b.append(PATH("M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f" % (
        tx(0), ty(39), tx(12), ty(27), tx(12), ty(90), tx(70), ty(32)), stroke=ACCENT, sw=3))
    b.append(C(tx(12), ty(90), 4.5, ACCENT, WHITE, 1.5))
    b.append(T(tx(12) + 10, ty(90) - 6, "화장실에 들르면 다시 찬다 (정차 시간 제외)", 12.5, 600, INK))
    b.append(C(tx(0), ty(39), 4.5, INK, WHITE, 1.5))
    b.append(T(tx(0) + 8, ty(39) - 8, "39분", 12.5, 700, INK))

    # storyboard cards
    cy0, ch = 414, 132
    gap = 12
    cw = (W - 32 - gap * 3) / 4.0
    cards = [
        ("정체와 휴게소 읽기", "내비 경로 상태에서 앞 정체 40분을, 회전 안내에서 경로 위 휴게소를 읽는다"),
        ("마지막 기회 판정", "아이 게이지 39분이 정체 뒤 첫 휴게소까지 58분보다 짧다"),
        ("4km 전 음성 질문", "“앞에 40분 정체가 있어요. 안성휴게소가 정체 전 마지막 화장실이에요. 들를까요?”"),
        ("“응” → 경유지", "addWaypoint로 경유지를 넣고, 정차하면 기록해 게이지를 되돌린다"),
    ]
    for i, (title, body) in enumerate(cards):
        x = 16 + i * (cw + gap)
        b.append(R(x, cy0, cw, ch, 12, SURF))
        b.append(badge_num(x + 22, cy0 + 24, i + 1))
        b.append(T(x + 40, cy0 + 29, title, 13.5, 800, INK))
        lines = wrap(body, 12.5, cw - 30)
        b.append(ML(x + 16, cy0 + 58, lines, 12.5, 18.5, 500, INK2))
        if i < 3:
            b.append(L(x + cw + 2, cy0 + ch / 2, x + cw + gap - 1, cy0 + ch / 2, ACCENT, 1.8, marker="arrA"))
    return svg(W, H, b)


# ------------------------------------------------------------------ figure 3: architecture

def box(x, y, w, h, title, lines=(), fill=WHITE, stroke=LINE2, tsize=13.5, lsize=12, dash=None, tcolor=INK,
        center=False):
    b = [R(x, y, w, h, 10, fill, stroke, 1.3, dash)]
    if center:
        total = 18 + 16.5 * len(lines)
        y0 = y + (h - total) / 2 + 13
        b.append(T(x + w / 2, y0, title, tsize, 800, tcolor, "middle"))
        b.append(ML(x + w / 2, y0 + 19, list(lines), lsize, 16.5, 500, INK3, "middle"))
    else:
        b.append(T(x + 12, y + 22, title, tsize, 800, tcolor))
        b.append(ML(x + 12, y + 41, list(lines), lsize, 16.5, 500, INK3))
    return ''.join(b)


def band(x, y, w, h, label, sub, fill):
    return (R(x, y, w, h, 14, fill) + T(x + 14, y + 22, label, 13.5, 800, INK2) +
            T(x + 20 + tw(label, 13.5, True), y + 22, sub, 12, 500, INK3))


def fig_architecture():
    W, H = 850, 560
    b = []
    # ---- app band
    b.append(band(8, 8, 618, 248, "쉼표 앱", "app · Kotlin, Compose", SURF))
    b.append(box(24, 72, 168, 76, "화면 (Compose)", ["주행·계획·급해", "탑승자·기록·설정"]))
    b.append(box(24, 166, 168, 76, "VoiceAgent", ["TTS·STT 대화", "규칙 의도 분류 (+LLM)"]))
    b.append(box(236, 72, 182, 170, "CoachService", ["포그라운드 서비스", "신호 수집 (SignalHub)", "1초마다 엔진 호출",
                                                     "질문·경유지·공조 실행", "정차 기록"], stroke=INK2))
    right = [("RouteOracle", "requestTrip으로 경로 거리"), ("StopRepository", "공공데이터 · 격자 색인"),
             ("개인 기록", "탑승자·정차·학습, 기기 안")]
    for i, (t, sub) in enumerate(right):
        y = 72 + i * 58
        b.append(R(446, y, 168, 50, 10, WHITE, LINE2, 1.3))
        b.append(T(458, y + 21, t, 12.5, 800, INK))
        b.append(T(458, y + 39, sub, 11.5, 500, INK3))
        b.append(L(420, y + 25, 444, y + 25, INK3, 1.5, marker="arrG"))
    # screen <-> service, voice <-> service
    for y0, lab_a, lab_b in [(98, "명령", "상태"), (192, "대답", "질문")]:
        b.append(L(194, y0, 234, y0, INK3, 1.5, marker="arrG"))
        b.append(L(234, y0 + 14, 194, y0 + 14, INK3, 1.5, marker="arrG"))
        b.append(T(214, y0 - 6, lab_a, 10.5, 600, INK3, "middle"))
        b.append(T(214, y0 + 29, lab_b, 10.5, 600, INK3, "middle"))
    # service <-> engine lanes
    b.append(PATH("M 300 72 L 300 26 L 637 26", stroke=ACCENT, sw=2, marker="arrA"))
    b.append(T(470, 20, "CoachInput (신호 묶음) · 1초마다", 11.5, 700, ACCENT_D, "middle"))
    b.append(PATH("M 640 46 L 352 46 L 352 70", stroke=ACCENT, sw=2, marker="arrA"))
    b.append(T(498, 62, "게이지 · 질문 · 추천 · 공조 동작", 11.5, 700, ACCENT_D, "middle"))
    # ---- engine
    b.append(R(640, 8, 202, 248, 14, TINT, ACCENT, 1.5))
    b.append(T(654, 32, "rest-engine", 14, 800, ACCENT_D))
    b.append(T(654, 50, "판단 엔진 · 순수 Kotlin", 12, 500, INK2))
    b.append(T(654, 70, "CoachEngine.update()", 12, 700, INK))
    items = ["화장실 필요 모델", "운전 스타일 편차", "피로 모델", "마지막 기회", "급해 순위", "피트스톱 플래너", "묻는 타이밍"]
    for i, s_ in enumerate(items):
        b.append(R(654, 82 + i * 24, 174, 20, 10, WHITE))
        b.append(T(666, 96 + i * 24, s_, 11.5, 600, INK2))
    # ---- kit band
    ky = 284
    b.append(band(8, ky, 834, 104, "pleos-kit", "SDK를 코루틴·Flow로 감싼 공통 모듈", SURF))
    cols = [24, 162, 300, 438, 576, 714]
    cw = 128
    kit = [("VehicleKit", "신호·제어"), ("NaviKit", "조회·경로 변경"), ("CarState", "기어·차속·UX 제한"),
           ("TtsKit · SttKit", "ON_DEVICE"), ("LocationKit", "위치 Flow")]
    for (name, sub), x in zip(kit, cols):
        b.append(box(x, ky + 34, cw, 56, name, [sub], center=True))
    b.append(T(718, ky + 44, "kit이 지키는 규칙", 11.5, 800, INK2))
    for i, r_ in enumerate(["SDK 전용 스레드", "포그라운드 서비스", "제어 후 다시 읽기", "내비 요청 직렬화"]):
        b.append(C(722, ky + 59 + i * 14.5, 2.2, ACCENT))
        b.append(T(729, ky + 63 + i * 14.5, r_, 11, 600, INK2))
    # app -> kit
    b.append(L(327, 258, 327, ky - 2, INK3, 1.5, marker="arrG"))
    b.append(L(108, 258, 108, ky - 2, INK3, 1.5, marker="arrG"))
    b.append(L(530, 258, 530, ky - 2, INK3, 1.5, marker="arrG"))
    # ---- platform band
    py = 412
    b.append(band(8, py, 834, 140, "", "", SURF2))
    plat = [("Vehicle SDK", ["조향·브레이크·방향지시등", "크루즈·도어·공조"]),
            ("NaviHelper", ["회전 안내·경로 상태", "requestTrip·경유지"]),
            ("AAOS Car API", ["기어·차속", "CarUxRestrictions"]),
            ("TTS · STT", ["ON_DEVICE", "네트워크 없이 동작"]),
            ("FusedLocation", ["위치·진행 방향"]),
            ("LLM SDK", ["선택 기능", "(없으면 규칙으로)"])]
    for (name, lines), x in zip(plat, cols):
        dash = "5 4" if name == "LLM SDK" else None
        b.append(box(x, py + 14, cw, 74, name, lines, center=True, dash=dash))
    for x in cols[:5]:
        b.append(L(x + cw / 2, ky + 90, x + cw / 2, py + 12, INK3, 1.4))
    b.append(T(22, py + 116, "Pleos Connect", 13.5, 800, INK2))
    b.append(T(22 + tw("Pleos Connect", 13.5, True) + 8, py + 116,
               "SDK + AAOS Car API · Pleos Connect Emulator 2.1.0에서 호출 확인", 12, 500, INK3))
    return svg(W, H, b)


# ------------------------------------------------------------------ figure 4: engine

def fig_engine():
    W, H = 850, 470
    b = []
    cols = [(16, 168, "입력"), (204, 214, "상태 추정"), (442, 196, "판단·계획"), (662, 172, "전달")]
    for x, w, name in cols:
        b.append(T(x, 24, name, 13.5, 800, INK2))
        b.append(L(x, 33, x + w, 33, ACCENT if name != "입력" else LINE2, 2))
    inputs = ["탑승자 프로필 · 정차 기록", "조향 · 차속 · 제동", "연속 운전 · 시간대 · 수면", "경로 앞 기회 · 정체",
              "주변 후보 · 경로 시간", "다음 회전 · 거절 이력", "음성 발화"]
    for i, s in enumerate(inputs):
        y = 46 + i * 44
        b.append(R(16, y, 168, 34, 8, SURF))
        b.append(T(28, y + 22, s, 12, 600, INK2))

    def comp(x, y, w, h, n, title, lines, fill=WHITE, stroke=LINE2):
        o = [R(x, y, w, h, 10, fill, stroke, 1.3), badge_num(x + 18, y + 20, n),
             T(x + 34, y + 25, title, 13.5, 800, INK)]
        o.append(ML(x + 14, y + 47, lines, 12, 16.5, 500, INK3))
        return ''.join(o)

    ys = [46, 140, 234]
    st = [("화장실 필요 모델", ["와이블 생존모형", "개인 기록으로 베이즈 갱신"]),
          ("운전 스타일 편차", ["주행 초반 개인 기준선", "강건 z점수 + EWMA"]),
          ("피로 모델 (규칙)", ["연속 운전 + 졸음 시간대", "수면·편차·‘졸려’로 앞당김"])]
    for i, (t, ls) in enumerate(st):
        b.append(comp(204, ys[i], 214, 82, i + 1, t, ls))
    pl = [("마지막 기회", ["정체 반영 도착 시간과", "게이지로 공백 분석"]),
          ("급해 순위", ["총 소요 + 영업·주차", "야간 안전·교환대 가점"]),
          ("피트스톱 플래너", ["경로 순서 동적 계획법", "정차 수·시간 최소화"])]
    for i, (t, ls) in enumerate(pl):
        b.append(comp(442, ys[i], 196, 82, i + 4, t, ls))
    b.append(comp(662, 46, 172, 82, 7, "묻는 타이밍", ["규칙 게이트 +", "톰프슨 샘플링"]))
    b.append(comp(662, 140, 172, 82, 8, "음성 의도", ["규칙 분류가 기본", "LLM은 선택"]))
    b.append(R(662, 234, 172, 82, 10, TINT, ACCENT, 1.3))
    b.append(T(676, 258, "출력", 13.5, 800, ACCENT_D))
    b.append(ML(676, 280, ["게이지 2개 · 질문 1개", "추천 · 정차 계획 · 공조"], 12, 16.5, 600, INK2))
    # arrows
    b.append(L(186, 180, 202, 180, INK3, 1.6, marker="arrG"))
    b.append(L(311, 222, 311, 232, INK3, 1.6, marker="arrG"))
    b.append(L(420, 87, 440, 87, INK3, 1.6, marker="arrG"))
    b.append(L(420, 275, 440, 275, INK3, 1.6, marker="arrG"))
    b.append(PATH("M 420 100 C 432 100 428 262 440 262", stroke=INK3, sw=1.4, marker="arrG"))
    b.append(L(640, 87, 660, 87, INK3, 1.6, marker="arrG"))
    b.append(L(640, 181, 660, 270, INK3, 1.6, marker="arrG"))
    b.append(L(640, 275, 660, 275, INK3, 1.6, marker="arrG"))
    b.append(L(748, 130, 748, 138, INK3, 1.6, marker="arrG"))
    b.append(L(748, 224, 748, 232, INK3, 1.6, marker="arrG"))
    # learning loop
    ly = 352
    b.append(R(204, ly, 630, 100, 14, SURF))
    b.append(T(220, ly + 26, "기기 안에서 학습 (서버로 보내지 않음)", 13.5, 800, INK2))
    b.append(T(220, ly + 54, "화장실 정차 간격 → ① 사후분포 갱신 (다른 사람 때문에 선 정차는 중도절단)", 12.5, 500, INK))
    b.append(T(220, ly + 80, "질문 수락·거절 → ⑦ 질문 종류 × 묻는 거리별 수락률 갱신", 12.5, 500, INK))
    b.append(PATH("M 748 318 L 748 340 L 520 340 L 520 350", stroke=ACCENT, sw=1.8, marker="arrA"))
    b.append(T(16, ly + 26, "원칙", 13.5, 800, INK2))
    b.append(ML(16, ly + 50, ["결정은 규칙·최적화가 하고", "근거를 한 줄로 함께 보여 준다.", "졸음 감지·의료 판단은 하지 않는다."],
                12, 17, 500, INK3))
    return svg(W, H, b)


# ------------------------------------------------------------------ figure 5: MAU funnel

def fig_funnel():
    W, H = 850, 256
    b = []
    rows = [("국내 Pleos 탑재 차량", "누적 추정, 2030", 3670659, "367만 대", "#93BBFF"),
            ("앱마켓에서 앱을 쓰는 운전자", "A 30%", 1101198, "110만 명", "#5E98FF"),
            ("쉼표 설치", "B 10%", 110120, "11.0만 명", "#2C7BFF"),
            ("월 1회 이상 사용 (MAU)", "C 35%", 38542, "3.85만", ACCENT)]
    x0, maxw = 300, 420
    for i, (name, sub, v, label, col) in enumerate(rows):
        y = 18 + i * 54
        b.append(T(16, y + 18, name, 14, 700, INK))
        b.append(T(16, y + 36, sub, 12.5, 500, INK3))
        w = max(4.0, maxw * v / rows[0][2])
        b.append(R(x0, y + 4, w, 30, 4, col))
        b.append(T(x0 + w + 10, y + 25, label, 15 if i == 3 else 14, 800 if i == 3 else 700, INK))
        if i == 3:
            b.append(T(x0 + w + 12 + tw(label, 15, True), y + 25, "  탑재 차량의 1.05%", 12.5, 500, INK3))
    b.append(L(x0, 12, x0, 232, LINE2, 1))
    b.append(T(16, 246, "막대 길이 = 탑재 차량 대비 비율. A·B·C는 가정이며, 가장 불확실한 A(앱마켓 이용률)는 42dot 자료로 검증해야 한다.",
               12, 500, INK3))
    return svg(W, H, b)


# ------------------------------------------------------------------ figure 6: screen mockups

def _btn(x, y, w, h, label, filled, size=40, rx=32):
    fill = FILLED if filled else BASIC
    color = WHITE if filled else INK
    return R(x, y, w, h, rx, fill) + T(x + w / 2, y + h / 2 + size * 0.36, label, size, 700, color, "middle")


def _gauge(x, y, label, value, unit, frac, tick, reason, color=ACCENT, text_value=None):
    o = [R(x, y, 368, 240, 32, WHITE, "#E2E3E6", 2)]
    o.append(T(x + 32, y + 56, label, 28, 600, INK2))
    if text_value:
        o.append(T(x + 32, y + 128, text_value, 44, 800, INK))
    else:
        o.append(T(x + 32, y + 132, value, 60, 800, INK))
        o.append(T(x + 40 + num_w(value, 60), y + 132, unit, 32, 600, INK2))
    o.append(R(x + 32, y + 156, 304, 24, 12, "#E7E8EB"))
    o.append(R(x + 32, y + 156, 304 * frac, 24, 12, color))
    if tick is not None:
        tx = x + 32 + 304 * tick
        o.append(R(tx - 2, y + 148, 4, 40, 2, INK3))
    o.append(T(x + 32, y + 216, reason, 28, 500, INK3))
    return ''.join(o)


def _header(title, back=False, menu=True, mic=True, parked=False):
    o = []
    if back:
        o.append(R(40, 16, 80, 80, 20, BASIC))
        o.append(PATH("M 88 38 L 70 56 L 88 74", stroke=INK, sw=6))
    else:
        o.append(R(40, 16, 80, 80, 20, INK2))
        o.append(C(82, 50, 11, WHITE))
        o.append(PATH("M 91 54 Q 92 72 72 82", stroke=WHITE, sw=7))
    o.append(T(144, 70, title + (" ▾" if parked and menu else ""), 36, 800, INK))
    if mic:
        o.append(R(720, 16, 80, 80, 20, BASIC))
        o.append(R(749, 32, 22, 34, 11, INK2))
        o.append(PATH("M 742 56 Q 742 76 760 76 Q 778 76 778 56", stroke=INK2, sw=4))
        o.append(L(760, 76, 760 , 86, INK2, 4))
    else:
        o.append(R(720, 16, 80, 80, 20, BASIC))
        for k in range(3):
            o.append(C(744 + k * 16, 56, 4.5, INK2))
    return ''.join(o)


def _chip(x, y, s, size=30, fill=SURF2, color=INK, h=60):
    w = tw(s, size, True) + 40
    return R(x, y, w, h, 16, fill) + T(x + w / 2, y + h / 2 + size * 0.36, s, size, 600, color, "middle"), w


def screen_drive():
    o = [_header("주행")]
    o.append(_gauge(40, 136, "화장실", "39", "분", 39 / 90.0, 20 / 90.0, "아이 기준"))
    o.append(_gauge(432, 136, "피로", "72", "분", 72 / 120.0, 20 / 120.0, "연속 0:48"))
    o.append(R(40, 400, 760, 452, 32, WHITE, ACCENT, 4))
    o.append(T(80, 460, "정체 전 마지막 화장실", 28, 600, INK3))
    o.append(T(80, 520, "안성휴게소 (부산 방향)", 40, 800, INK))
    o.append(T(80, 572, "4.2km · 3분 · 경로 위", 30, 500, INK2))
    c, w = _chip(80, 604, "정체 40분 전 마지막")
    o.append(c)
    c2, _ = _chip(80 + w + 16, 604, "아이 동승")
    o.append(c2)
    o.append(L(80, 712, 760, 712, "#E7E8EB", 2))
    o.append(T(80, 768, "“안성휴게소에 들를까요?”", 30, 600, INK))
    o.append(T(80, 812, "‘응’ 또는 ‘아니’로 답하세요", 28, 500, INK3))
    o.append(_btn(40, 876, 368, 100, "들르기", True))
    o.append(_btn(432, 876, 368, 100, "괜찮아요", False))
    o.append(R(40, 1000, 760, 120, 32, RED))
    o.append(T(420, 1077, "! 급해요", 48, 800, INK, "middle"))
    return ''.join(o)


def screen_urgent():
    o = [_header("급해", back=True)]

    def card(y, n, name, meta, badges, filled):
        fill = FILLED if filled else BASIC
        tc = WHITE if filled else INK
        sc = "#E6E7EA" if filled else INK2
        r = [R(40, y, 760, 300, 32, fill)]
        r.append(T(80, y + 72, str(n), 40, 800, tc))
        r.append(T(130, y + 72, name, 40, 800, tc))
        r.append(T(130, y + 128, meta, 30, 500, sc))
        x = 130
        for bdg in badges:
            c, w = _chip(x, y + 156, bdg, 28, "#5A5C64" if filled else WHITE, tc, 56)
            r.append(c)
            x += w + 12
        r.append(T(760, y + 268, "여기로  >", 36, 800, tc, "end"))
        return ''.join(r)

    o.append(card(136, 1, "공영주차장 화장실", "4분 · 1.6km · 진행 방향", ["교환대", "비상벨"], True))
    o.append(card(460, 2, "주유소 화장실", "6분 · 2.3km · 진행 방향", ["24시간", "주차 가능"], False))
    o.append(T(80, 840, "밤 11시 · 영아 동승 기준으로 골랐어요", 28, 500, INK3))
    o.append(_btn(40, 1020, 760, 100, "닫기", False))
    return ''.join(o)


def screen_parked():
    o = [_header("주행", menu=True, mic=False, parked=True)]
    o.append(_gauge(40, 136, "화장실", "88", "분", 0.98, 20 / 90.0, "방금 들름"))
    o.append(_gauge(432, 136, "피로", None, None, 0.7, None, "10분이면 회복", text_value="휴식 7분째"))
    o.append(R(40, 400, 760, 300, 32, WHITE, "#E2E3E6", 2))
    o.append(T(80, 460, "다음 계획", 28, 600, INK3))
    o.append(T(80, 520, "옥산휴게소 (부산 방향)", 40, 800, INK))
    o.append(T(80, 572, "66km · 약 1시간 25분 (정체 포함)", 30, 500, INK2))
    c, w = _chip(80, 604, "화장실")
    o.append(c)
    c2, _ = _chip(80 + w + 16, 604, "휴식")
    o.append(c2)
    o.append(R(40, 724, 760, 128, 32, SURF2))
    o.append(T(80, 776, "오늘 정차 2번 · 쉰 시간 31분", 30, 700, INK))
    o.append(T(80, 820, "기어 P와 문 열림으로 자동 기록", 28, 500, INK3))
    o.append(_btn(40, 876, 368, 100, "계획 보기", True))
    o.append(_btn(432, 876, 368, 100, "탑승자", False))
    o.append(R(40, 1000, 760, 120, 32, RED))
    o.append(T(420, 1077, "! 급해요", 48, 800, INK, "middle"))
    return ''.join(o)


def screen_people():
    o = [_header("탑승자", mic=False, parked=True)]
    o.append(R(40, 136, 760, 300, 32, WHITE, "#E2E3E6", 2))
    rows = [("운전자", "성인"), ("아이", "6세 · 기저귀 교환대 필요 없음"), ("반려견", "반려동물 구역 선호")]
    for i, (a, s) in enumerate(rows):
        y = 136 + i * 100
        o.append(C(96, y + 50, 26, TINT))
        o.append(T(96, y + 60, a[0], 28, 800, ACCENT_D, "middle"))
        o.append(T(144, y + 48, a, 32, 800, INK))
        o.append(T(144, y + 82, s, 26, 500, INK3))
        o.append(PATH("M 752 %d L 768 %d L 752 %d" % (y + 36, y + 50, y + 64), stroke=INK3, sw=5))
        if i < 2:
            o.append(L(72, y + 100, 768, y + 100, "#E7E8EB", 2))
    o.append(_btn(40, 460, 760, 100, "+ 탑승자 추가", False))
    o.append(T(40, 618, "출발 전 마지막 화장실", 28, 700, INK2))
    x = 40
    for i, s in enumerate(["방금", "30분 전", "1시간 전", "모름"]):
        w = 172
        sel = i == 0
        o.append(R(x, 640, w, 80, 24, FILLED if sel else BASIC))
        o.append(T(x + w / 2, 691, s, 30, 700, WHITE if sel else INK, "middle"))
        x += w + 24
    o.append(T(40, 788, "지난밤 수면 (운전자)", 28, 700, INK2))
    x = 40
    for i, s in enumerate(["6시간 미만", "6시간 이상", "모름"]):
        w = 237
        sel = i == 1
        o.append(R(x, 810, w, 80, 24, FILLED if sel else BASIC))
        o.append(T(x + w / 2, 861, s, 30, 700, WHITE if sel else INK, "middle"))
        x += w + 24
    o.append(T(40, 970, "지금 기준: 아이 (가장 엄격한 탑승자)", 28, 600, INK2))
    o.append(T(40, 1014, "건강 정보는 이 차 안에만 저장됩니다", 28, 500, INK3))
    return ''.join(o)


def fig_screens():
    s = 0.452
    sw, sh = 840 * s, 1184 * s
    W = 850
    gap = 34
    left = (W - 2 * sw - gap) / 2
    lab = 44
    H = int(2 * (sh + lab) + 24)
    b = []
    screens = [(screen_drive, "① 주행 중 · 마지막 기회 질문"), (screen_urgent, "② 급해 결과 · 주행 중 2곳"),
               (screen_parked, "③ 정차 중 · 휴식 자동 기록"), (screen_people, "④ 탑승자 설정 · 정차 중")]
    for i, (fn, label) in enumerate(screens):
        col, row = i % 2, i // 2
        x = left + col * (sw + gap)
        y = 6 + row * (sh + lab + 12)
        b.append('<g transform="translate(%.1f %.1f) scale(%.4f)">' % (x, y, s))
        b.append(R(0, 0, 840, 1184, 40, "#F7F8FA", "#D5D7DC", 3))
        b.append(fn())
        b.append('</g>')
        b.append(T(x + sw / 2, y + sh + 26, label, 14, 700, INK, "middle"))
    return svg(W, H, b)


# ------------------------------------------------------------------ build

FIGS = {
    "gauge": ("fig1-gauge", fig_gauge),
    "storyboard": ("fig2-storyboard", fig_storyboard),
    "architecture": ("fig3-architecture", fig_architecture),
    "engine": ("fig4-engine", fig_engine),
    "funnel": ("fig5-funnel", fig_funnel),
    "screens": ("fig6-screens-mockup", fig_screens),
}


def render(name, fn, outdir):
    svg_path = os.path.join(outdir, name + ".svg")
    png_path = os.path.join(outdir, name + ".png")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(fn())
    subprocess.run(["rsvg-convert", "--zoom", "2", "--format", "png", "--output", png_path, svg_path], check=True)
    return png_path


def build_all(outdir, only=None):
    os.makedirs(outdir, exist_ok=True)
    out = []
    for key, (name, fn) in FIGS.items():
        if only and key not in only:
            continue
        out.append(render(name, fn, outdir))
    return out


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.join(os.path.dirname(os.path.dirname(here)), "docs", "plan", "assets")
    for p in build_all(assets, set(sys.argv[1:]) or None):
        print(p)
