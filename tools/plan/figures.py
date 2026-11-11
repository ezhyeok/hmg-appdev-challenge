"""Figures for the planning document: SVG written to docs/plan/assets/, PNG rendered at 2x.

    python3 -I tools/plan/figures.py                     # all figures
    python3 -I tools/plan/figures.py hero scene          # some figures
    python3 -I tools/plan/figures.py hero --hero-screen docs/plan/assets/emu/drive.png

Rendering needs `rsvg-convert` and the Pretendard font (falls back to Noto Sans CJK KR).
Colors: Pleos informative_positive as the single accent, Pleos grayscale for everything
else; orange/red appear only where they carry the gauge-stage meaning (SOON / NOW).
"""
import os
import subprocess
import sys

sys.dont_write_bytecode = True   # keep tools/plan free of __pycache__
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # icons.py, ooxml.py under `python3 -I`
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
    return ('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="%d" '
            'height="%d" viewBox="0 0 %d %d" font-family="%s">%s<rect width="%d" height="%d" fill="#FFFFFF"/>%s</svg>'
            % (w, h, w, h, FONT, DEFS, w, h, ''.join(body)))


def icon(name, x, y, size, fill=INK):
    """Material Symbols icon (tools/plan/icons.py) with its top-left corner at (x, y)."""
    import icons
    s = size / 960.0
    return ('<path d="%s" fill="%s" transform="translate(%.2f %.2f) scale(%.5f) translate(0 960)"/>'
            % (icons.PATHS[name], fill, x, y, s))


def image_uri(path):
    """PNG/JPEG file as a data URI (rsvg-convert renders embedded images without file access)."""
    import base64
    ext = os.path.splitext(path)[1].lower()
    mime = "image/png" if ext == ".png" else "image/jpeg"
    with open(path, "rb") as f:
        return "data:%s;base64,%s" % (mime, base64.b64encode(f.read()).decode("ascii"))


def image_size(path):
    import ooxml
    return ooxml.image_size(path)


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
    b.append(gauge_card(x0 + 16, 50, cw, 150, "화장실", "39", "분", 39 / 105.0, 20 / 105.0, "7살 아이 기준", "확인 단계"))
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
    inputs = ["탑승자 프로필 · 정차 기록", "조향 · 차속 · 제동", "연속 운전 · 기상 · 수면", "경로 앞 기회 · 정체",
              "주변 후보 · 경로 시간", "다음 회전 · 대화 이력", "음성 발화 · ‘졸려’"]
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
    st = [("화장실 필요 모델", ["와이블 생존모형 (k = 2.5)", "개인 기록으로 베이즈 갱신"]),
          ("운전 스타일 편차", ["주행 초반 개인 기준선", "강건 z점수 + EWMA"]),
          ("피로 모델", ["2시간 휴식 리듬 + 졸림 곡선", "‘혹시 졸리세요?’ 대답 우선"])]
    for i, (t, ls) in enumerate(st):
        b.append(comp(204, ys[i], 214, 82, i + 1, t, ls))
    pl = [("마지막 기회", ["정체 반영 도착 시간 vs", "판단 마감(30%) · 커버리지"]),
          ("급해 순위", ["총 소요 + 영업·주차", "야간 안전·교환대 가점"]),
          ("피트스톱 플래너", ["경로 순서 동적 계획법", "정차 수·시간 최소화"])]
    for i, (t, ls) in enumerate(pl):
        b.append(comp(442, ys[i], 196, 82, i + 4, t, ls))
    b.append(comp(662, 46, 172, 82, 7, "묻는 타이밍", ["회전·대화 뒤 침묵 +", "톰프슨 샘플링"]))
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


# ------------------------------------------------------------------ v2 figures (planning-doc revision)
#
# Sizing rule: every figure is 850 px wide and is placed at 17 cm, so 1 px = 0.2 mm and
# FS = 15 px is 8.5 pt on paper. No text below FS.

import json
import math

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, "docs", "plan", "assets")
DATA = os.path.join(ROOT, "docs", "plan", "data")
FS = 15.0
RED_TEXT = "#B4321A"          # dark red for text on light backgrounds (warning meaning only)
CALM = "#BFD6FF"              # light accent for 'fine' segments
HERO_SCREEN = None            # set by --hero-screen; else emu/drive.png, else screen-drive.png


def TS(x, y, parts, size=FS, anchor="start"):
    """One text line made of styled runs: parts = [(text, weight, color), ...]."""
    spans = ''.join('<tspan font-weight="%d" fill="%s">%s</tspan>' % (w, c, escape(t)) for t, w, c in parts)
    return ('<text x="%.1f" y="%.1f" font-size="%.1f" text-anchor="%s" xml:space="preserve">%s</text>'
            % (x, y, size, anchor, spans))


def ts_width(parts, size=FS):
    return sum(tw(t, size, w >= 600) for t, w, c in parts)


def hero_screen_path():
    for p in (HERO_SCREEN, os.path.join(ASSETS, "emu", "drive.png"), os.path.join(ASSETS, "screen-drive.png")):
        if p and os.path.exists(p):
            return p
    raise FileNotFoundError("no drive screen: docs/plan/assets/emu/drive.png or screen-drive.png")


def wc_marker(cx, cy, r=15, filled=True):
    """Restroom stop marker: accent disc with a white WC icon (filled) or a ring with an accent icon."""
    s = r * 1.25
    if filled:
        return C(cx, cy, r, ACCENT, WHITE, 2) + icon("Wc", cx - s / 2, cy - s / 2, s, WHITE)
    return C(cx, cy, r, WHITE, ACCENT, 2.5) + icon("Wc", cx - s / 2, cy - s / 2, s, ACCENT)


# ------------------------------------------------------------------ hero (box 1)

def fig_hero(screen=None):
    W, H = 850, 440
    path = screen or hero_screen_path()
    iw, ih = image_size(path)
    b = []
    # display frame sized to the screenshot (portrait app window or landscape emulator capture)
    max_w, max_h = 352.0, 336.0
    sc = min(max_w / iw, max_h / ih)
    dw, dh = iw * sc, ih * sc
    bez = 11
    fx, fy = 24 + bez, 14 + bez + (max_h - dh) / 2
    b.append('<filter id="soft" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="10"/>'
             '</filter>')
    b.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="26" fill="#000000" opacity="0.18" '
             'filter="url(#soft)"/>' % (fx - bez + 4, fy - bez + 12, dw + 2 * bez - 8, dh + 2 * bez - 8))
    b.append(R(fx - bez, fy - bez, dw + 2 * bez, dh + 2 * bez, 26, "#1D1E21"))
    b.append('<clipPath id="scr"><rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="15"/></clipPath>'
             % (fx, fy, dw, dh))
    b.append('<image x="%.1f" y="%.1f" width="%.1f" height="%.1f" preserveAspectRatio="xMidYMid slice" '
             'clip-path="url(#scr)" xlink:href="%s"/>' % (fx, fy, dw, dh, image_uri(path)))

    x0 = fx + dw + bez + 34
    colw = W - 18 - x0
    b.append(TS(x0, 40, [("차에는 주행가능거리, 사람에게는 ", 800, INK), ("쉼표", 800, ACCENT)], 21))
    sub = wrap("탑승자의 다음 화장실·휴식까지 남은 시간을 계산하는 차량용 휴식 코치", 15.5, colw)
    b.append(ML(x0, 66, sub, 15.5, 21, 500, INK3))
    props = [("Wc", "정체 전 마지막 화장실", "경로 위 휴게소·정체를 읽어 화장실 공백 앞에서 먼저 묻는다"),
             ("Group", "탑승자별 AI 예측", "아이·어르신·임신부마다 다른 시간, 정차 기록으로 우리 가족에게 맞춰진다"),
             ("Mic", "‘응’ 한마디", "화면 조작 없이 경유지 추가, 정차·휴식은 차가 알아서 기록")]
    y = 66 + 21 * len(sub) + 14
    for i, (ic, title, desc) in enumerate(props):
        b.append(C(x0 + 25, y + 25, 25, TINT))
        b.append(icon(ic, x0 + 25 - 15, y + 25 - 15, 30, ACCENT))
        tx_ = x0 + 64
        b.append(TS(tx_, y + 19, [("%s " % "①②③"[i], 800, ACCENT), (title, 800, INK)], 18.5))
        lines = wrap(desc, 15.5, W - 18 - tx_)
        b.append(ML(tx_, y + 44, lines, 15.5, 21, 500, INK2))
        y += max(66, 44 + 21 * len(lines)) + 14
    # evidence strip
    sy = H - 46
    b.append(R(16, sy, W - 32, 36, 18, SURF2))
    parts = [("Pleos ", 700, INK2), ("NaviHelper · Vehicle · TTS/STT · FusedLocation", 500, INK2),
             (" + AAOS Car API", 500, INK2), ("   |   ", 400, MUTED), ("판단 엔진 테스트 140개", 700, INK),
             ("   |   ", 400, MUTED), ("에뮬레이터 검증", 700, INK)]
    size = FS
    b.append(TS(W / 2, sy + 23.5, parts, size, "middle"))
    return svg(W, H, b)


# ------------------------------------------------------------------ scene (box 2 hook)

def fig_scene():
    W, H = 850, 474
    b = []
    b.append(TS(16, 36, [("옥산휴게소를 지나면, 다음 화장실은 ", 800, INK), ("63분 뒤", 800, RED_TEXT)], 24))
    b.append(T(16, 64, "추석 경부고속도로 하행 · 7살 아이 동승 · 마지막 화장실 55분 전", 15.5, 500, INK3))

    # distance axis (km)
    X0, DX1, KM = 92.0, 470.0, 26.0

    def kx(km):
        return X0 + (DX1 - X0) * km / KM

    dy = 134
    b.append(T(16, dy + 5.5, "거리", 15.5, 800, INK2))
    b.append(L(kx(0), dy, kx(KM), dy, LINE2, 6, cap="round"))
    b.append(L(kx(5), dy, kx(25), dy, RED, 10))
    b.append(C(kx(0), dy, 15, INK2, WHITE, 2))
    b.append(icon("Car", kx(0) - 10, dy - 10, 20, WHITE))
    b.append(wc_marker(kx(4), dy, 15, True))
    b.append(wc_marker(kx(25.2), dy, 15, False))
    b.append(TS(kx(4), dy - 26, [("옥산휴게소 ", 800, INK), ("4km", 600, INK3)], 16, "middle"))
    b.append(T(kx(15), dy - 22, "정체 20km", 16, 800, RED_TEXT, "middle"))
    b.append(TS(kx(25.2), dy - 26, [("죽암휴게소 ", 800, INK), ("25.2km", 600, INK3)], 16, "middle"))
    b.append(T(kx(0), dy + 36, "지금", 15, 700, INK2, "middle"))
    b.append(T(kx(KM) + 26, dy + 5.5, "평소 속도라면 15분 거리", 15, 500, INK3))

    # time axis (min)
    TX0, TX1, TMAX = 92.0, 826.0, 66.0

    def mx(m):
        return TX0 + (TX1 - TX0) * m / TMAX

    ty_ = 248
    # stretch: congestion 20 km becomes 60 min
    b.append('<polygon points="%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f" fill="%s"/>'
             % (kx(5), dy + 7, kx(25), dy + 7, mx(63), ty_ - 7, mx(3), ty_ - 7, RED_TINT))
    b.append(L(kx(5), dy + 7, mx(3), ty_ - 7, "#F2A08A", 1.5, dash="5 4"))
    b.append(L(kx(25), dy + 7, mx(63), ty_ - 7, "#F2A08A", 1.5, dash="5 4"))
    b.append(L(kx(4), dy + 17, mx(2.4), ty_ - 9, MUTED, 1.2, dash="3 3"))
    b.append(T((kx(25) + mx(63)) / 2 + 30, (dy + ty_) / 2 + 22, "정체 20km = 60분", 17, 800, RED_TEXT, "middle"))
    b.append(T(16, ty_ + 5.5, "시간", 15.5, 800, INK2))
    b.append(L(mx(0), ty_, mx(TMAX), ty_, LINE2, 6, cap="round"))
    b.append(L(mx(3), ty_, mx(63), ty_, RED, 10))
    b.append(C(mx(0), ty_, 7, INK2))
    b.append(C(mx(2.4), ty_, 8, ACCENT, WHITE, 2))
    b.append(wc_marker(mx(63.1), ty_, 13, False))
    for m in range(0, 41, 10):
        b.append(T(mx(m), ty_ + 28, "%d분" % m if m else "0", 15, 500, INK3, "middle"))
    b.append(T(mx(63.1), ty_ + 30, "죽암 63분", 15.5, 800, INK, "middle"))

    # the child's restroom gauge runs out inside the congestion
    gy = 314
    b.append(TS(mx(0), gy - 12, [("7살 아이 화장실 게이지 ", 800, INK), ("50분", 800, ACCENT_D)], 16))
    for a, z, col in ((0, 30, ACCENT), (30, 40, ORANGE), (40, 50, RED)):
        b.append(R(mx(a) + (1 if a else 0), gy, mx(z) - mx(a) - (1 if a else 0) - (1 if z < 50 else 0), 14, 4, col))
    b.append(L(mx(50), gy - 4, mx(50), ty_ + 8, RED, 1.6, dash="4 3"))
    b.append(C(mx(50), gy + 7, 9, RED, WHITE, 2.5))
    b.append(T(mx(50), gy + 46, "여기서 ‘쉬 마려워’", 18.5, 800, INK, "middle"))
    b.append(T(mx(50), gy + 70, "남는 선택: 참거나, 갓길에 세우거나", 15.5, 700, RED_TEXT, "middle"))

    # with 쉼표
    bx, by, bw, bh = 16, 346, 452, 92
    b.append(R(bx, by, bw, bh, 14, TINT))
    b.append(R(bx, by, 5, bh, 2.5, ACCENT))
    b.append(T(bx + 20, by + 25, "쉼표라면 4km 전에 먼저 묻는다", 15.5, 800, ACCENT_D))
    voice = wrap("“앞에 1시간 정체가 있어요. 옥산휴게소가 정체 전 마지막 화장실이에요. 들를까요?” → ‘응’ → 경유지",
                 15.5, bw - 36)
    b.append(ML(bx + 20, by + 49, voice, 15.5, 21, 500, INK))
    b.append(T(16, H - 12, "고속도로 2차 사고 치사율은 일반 사고의 약 6배 (한국도로공사)", 15, 500, INK3))
    return svg(W, H, b)


# ------------------------------------------------------------------ gap map (box 2, own data analysis)

def fig_gapmap(path=None):
    d = json.load(open(path or os.path.join(DATA, "analysis.json"), encoding="utf-8"))["gyeongbu_south"]
    st, stops, gaps = d["stats"], d["stops"], d["gaps"]
    length = float(d["lengthKm"])
    W, H = 850, 396
    X0, X1 = 52.0, 818.0

    def kx(km):
        return X0 + (X1 - X0) * km / length

    def color(minutes):
        return RED if minutes > 60 else (ORANGE if minutes >= 30 else CALM)

    b = []
    b.append(T(16, 30, "경부고속도로 서울→부산 %dkm · 화장실 있는 휴게시설 %d곳 · 평균 %.1fkm 간격"
               % (round(length), st["stops"], st["meanGapKm"]), 19, 800, INK))
    b.append(TS(16, 57, [("휴게소 %d · 졸음쉼터 %d.  정체(시속 20km)라면 공백 %d개 중 " % (st["restAreas"], st["shelters"],
                                                                         st["gaps"]), 500, INK2),
                         ("%d개가 30분 초과" % st["gapsOver30minAt20"], 800, INK),
                         (", ", 500, INK2),
                         ("%d개가 60분 초과" % st["gapsOver60minAt20"], 800, RED_TEXT)], 15.5))
    # route with stops
    my = 104
    b.append(L(kx(0), my, kx(length), my, LINE2, 4, cap="round"))
    for name, km in (("서울", 0), ("천안", 90), ("대전", 145), ("김천", 223), ("대구", 285), ("경주", 350), ("부산", 416)):
        b.append(L(kx(km), my + 8, kx(km), my + 14, INK3, 1.5))
        b.append(T(kx(km), my + 34, name, 15, 600, INK2, "middle"))
    for s in stops:
        if s["kind"] != "rest_area":
            b.append(C(kx(s["fromOriginKm"]), my, 5, WHITE, ACCENT, 2))
    for s in stops:
        if s["kind"] == "rest_area":
            b.append(C(kx(s["fromOriginKm"]), my, 6.5, ACCENT, WHITE, 1.5))
    lx = W - 18
    leg = [("졸음쉼터 %d" % st["shelters"], False), ("휴게소 %d" % st["restAreas"], True)]
    for label, filled in leg:
        wv = tw(label, 15, True)
        b.append(T(lx, my - 18, label, 15, 600, INK2, "end"))
        cxm = lx - wv - 12
        b.append(C(cxm, my - 23, 6.5 if filled else 5, ACCENT if filled else WHITE, WHITE if filled else ACCENT,
                   1.5 if filled else 2))
        lx = cxm - 22

    def row(y_title, y_bar, title, stats, speed, show_labels):
        b.append(T(X0, y_title, title, 15.5, 800, INK))
        b.append(T(X0 + tw(title, 15.5, True) + 14, y_title, stats, 15.5, 500, INK2))
        for g in gaps:
            minutes = g["km"] / speed * 60.0
            x1, x2 = kx(g["startKm"]) + 1, kx(g["endKm"]) - 1
            b.append(R(x1, y_bar, max(1.0, x2 - x1), 18, 3, color(minutes)))
            if show_labels and minutes > 60:
                b.append(T((x1 + x2) / 2, y_bar - 7, "%d분" % round(minutes), 15, 800, INK, "middle"))

    row(176, 186, "시속 100km", "평균 %d분 · 최대 %d분" % (st["meanMin100"], st["maxMin100"]), 100.0, False)
    row(240, 274, "시속 20km 정체", "평균 %d분 · 최대 %d분" % (st["meanMin20"], st["maxMin20"]), 20.0, True)
    # 옥산→죽암 = the scene above
    ok = next((g for g in gaps if g["from"].startswith("옥산") and g["to"].startswith("죽암")), None)
    if ok:
        cxg = (kx(ok["startKm"]) + kx(ok["endKm"])) / 2
        b.append(L(cxg, 296, cxg, 306, INK3, 1.2))
        b.append(T(cxg - 8, 323, "옥산→죽암 (위 장면)", 15, 600, INK2))
    # legend (right side of the 20 km/h title line)
    lx = W - 18
    for label, col in (("60분 초과", RED), ("30~60분", ORANGE), ("30분 미만", CALM)):
        wv = tw(label, 15)
        b.append(T(lx, 240, label, 15, 500, INK2, "end"))
        b.append(R(lx - wv - 26, 228, 18, 14, 3, col))
        lx -= wv + 44
    note = ("한국도로공사 공공데이터(휴게소·졸음쉼터 이정)와 OpenStreetMap으로 직접 계산. 졸음쉼터는 화장실 있는 곳만, "
            "화물차·양방향 휴게소 포함. 시속 20km 행은 전 구간이 정체라고 가정한 경우(what-if)다.")
    b.append(ML(16, 356, wrap(note, 15, W - 32), 15, 20, 500, INK3))
    return svg(W, H, b)


# ------------------------------------------------------------------ core loop (box 3)

def fig_loop():
    W, H = 850, 500
    cx, cy, rx, ry = 425.0, 250.0, 286.0, 172.0
    b = [('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="none" stroke="%s" stroke-width="8"/>'
          % (cx, cy, rx, ry, TINT2))]
    # direction arrows at the middle of each arc (clockwise on screen)
    for deg in (216, 288, 0, 72, 144):
        t = math.radians(deg)
        px, py = cx + rx * math.cos(t), cy + ry * math.sin(t)
        vx, vy = -rx * math.sin(t), ry * math.cos(t)
        ang = math.degrees(math.atan2(vy, vx))
        b.append('<path d="M -11 -10 L 11 0 L -11 10 z" fill="%s" transform="translate(%.1f %.1f) rotate(%.1f)"/>'
                 % (ACCENT, px, py, ang))
    nodes = [
        (180, "감지", "기어·도어·조향·차속, Pleos 내비의 경로·정체",
         ["getTBTInfo · getRouteStateInfo", "GEAR_SELECTION · 도어·조향"]),
        (252, "예측", "탑승자별 화장실 게이지와 피로 게이지",
         ["와이블 생존모형 · 베이즈 갱신", "Three-Process Model"]),
        (324, "판단", "경로 위 화장실 공백, 마지막 기회, 운전 부하가 낮은 순간",
         ["requestTrip (진행 방향)", "CarUxRestrictions"]),
        (36, "묻고 실행", "음성으로 한 번 묻고, ‘응’이면 경유지 추가, 필요하면 환기",
         ["TTS·STT · addWaypoint", "setHvacRecirculation"]),
        (108, "학습", "정차 기록 → 개인 모델 베이즈 갱신, 질문 수락률 → 묻는 거리 톰프슨 샘플링",
         ["기기 안에만 저장"]),
    ]
    cw = 258.0
    for i, (deg, title, desc, apis) in enumerate(nodes):
        t = math.radians(deg)
        px, py = cx + rx * math.cos(t), cy + ry * math.sin(t)
        dl = wrap(desc, 15.5, cw - 30)
        ch = 16 + 24 + 21 * len(dl) + 8 + 19.5 * len(apis) + 12
        x, y = px - cw / 2, py - ch / 2
        b.append(R(x, y, cw, ch, 14, WHITE, LINE2, 1.5))
        b.append(badge_num(x + 26, y + 27, i + 1, r=13))
        b.append(T(x + 46, y + 33, title, 18.5, 800, INK))
        b.append(ML(x + 16, y + 62, dl, 15.5, 21, 500, INK2))
        ay = y + 62 + 21 * len(dl) + 6
        b.append(ML(x + 16, ay, apis, 15, 19.5, 600, INK3))
    b.append(T(cx, cy - 8, "모든 판단은 기기 안에서", 21, 800, INK, "middle"))
    b.append(T(cx, cy + 20, "1초마다 감지·예측·판단", 15.5, 500, INK3, "middle"))
    b.append(T(cx, cy + 41, "정차할 때마다 학습", 15.5, 500, INK3, "middle"))
    return svg(W, H, b)


# ------------------------------------------------------------------ storyboard (box 4), 옥산 numbers

def fig_storyboard():
    W, H = 850, 624
    X0, X1, TMAX = 76.0, 806.0, 70.0

    def tx(t):
        return X0 + (X1 - X0) * t / TMAX

    b = []
    b.append(T(16, 28, "경로", 16, 800, INK2))
    b.append(T(62, 28, "앞으로의 주행 시간 · 추석 경부고속도로 하행, 7살 아이 동승", 15.5, 500, INK3))
    ry = 124
    b.append(L(tx(0), ry, tx(TMAX) - 4, ry, LINE2, 6, cap="round"))
    b.append(PATH("M %.1f %.1f l -9 -6 l 0 12 z" % (tx(TMAX) + 4, ry), fill=LINE2, stroke=LINE2, sw=1))
    b.append(T(tx(TMAX), ry - 18, "부산 방향", 15, 500, INK3, "end"))
    b.append(R(tx(3), ry - 6, tx(63) - tx(3), 12, 4, RED))
    b.append(T((tx(3) + tx(63)) / 2 + 40, ry - 18, "정체 20km · 약 60분 · 안에 화장실 없음", 16, 800, INK, "middle"))
    # now = question moment (4 km before 옥산)
    b.append(R(tx(0) - 12, ry - 66, 176, 32, 16, ACCENT))
    b.append(T(tx(0) + 76, ry - 44.5, "지금 · 4km 전 질문", 15.5, 800, WHITE, "middle"))
    b.append(PATH("M %.1f %.1f l 7 0 l -7 10 l -7 -10 z" % (tx(0) + 2, ry - 34.5), fill=ACCENT, stroke=ACCENT, sw=1))
    b.append(C(tx(0), ry, 9, INK, WHITE, 2.5))
    b.append(wc_marker(tx(2.4) + 6, ry, 14, True))
    b.append(T(tx(2.4) - 10, ry + 40, "옥산휴게소", 16, 800, INK))
    b.append(T(tx(2.4) - 10, ry + 62, "3분 뒤 · 정체 전 마지막", 15, 700, ACCENT_D))
    b.append(wc_marker(tx(63.1), ry, 14, False))
    b.append(T(tx(63.1), ry + 40, "죽암휴게소", 16, 800, INK, "middle"))
    b.append(T(tx(63.1), ry + 62, "63분 뒤", 15, 600, INK3, "middle"))

    # chart
    top, bot = 248.0, 410.0
    vmax = 112.0

    def ty(v):
        return bot - (bot - top) * v / vmax

    b.append(T(16, 226, "아이의 화장실 게이지 (남은 분)", 16, 800, INK2))
    lx = 452
    b.append(L(lx, 221, lx + 28, 221, ACCENT, 3))
    b.append(T(lx + 36, 226, "옥산휴게소에 들르면", 15, 600, INK))
    b.append(L(lx + 202, 221, lx + 230, 221, INK3, 2.5, dash="5 4"))
    b.append(T(lx + 238, 226, "지나치면", 15, 600, INK))
    b.append(R(tx(3), top, tx(63) - tx(3), bot - top, 0, RED_TINT))
    b.append(T(tx(63) - 10, top + 22, "정체 구간", 15, 800, RED_TEXT, "end"))
    for v, name in ((45, "확인"), (20, "곧")):
        b.append(L(X0, ty(v), X1, ty(v), LINE, 1))
        b.append(T(X1 + 6, ty(v) + 5, name, 15, 600, INK3))
    b.append(L(X0, bot, X1, bot, LINE2, 1.2))
    for v in (0, 20, 45, 105):
        b.append(T(X0 - 9, ty(v) + 5, str(v), 15, 500, INK3, "end"))
    for t in range(0, 71, 10):
        b.append(T(tx(t), bot + 24, "%d분" % t if t else "0", 15, 500, INK3, "middle"))
    b.append(PATH("M %.1f %.1f L %.1f %.1f" % (tx(0), ty(50), tx(50), ty(0)), stroke=INK3, sw=2.5, dash="6 5"))
    b.append(C(tx(50), ty(0), 7, RED, WHITE, 2))
    b.append(T(tx(50) + 12, ty(0) - 12, "지나치면 정체 안에서 0분", 15.5, 800, INK))
    b.append(PATH("M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f" % (
        tx(0), ty(50), tx(2.4), ty(47.6), tx(2.4), ty(105), tx(70), ty(37.4)), stroke=ACCENT, sw=3))
    b.append(C(tx(2.4), ty(105), 5, ACCENT, WHITE, 1.5))
    b.append(T(tx(2.4) + 12, ty(105) - 9, "옥산휴게소에 들르면 다시 찬다 (정차 시간 제외)", 15.5, 700, INK))
    b.append(C(tx(0), ty(50), 5, INK, WHITE, 1.5))
    b.append(T(tx(2.4) + 10, ty(50) - 9, "50분 · 지금(질문 시점)", 15.5, 800, INK))

    # cards
    cy0 = 452
    gap = 12
    cw = (W - 32 - gap * 3) / 4.0
    cards = [
        ("정체·휴게소 읽기", "회전 안내에서 옥산·죽암휴게소를, 경로 상태에서 정체 20km를 읽는다"),
        ("마지막 기회 판정", "정체 안에 화장실이 없고, 죽암휴게소(63분 뒤)가 아이 게이지 50분보다 멀다"),
        ("4km 전 음성 질문", "“앞에 1시간 정체가 있어요. 옥산휴게소가 정체 전 마지막 화장실이에요. 들를까요?”"),
        ("“응” → 경유지", "addWaypoint로 경유지를 넣고, 정차하면 기록해 게이지를 되돌린다"),
    ]
    lines_all = [wrap(body, 15, cw - 28) for _, body in cards]
    ch = 56 + 20.5 * max(len(l) for l in lines_all) + 12
    for i, ((title, body), lines) in enumerate(zip(cards, lines_all)):
        x = 16 + i * (cw + gap)
        b.append(R(x, cy0, cw, ch, 12, SURF))
        b.append(badge_num(x + 24, cy0 + 26, i + 1, r=13))
        b.append(T(x + 44, cy0 + 32, title, 16.5, 800, INK))
        b.append(ML(x + 14, cy0 + 62, lines, 15, 20.5, 500, INK2))
        if i < 3:
            b.append(L(x + cw + 1, cy0 + ch / 2, x + cw + gap - 1, cy0 + ch / 2, ACCENT, 2, marker="arrA"))
    H = int(cy0 + ch + 8)
    return svg(W, H, b)


# ------------------------------------------------------------------ models (box 5) and effect (box 6)
#
# Data from the engine agent: docs/plan/data/engine_curves.json and effect_sim.json
# (rest-engine/src/sim, `./gradlew -p rest-engine runSim`, engine code as committed).

def _load(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return json.load(f)


def _axes(b, x, y, w, h, xr, yr, xticks, yticks, xfmt, yfmt, grid=True):
    """Plot frame with recessive grid. Returns (px, py) mappers."""
    (x0, x1), (y0, y1) = xr, yr

    def px(v):
        return x + w * (v - x0) / float(x1 - x0)

    def py(v):
        return y + h - h * (v - y0) / float(y1 - y0)

    for v in yticks:
        if grid:
            b.append(L(x, py(v), x + w, py(v), LINE, 1))
        b.append(T(x - 8, py(v) + 5, yfmt(v), FS, 500, INK3, "end"))
    b.append(L(x, y + h, x + w, y + h, LINE2, 1.2))
    for v in xticks:
        b.append(T(px(v), y + h + 22, xfmt(v), FS, 500, INK3, "middle"))
    return px, py


def _poly(points, color, width=2.5, dash=None):
    a = '<polyline points="%s" fill="none" stroke="%s" stroke-width="%.1f" stroke-linejoin="round"' % (
        " ".join("%.1f,%.1f" % p for p in points), color, width)
    if dash:
        a += ' stroke-dasharray="%s"' % dash
    return a + '/>'


def fig_models():
    d = _load("engine_curves.json")
    W, H = 850, 404
    b = []
    pw, ph, top = 214.0, 196.0, 138.0
    xs = [62.0, 344.0, 622.0]
    heads = [("① 탑승자별 필요 확률", "마지막 화장실 이후 (분)"),
             ("② 기록으로 개인에 맞춘다", "기록된 화장실 정차 (번째)"),
             ("③ 운전 패턴 편차", "주행 시간 (분)")]
    for (t1, t2), x in zip(heads, xs):
        b.append(T(x - 46, 34, t1, 17, 800, INK))
        b.append(T(x + pw, top + ph + 46, t2, FS, 500, INK3, "end"))

    # ① need CDF by occupant type, with the gauge (50%) and decision (30%) lines
    nd = d["needCdf"]
    ts = nd["tMin"]
    px, py = _axes(b, xs[0], top, pw, ph, (0, 240), (0, 1), (0, 60, 120, 180, 240), (0, 0.3, 0.5, 1.0),
                   lambda v: "%d" % v, lambda v: "%d%%" % round(v * 100))
    series = [("CHILD_4", "아이 4세", "#93BBFF"), ("CHILD_7", "아이 7세", ACCENT),
              ("ELDERLY", "어르신", INK2), ("ADULT", "어른", MUTED)]
    for key, label, col in series:
        s = nd["series"][key]
        b.append(_poly([(px(t), py(c)) for t, c in zip(ts, s["cdf"])], col, 3 if key == "CHILD_7" else 2.5))
    b.append(L(xs[0], py(0.5), xs[0] + pw, py(0.5), INK, 1.3, dash="5 4"))
    b.append(L(xs[0], py(0.3), xs[0] + pw, py(0.3), ORANGE, 1.6, dash="5 4"))
    lx1 = xs[0] - 46
    b.append(L(lx1, 103, lx1 + 20, 103, INK, 1.4, dash="5 4"))
    b.append(T(lx1 + 26, 108, "게이지 50%", FS, 600, INK2))
    b.append(L(lx1 + 132, 103, lx1 + 152, 103, ORANGE, 1.6, dash="5 4"))
    b.append(T(lx1 + 158, 108, "판단 30%", FS, 600, INK2))
    for i, (key, label, col) in enumerate(series):
        s = nd["series"][key]
        lx_, ly_ = xs[0] - 46 + (i % 2) * 132, 64 + (i // 2) * 22
        b.append(L(lx_, ly_ - 5, lx_ + 20, ly_ - 5, col, 3.5))
        b.append(T(lx_ + 26, ly_, "%s %d분" % (label, s["priorMedianMin"]), FS, 600, INK2))

    # ② personalization: recorded intervals (dots) and the engine's posterior median (line)
    ps = d["personalization"]
    after = ps["afterN"]
    obs = ps["intervalsMin"]
    first, last = after[0]["posteriorMedianMin"], after[-1]["posteriorMedianMin"]
    ymax = max(200.0, math.ceil(max(obs) / 50.0) * 50.0)
    px, py = _axes(b, xs[1], top, pw, ph, (0, len(obs)), (0, ymax), (0, 3, 6, 9, 12),
                   [v for v in (0, 50, 100, 150, 200, 250) if v <= ymax], lambda v: "%d" % v, lambda v: "%d" % v)
    b.append(L(xs[1], py(ps["trueMedianMin"]), xs[1] + pw, py(ps["trueMedianMin"]), INK3, 1.4, dash="5 4"))
    for i, v in enumerate(obs):
        b.append(C(px(i + 1), py(v), 4.5, MUTED))
    b.append(_poly([(px(a["n"]), py(a["posteriorMedianMin"])) for a in after], ACCENT, 3))
    for a in after:
        b.append(C(px(a["n"]), py(a["posteriorMedianMin"]), 3.2, ACCENT, WHITE, 1))
    lx2 = xs[1] - 46
    b.append(C(lx2 + 10, 59, 4.5, MUTED))
    b.append(T(lx2 + 26, 64, "기록된 화장실 간격 %d개" % len(obs), FS, 600, INK2))
    b.append(L(lx2, 81, lx2 + 20, 81, ACCENT, 3.5))
    b.append(T(lx2 + 26, 86, "엔진의 사후 중앙값 %d→%d분" % (round(first), round(last)), FS, 600, INK2))
    b.append(L(lx2, 103, lx2 + 20, 103, INK3, 1.4, dash="5 4"))
    b.append(T(lx2 + 26, 108, "실제 중앙값 %d분" % ps["trueMedianMin"], FS, 600, INK2))

    # ③ driving-style statistic: the EWMA S the engine accumulates (continuous), with its limits
    ds = d["drivingStyle"]
    ev, th = ds["events"], ds["thresholds"]
    ucl = float(th["ewmaUcl3Sigma"])
    s_adv = th["deviationAdvance"] * 2.0 * ucl          # deviation = clamp(S / (2·UCL), 0, 1)
    rows = [r for r in ds["perMinute"] if r["ewmaS"] is not None]
    smax = max(r["ewmaS"] for r in rows)
    ytop = max(5.0, math.ceil(smax))
    px, py = _axes(b, xs[2], top, pw, ph, (0, 60), (-1, ytop), (0, 20, 40, 60),
                   [v for v in range(0, int(ytop) + 1, 2)], lambda v: "%d" % v, lambda v: "%d" % v)
    ready = ev["baselineReadyAtMinute"]
    b.append(R(px(0), top, px(ready) - px(0), ph, 0, SURF2))
    b.append(ML(px(ready / 2.0), py(3.0), ["기준선", "만드는 중"], FS, 19, 600, INK3, "middle"))
    b.append(L(xs[2], py(ucl), xs[2] + pw, py(ucl), INK3, 1.3, dash="2 3"))
    b.append(L(xs[2], py(s_adv), xs[2] + pw, py(s_adv), ORANGE, 1.6, dash="5 4"))
    b.append(_poly([(px(r["minute"]), py(r["ewmaS"])) for r in rows], ACCENT, 3))
    adv = ev["stageAdvancedAtMinute"]
    b.append(L(px(adv), top, px(adv), top + ph, RED, 1.5, dash="3 3"))
    b.append(T(px(adv) - 7, top + 20, "%d분 한 단계 앞당김" % adv, FS, 800, RED_TEXT, "end"))
    b.append(T(px(adv) - 7, top + 40, "‘혹시 졸리세요?’", FS, 600, INK2, "end"))
    lx3 = xs[2] - 46
    b.append(L(lx3, 59, lx3 + 20, 59, ACCENT, 3.5))
    b.append(T(lx3 + 26, 64, "EWMA 통계량 S (λ %.1f)" % th["ewmaLambda"], FS, 600, INK2))
    b.append(L(lx3, 81, lx3 + 20, 81, INK3, 1.3, dash="2 3"))
    b.append(T(lx3 + 26, 86, "3σ 관리한계 S = %.1f" % ucl, FS, 600, INK2))
    b.append(L(lx3, 103, lx3 + 20, 103, ORANGE, 1.6, dash="5 4"))
    b.append(T(lx3 + 26, 108, "앞당김 기준 S = %.1f (편차 %.1f)" % (s_adv, th["deviationAdvance"]), FS, 600, INK2))

    foot = ("① 화면 게이지는 필요 확률 50%%, 마지막 기회·계획 판단은 30%%에서 정한다(엔진 사전분포). "
            "② 가상의 성인(실제 중앙값 %d분)의 화장실 간격 %d개(점)를 차례로 넣으면 엔진의 사후 중앙값이 사전값 %d분에서 "
            "%d분으로 실제 쪽으로 옮겨 간다. ③ 40분부터 운전이 흐트러지는 가상 주행. 정속 구간 특징의 강건 z점수를 "
            "EWMA로 누적한 S가 앞당김 기준 %.1f을 넘고 5분 안 3번 이상 이어진 %d분에 피로 단계를 한 단계 앞당기고 묻는다. "
            "편차만으로는 휴식 권고를 넘기지 않는다."
            % (ps["trueMedianMin"], len(obs), round(first), round(last), s_adv, adv))
    b.append(ML(16, top + ph + 84, wrap(foot, FS, W - 32), FS, 20, 500, INK3))
    lines = len(wrap(foot, FS, W - 32))
    H = int(top + ph + 84 + 20 * (lines - 1) + 14)
    return svg(W, H, b)


def fig_effect():
    d = _load("effect_sim.json")
    cond = {c["key"]: c for c in d["conditions"]}
    main = cond["child7_holiday_sigma0.3"]
    W = 850
    b = []
    b.append(T(16, 32, "화장실 위기: 급해진 뒤 30분 넘게 화장실에 못 간 여정", 19, 800, INK))
    b.append(T(16, 58, "운전자 + 7살 아이 · 명절 정체 · 정책마다 같은 여정 %d번 (엔진 코드 시뮬레이션)" % main["trips"],
               15.5, 500, INK3))
    pol = [("nowLike", "지금처럼", MUTED), ("every2h", "2시간마다 휴게소", MUTED),
           ("shimpyoP07", "쉼표 (수락 70%)", "#5E98FF"), ("shimpyoP10", "쉼표 (수락 100%)", ACCENT)]
    x0, xmax, vmax = 176.0, 452.0, 0.45
    y0, bh, gap = 82.0, 30.0, 16.0

    def vx(v):
        return x0 + (xmax - x0) * v / vmax

    for tick in (0, 0.1, 0.2, 0.3, 0.4):
        b.append(L(vx(tick), y0 - 6, vx(tick), y0 + 4 * (bh + gap) - gap + 6, LINE, 1))
        b.append(T(vx(tick), y0 + 4 * (bh + gap) - gap + 26, "%d%%" % round(tick * 100), FS, 500, INK3, "middle"))
    for i, (key, label, col) in enumerate(pol):
        m = main["policies"][key]["crisisOver30MinShare"]
        y = y0 + i * (bh + gap)
        ours = key.startswith("shimpyo")
        b.append(T(x0 - 12, y + bh / 2 + 5.5, label, 15.5, 800 if ours else 500, INK, "end"))
        b.append(R(x0, y, vx(m["value"]) - x0, bh, 4, col))
        lo, hi = m["ci95"]
        b.append(L(vx(lo), y + bh / 2, vx(hi), y + bh / 2, INK, 1.6))
        b.append(L(vx(lo), y + bh / 2 - 6, vx(lo), y + bh / 2 + 6, INK, 1.6))
        b.append(L(vx(hi), y + bh / 2 - 6, vx(hi), y + bh / 2 + 6, INK, 1.6))
        b.append(T(vx(hi) + 8, y + bh / 2 + 5.5, "%d%%" % round(m["value"] * 100), 15.5, 800, INK))

    # other occupants: paired difference (쉼표 70% − 지금처럼) with 95% CI
    fx0, fx1 = 694.0, 826.0
    lo_r, hi_r = -0.30, 0.10

    def fxv(v):
        return fx0 + (fx1 - fx0) * (v - lo_r) / (hi_r - lo_r)

    b.append(T(500, 92, "다른 탑승 조건 (명절 정체)", 16, 800, INK))
    b.append(T(500, 114, "쉼표(수락 70%) − 지금처럼, %p", FS, 500, INK3))
    others = [("child4_holiday_sigma0.3", "아이 4세"), ("elderly_holiday_sigma0.3", "어르신"),
              ("oabDriver_holiday_sigma0.3", "과민성 방광"), ("adults_holiday_sigma0.3", "어른 2명")]
    oy0 = 146.0
    b.append(L(fxv(0), oy0 - 16, fxv(0), oy0 + 3 * 36 + 14, INK3, 1.2))
    b.append(T(fxv(0), oy0 + 3 * 36 + 34, "0", FS, 500, INK3, "middle"))
    b.append(T(fxv(-0.2), oy0 + 3 * 36 + 34, "−20", FS, 500, INK3, "middle"))
    for i, (key, label) in enumerate(others):
        c = cond[key]
        diff = c["vsNowLike"]["shimpyoP07"]["crisisOver30MinShareDiff"]
        y = oy0 + i * 36
        now = c["policies"]["nowLike"]["crisisOver30MinShare"]["value"]
        withs = c["policies"]["shimpyoP07"]["crisisOver30MinShare"]["value"]
        b.append(T(500, y + 5, label, FS, 600, INK2))
        b.append(T(fx0 - 10, y + 5, "%d→%d%%" % (round(now * 100), round(withs * 100)), FS, 700, INK, "end"))
        lo, hi = diff["ci95"]
        b.append(L(fxv(lo), y, fxv(hi), y, ACCENT, 2))
        b.append(C(fxv(diff["mean"]), y, 5.5, ACCENT, WHITE, 1.5))

    # cost: compare with the recommended behaviour (2-hour rest), not with "never rest"
    vs = main["vsNowLike"]
    pp = main["policies"]
    d_stops = vs["shimpyoP07"]["extraStops"]["mean"] - vs["every2h"]["extraStops"]["mean"]
    d_min = vs["shimpyoP07"]["extraTripMin"]["mean"] - vs["every2h"]["extraTripMin"]["mean"]
    r2h = pp["every2h"]["crisisOver30MinShare"]["value"]
    r07 = pp["shimpyoP07"]["crisisOver30MinShare"]["value"]
    now_min = vs["shimpyoP07"]["extraTripMin"]["mean"]
    rest_min = vs["every2h"]["extraTripMin"]["mean"]
    long_share = pp["nowLike"]["stretchOver2h30Share"]["value"]
    cy = y0 + 4 * (bh + gap) + 60
    b.append(TS(16, cy, [("대가 ", 800, INK),
                         ("권장 습관인 2시간마다 휴게소와 비교하면, 쉼표(수락 70%%)는 여정당 정차 %.2f회·%d분이 늘고 "
                          "위기는 %d%%→%d%%로 준다." % (d_stops, round(d_min), round(r2h * 100), round(r07 * 100)),
                          500, INK2)], 15.5))
    b.append(T(16, cy + 23, "지금처럼보다 늘어나는 %d분 중 %d분은 지침이 권하는 2시간 휴식 몫이다(지금처럼은 %d%%의 여정에서 "
               "2시간 30분 넘게 쉬지 않았다)." % (round(now_min), round(rest_min), round(long_share * 100)), 15.5, 500, INK2))
    free_max = max(c["policies"][k]["crisisOver30MinShare"]["value"]
                   for c in d["conditions"] if c["traffic"] == "free" for k in c["policies"])
    free_note = ("정체가 없으면 네 정책 모두 0%. " if free_max == 0
                 else "정체가 없을 때 위기는 정책에 관계없이 %d%% 이하. " % round(free_max * 100 + 0.499))
    foot = ("막대는 위기 비율, 선은 95%% 구간(윌슨). %s실제 사용 결과가 아니라 엔진 코드를 그대로 돌린 시뮬레이션이다"
            "(경부고속도로 서울→부산, 오전 9시 출발, 정체 1~3개 무작위, 사람마다 실제 간격이 사전값과 다름, 개인 기록 없음)."
            % free_note)
    fl = wrap(foot, FS, W - 32)
    b.append(ML(16, cy + 56, fl, FS, 20, 500, INK3))
    H = int(cy + 56 + 20 * (len(fl) - 1) + 14)
    return svg(W, H, b)


# ------------------------------------------------------------------ build

FIGS = {
    "hero": ("fig-hero", fig_hero),
    "scene": ("fig-scene", fig_scene),
    "gapmap": ("fig-gapmap", fig_gapmap),
    "loop": ("fig-loop", fig_loop),
    "storyboard": ("fig2-storyboard", fig_storyboard),
    "models": ("fig-models", fig_models),
    "effect": ("fig-effect", fig_effect),
    "gauge": ("fig1-gauge", fig_gauge),
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
    args = sys.argv[1:]
    if "--hero-screen" in args:
        i = args.index("--hero-screen")
        HERO_SCREEN = os.path.abspath(args[i + 1])
        del args[i:i + 2]
    for p in build_all(ASSETS, set(args) or None):
        w, h = image_size(p)
        print("%s  %dx%d  h/w=%.3f" % (p, w, h, h / float(w)))
