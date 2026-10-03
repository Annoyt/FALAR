#!/usr/bin/env python3
"""Картинки для сайта Falar: снимки экрана приложения (рисованные) и шаги установки.

    python3 design/mockups/landing_pics.py            # в docs/img

Каждая картинка — отдельный SVG без внешних ссылок: сайт не делает ни одного внешнего запроса.
Тексты в картинках придуманные — ни строчки из настоящих разговоров или снимков владельца.
"""
import os
import sys

PLUM, DEEP, GOLD, MINT = "#802244", "#3D0C2A", "#E0B878", "#6FE0BC"
FG, DIM, SOFT, CARD, LINE, TINT, HAIR, SEG = "#1A1420", "#5D5566", "#8A8092", "#FAF7F9", "#E6DFE6", "#F6EAF0", "#F0EAF0", "#F1ECF1"
FONT = "Roboto,'Noto Sans','Segoe UI',Arial,sans-serif"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, s, size, fill=FG, weight=400, anchor="start", extra=""):
    w = f' font-weight="{weight}"' if weight != 400 else ""
    a = f' text-anchor="{anchor}"' if anchor != "start" else ""
    return f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}"{w}{a}{extra}>{esc(s)}</text>'


def svg(w, h, body, defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'font-family="{FONT}">' + (f"<defs>{defs}</defs>" if defs else "") + body + "</svg>\n")


# ---------------------------------------------------------------- снимки приложения

PW, PH = 300, 620          # телефон целиком
SX, SY, SW, SH = 9, 9, 282, 602


def phone(content, defs="", dark=False):
    """Рамка телефона; content рисуется в координатах экрана (0,0 — левый верхний угол экрана)."""
    clip = f'<clipPath id="scr"><rect x="{SX}" y="{SY}" width="{SW}" height="{SH}" rx="31"/></clipPath>'
    grad = (f'<linearGradient id="top" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{PLUM}"/>'
            f'<stop offset="1" stop-color="{DEEP}"/></linearGradient>')
    body = (f'<rect width="{PW}" height="{PH}" rx="40" fill="#141016"/>'
            f'<g clip-path="url(#scr)"><g transform="translate({SX} {SY})">'
            f'<rect width="{SW}" height="{SH}" fill="{"#15101A" if dark else "#fff"}"/>{content}</g></g>')
    return svg(PW, PH, body, clip + grad + defs)


def status_bar(color="#fff"):
    return (text(22, 17, "13:05", 10.5, color, 600) +
            f'<g fill="{color}"><path d="M226 15 l6-6 v6z" opacity=".9"/>'
            f'<rect x="238" y="9" width="17" height="8" rx="2" opacity=".9"/><rect x="255.5" y="11" width="1.6" height="4" rx=".6"/></g>')


def app_bar(title, sub=None, back=False, icons=True):
    s = f'<rect width="{SW}" height="74" fill="url(#top)"/>' + status_bar()
    if back:
        s += '<path d="M28 45 l-8 8 8 8 M20 53 h16" stroke="#fff" stroke-width="2.2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'
    else:
        s += '<path d="M19 46h17M19 53h17M19 60h17" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/>'
    if sub:
        s += text(52, 50, title, 15.5, "#fff", 700) + text(52, 65, sub, 10, "rgba(255,255,255,.72)")
    else:
        s += text(52, 58, title, 15.5, "#fff", 700)
    if icons:
        s += ('<g stroke="#fff" stroke-width="1.9" fill="none" stroke-linecap="round" stroke-linejoin="round">'
              '<path d="M216 47h4l2-3h8l2 3h4a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-20a2 2 0 0 1-2-2V49a2 2 0 0 1 2-2z"/><circle cx="226" cy="54" r="3.6"/></g>'
              '<g fill="#fff"><circle cx="258" cy="46" r="1.9"/><circle cx="258" cy="53" r="1.9"/><circle cx="258" cy="60" r="1.9"/></g>')
    return s


def mic_button(cx, cy, r):
    """Кнопка удержания как в приложении (MicButton): диск, микрофон, FALAR сверху, ГОВОРИ снизу."""
    rd, fs = r * .86, r * .17
    cap = fs * .7
    rt, rb = r * .68 - cap / 2, r * .68 + cap / 2
    u = r * .40 / 11
    d = (f'<linearGradient id="md" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{PLUM}"/><stop offset="1" stop-color="{DEEP}"/></linearGradient>'
         f'<path id="mt" d="M {cx-rt} {cy} A {rt} {rt} 0 0 1 {cx+rt} {cy}"/><path id="mb" d="M {cx-rb} {cy} A {rb} {rb} 0 0 0 {cx+rb} {cy}"/>')
    t = f'font-weight="700" font-size="{fs:.2f}" letter-spacing="{.18*fs:.2f}" fill="{GOLD}"'
    s = (f'<circle cx="{cx}" cy="{cy}" r="{r*.98:.1f}" fill="#fff"/>'
         f'<circle cx="{cx}" cy="{cy}" r="{rd:.1f}" fill="url(#md)"/>'
         f'<circle cx="{cx}" cy="{cy}" r="{r*.53:.1f}" fill="none" stroke="{GOLD}" stroke-opacity=".35" stroke-width="1"/>'
         f'<g transform="translate({cx-12*u:.2f} {cy-12*u:.2f}) scale({u:.3f})" stroke="{GOLD}" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">'
         '<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M19 10 L19 12 A7 7 0 0 1 5 12 L5 10"/><path d="M12 19 L12 22"/></g>'
         f'<text {t}><textPath href="#mt" startOffset="50%" text-anchor="middle">FALAR</textPath></text>'
         f'<text {t}><textPath href="#mb" startOffset="50%" text-anchor="middle">ГОВОРИ</textPath></text>')
    return s, d


def shot_talk():
    s = app_bar("Аптека", "сегодня · 2 собеседника")
    s += f'<circle cx="24" cy="98" r="3.5" fill="{PLUM}"/>' + text(32, 101.5, "Собеседник 1", 10.5, SOFT)
    s += text(18, 128, "Onde fica a farmácia", 20.5, FG, 700) + text(18, 153, "mais próxima?", 20.5, FG, 700)
    s += text(18, 176, "Где ближайшая аптека?", 13.5, DIM)
    for x, w, lbl in ((18, 78, "Улучшить"), (102, 84, "Запомнить"), (192, 34, "⋯")):
        s += f'<rect x="{x}" y="190" width="{w}" height="24" rx="12" fill="#fff" stroke="{LINE}"/>' + text(x + w / 2, 206, lbl, 10.5, FG, 400, "middle")
    s += f'<path d="M18 238h92M172 238h92" stroke="{LINE}"/>' + text(141, 241.5, "ранее", 10, SOFT, 400, "middle")
    bub = [("l", 252, "Boa tarde!", "Добрый день!"), ("r", 302, "Preciso de um remédio", "Мне нужно лекарство"),
           ("l", 352, "Para dor de cabeça?", "От головной боли?"), ("r", 402, "Sim, e para febre", "Да, и от температуры")]
    for side, y, pt, ru in bub:
        w = max(len(pt) * 6.4, len(ru) * 6.0) + 24
        x = 16 if side == "l" else SW - 16 - w
        fill, stroke = (CARD, HAIR) if side == "l" else (TINT, TINT)
        s += f'<rect x="{x:.1f}" y="{y}" width="{w:.1f}" height="42" rx="13" fill="{fill}" stroke="{stroke}"/>'
        s += text(x + 12, y + 17, pt, 11.5, FG, 600) + text(x + 12, y + 33, ru, 10.5, DIM)
    s += f'<rect y="512" width="{SW}" height="90" fill="#fff"/><path d="M0 512.5h{SW}" stroke="{HAIR}"/>'
    m, d = mic_button(141, 520, 46)
    s += m
    s += ('<g stroke="' + DIM + '" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round">'
          '<rect x="34" y="530" width="22" height="15" rx="3"/><path d="M38 535h2M43 535h2M48 535h2M39 540h12"/></g>')
    s += text(45, 562, "Ввод", 9.5, DIM, 400, "middle")
    s += (f'<rect x="210" y="527" width="56" height="22" rx="11" fill="{CARD}" stroke="{LINE}"/>'
          f'<rect x="210" y="527" width="28" height="22" rx="11" fill="{PLUM}"/>'
          + text(224, 542, "PT", 9.5, "#fff", 700, "middle") + text(252, 542, "RU", 9.5, DIM, 700, "middle"))
    s += text(238, 562, "Слушать", 9.5, DIM, 400, "middle")
    s += f'<rect x="111" y="590" width="60" height="3.5" rx="1.75" fill="{FG}" opacity=".35"/>'
    return phone(s, d)


def shot_photo():
    s = '<rect width="282" height="602" fill="#3a2f28"/>'
    # доска меню — «снимок»
    s += '<rect x="14" y="92" width="254" height="372" rx="6" fill="#7a4f33"/><rect x="24" y="102" width="234" height="352" rx="3" fill="#263029"/>'
    s += text(141, 140, "CARDÁPIO", 22, "#EDEBE3", 700, "middle", ' letter-spacing="2" opacity=".55"')
    rows = [("Pão de queijo", "6,00", "Сырная булочка"), ("Café com leite", "5,50", "Кофе с молоком"),
            ("Suco de laranja", "8,00", "Апельсиновый сок"), ("Coxinha", "7,00", "Пирожок с курицей"),
            ("Açaí na tigela", "15,00", "Асаи в миске")]
    y = 186
    for pt, price, ru in rows:
        s += text(40, y, pt, 14, "#EDEBE3", 400, "start", ' opacity=".45"') + text(242, y, price, 14, "#EDEBE3", 400, "end", ' opacity=".45"')
        s += f'<rect x="34" y="{y-16}" width="214" height="23" rx="5" fill="#fff" fill-opacity=".93"/>'
        s += text(42, y, ru, 13, FG, 600) + text(240, y, price, 13, FG, 600, "end")
        y += 52
    # снимок открыт в приложении: шапка поверх
    s += '<rect width="282" height="74" fill="rgba(20,10,16,.82)"/>' + status_bar()
    s += '<path d="M28 45 l-8 8 8 8 M20 53 h16" stroke="#fff" stroke-width="2.2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'
    s += text(52, 58, "Снимок", 15.5, "#fff", 700)
    s += '<rect y="492" width="282" height="110" fill="rgba(20,10,16,.82)"/>'
    s += f'<rect x="56" y="514" width="170" height="34" rx="17" fill="rgba(255,255,255,.12)"/><rect x="58" y="516" width="84" height="30" rx="15" fill="{GOLD}"/>'
    s += text(100, 535.5, "Перевод", 12, DEEP, 700, "middle") + text(184, 535.5, "Оригинал", 12, "#fff", 400, "middle")
    s += f'<rect x="111" y="590" width="60" height="3.5" rx="1.75" fill="#fff" opacity=".45"/>'
    return phone(s)


def shot_words():
    s = '<rect width="282" height="602" fill="#F4F1F4"/>' + app_bar("Слова", back=True, icons=False)
    s += '<g fill="#fff"><circle cx="258" cy="46" r="1.9"/><circle cx="258" cy="53" r="1.9"/><circle cx="258" cy="60" r="1.9"/></g>'
    s += f'<rect x="14" y="88" width="254" height="32" rx="10" fill="{SEG}"/><rect x="17" y="91" width="82" height="26" rx="8" fill="#fff"/>'
    for cx, lbl, on in ((58, "Слова", True), (141, "Фразы", False), (224, "Знаю", False)):
        s += text(cx, 108.5, lbl, 11.5, FG if on else DIM, 700 if on else 400, "middle")
    s += f'<rect x="14" y="132" width="254" height="62" rx="15" fill="url(#top)"/>'
    s += text(28, 158, "Повторение на слух", 13, "#fff", 700) + text(28, 175, "сегодня — 8 слов", 10.5, "rgba(255,255,255,.75)")
    s += f'<rect x="184" y="148" width="72" height="30" rx="15" fill="{GOLD}"/>' + text(220, 167.5, "▶ Начать", 11, DEEP, 700, "middle")
    words = [("obrigado", "спасибо", 14), ("quanto custa", "сколько стоит", 9), ("farmácia", "аптека", 6),
             ("amanhã", "завтра", 5), ("troco", "сдача", 4), ("devagar", "медленно", 3)]
    y = 206
    for pt, ru, n in words:
        s += f'<rect x="14" y="{y}" width="254" height="46" rx="12" fill="#fff" stroke="{HAIR}"/>'
        s += text(28, y + 20, pt, 13, FG, 700) + text(28, y + 36, ru, 11, DIM)
        s += text(222, y + 28, f"×{n}", 11, SOFT, 400, "end")
        s += (f'<g transform="translate(232 {y+13})" stroke="{PLUM}" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round">'
              '<path d="M2 7h4l5-4v14l-5-4H2z"/><path d="M14 6.5a4.5 4.5 0 0 1 0 7"/></g>')
        y += 52
    s += f'<rect y="530" width="282" height="72" fill="#fff"/><path d="M0 530.5h282" stroke="{HAIR}"/>'
    s += f'<rect x="14" y="541" width="210" height="34" rx="17" fill="{CARD}" stroke="{LINE}"/>' + text(30, 562, "Своё слово: отель, улица, имя", 11, SOFT)
    s += f'<circle cx="251" cy="558" r="17" fill="{PLUM}"/><path d="M251 551v14M244 558h14" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/>'
    s += f'<rect x="111" y="590" width="60" height="3.5" rx="1.75" fill="{FG}" opacity=".35"/>'
    return phone(s)


SHOTS = {"shot-talk.svg": shot_talk, "shot-photo.svg": shot_photo, "shot-words.svg": shot_words}


# ---------------------------------------------------------------- шаги установки
# Кусок экрана телефона 180×210: что человек увидит и куда нажать. Нажать — золотая рамка и касание.
# Слова на кнопках — как в системе; остальной текст диалога — серыми полосками: его всё равно не читают.

W, H = 180, 210
SYS_BG, SYS_FG, SYS_DIM, BLUE, BAR = "#EEF0F4", "#1B1B1F", "#5E5E66", "#0B57D0", "#C9CCD3"


def screen(body, bg=SYS_BG, dark_status=True):
    c = SYS_FG if dark_status else "#fff"
    st = (text(12, 13, "13:05", 8, c, 600) +
          f'<g fill="{c}" opacity=".85"><path d="M147 11 l5-5 v5z"/><rect x="156" y="6" width="12" height="6" rx="1.5"/></g>')
    return svg(W, H, f'<rect width="{W}" height="{H}" fill="{bg}"/>' + st + body)


def bars(x, y, widths, gap=9, h=5, fill=BAR):
    return "".join(f'<rect x="{x}" y="{y + i*gap}" width="{w}" height="{h}" rx="{h/2}" fill="{fill}"/>' for i, w in enumerate(widths))


def ring(x, y, w, h, r=None):
    """Золотая рамка вокруг того, что нажать, и след касания."""
    r = h / 2 if r is None else r
    cx, cy = x + w * .78, y + h + 5          # касание — под надписью, чтобы её не закрывать
    return (f'<rect x="{x-4}" y="{y-4}" width="{w+8}" height="{h+8}" rx="{r+4}" fill="none" stroke="{GOLD}" stroke-width="3.2"/>'
            f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="10" fill="{PLUM}" fill-opacity=".2"/>'
            f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="4.5" fill="{PLUM}" fill-opacity=".85" stroke="#fff" stroke-width="1.2"/>')


def pill(x, y, w, h, label, fill=BLUE, fg="#fff", size=9.5, weight=700):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h/2}" fill="{fill}"/>' + text(x + w / 2, y + h / 2 + size * .36, label, size, fg, weight, "middle")


def tbtn(x, y, label, fg=BLUE, size=9.5, anchor="middle"):
    """Текстовая кнопка системного диалога."""
    return text(x, y, label, size, fg, 700, anchor)


def dialog(y, h, inner, x=12, w=W - 24):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="#fff"/>' + inner


def sheet(y, inner):
    return f'<rect x="0" y="{y}" width="{W}" height="{H-y+16}" rx="16" fill="#fff"/>' + inner


def toggle(x, y, on=True):
    if on:
        return f'<rect x="{x}" y="{y}" width="30" height="17" rx="8.5" fill="{BLUE}"/><circle cx="{x+21.5}" cy="{y+8.5}" r="6" fill="#fff"/>'
    return f'<rect x="{x}" y="{y}" width="30" height="17" rx="8.5" fill="#fff" stroke="#74777F" stroke-width="1.5"/><circle cx="{x+8.5}" cy="{y+8.5}" r="4.5" fill="#74777F"/>'


def app_icon(x, y, s):
    """Значок Falar: ухо золотом на сливовом, точка-звук мятная."""
    k = s / 512
    return (f'<g transform="translate({x} {y}) scale({k:.4f})">'
            f'<rect width="512" height="512" rx="112" fill="{PLUM}"/>'
            f'<path d="M262 370c0 55-75 70-100 20-20-40-10-70-55-110-60-55-30-200 90-200 80 0 100 80 150 80 60 0 70-40 70-60" '
            f'fill="none" stroke="{GOLD}" stroke-width="30" stroke-linecap="round"/>'
            f'<circle cx="420" cy="100" r="28" fill="{GOLD}"/><circle cx="170" cy="262" r="24" fill="{MINT}"/>'
            f'<path d="M215 218a60 60 0 0 1 0 90" fill="none" stroke="{MINT}" stroke-width="16" stroke-linecap="round"/></g>')


def step_models():
    """Первый экран Falar: микрофон уже разрешён, остаётся скачать модели (BuildSetup в MainActivity)."""
    s = text(14, 40, "Falar", 17, SYS_FG, 700) + bars(14, 48, [140, 96], 8, 4)
    s += text(14, 78, "1. Микрофон", 10, SYS_FG, 700) + text(14, 91, "✓ разрешён", 9, "#18775A", 600)
    s += text(14, 111, "2. Что нужно", 10, SYS_FG, 700)
    for i, w in enumerate((92, 70, 84)):
        y = 118 + i * 11
        s += f'<rect x="14" y="{y}" width="7" height="7" rx="1.5" fill="{PLUM}"/><path d="M15.6 {y+3.6} l1.6 1.6 2.6-3" stroke="#fff" stroke-width="1.2" fill="none"/>'
        s += f'<rect x="26" y="{y+1}" width="{w}" height="5" rx="2.5" fill="{BAR}"/>'
    s += text(14, 166, "3. Модели", 10, SYS_FG, 700) + bars(26, 172, [112], 8, 4)
    s += f'<rect x="14" y="182" width="152" height="22" rx="8" fill="#F6F2F5" stroke="#E0D7E0"/>' + text(90, 196.5, "скачать 1530 МБ", 9.5, SYS_FG, 700, "middle")
    s += ring(14, 182, 152, 22, 8)
    s += ('<g transform="translate(148 30)" stroke="' + PLUM + '" stroke-width="2" fill="none" stroke-linecap="round">'
          '<path d="M0 6a15 15 0 0 1 20 0M3.5 9.5a10 10 0 0 1 13 0M7 13a5 5 0 0 1 6 0"/><circle cx="10" cy="16.5" r=".8" fill="' + PLUM + '"/></g>')
    return screen(s, "#fff")


SCRIM = '<rect width="180" height="210" fill="#000" fill-opacity=".42"/>'


def page_behind():
    """Сайт Falar за диалогом: сливовая шапка с золотой кнопкой и светлый низ."""
    return (f'<rect y="18" width="180" height="80" fill="{PLUM}"/><rect x="40" y="70" width="100" height="16" rx="8" fill="{GOLD}"/>'
            f'<rect x="76" y="30" width="28" height="28" rx="7" fill="{DEEP}"/>' + bars(16, 112, [110, 140, 90, 120, 60], 12, 6, "#DADDE3"))


def step_chrome():
    """Chrome: «Файл может быть опасным» → «Все равно скачать» (строки Chromium, ru)."""
    s = page_behind() + SCRIM
    s += dialog(46, 132, text(22, 72, "Файл может быть", 12, SYS_FG, 700) + text(22, 87, "опасным", 12, SYS_FG, 700)
                + text(22, 106, "Все равно скачать файл", 9.5, SYS_DIM) + text(22, 119, "Falar.apk (83 МБ)?", 9.5, SYS_DIM)
                + tbtn(22, 160, "Отмена", BLUE, 9.5, "start") + tbtn(158, 160, "Все равно скачать", BLUE, 9.5, "end"), x=10, w=160)
    s += ring(70, 150, 90, 14, 7)
    return screen(s)


def step_open():
    """Chrome: файл скачан — «Открыть»."""
    s = page_behind()
    s += '<rect x="8" y="150" width="164" height="42" rx="12" fill="#2F3033"/>'
    s += ('<g transform="translate(18 161)" stroke="#C4C7C5" stroke-width="1.6" fill="none" stroke-linejoin="round">'
          '<path d="M2 1h9l5 5v13H2z"/><path d="M11 1v5h5"/></g>')
    s += text(42, 167, "Falar.apk", 10.5, "#fff", 700) + text(42, 180, "Скачано", 8.5, "#C4C7C5")
    s += tbtn(160, 175, "Открыть", "#A8C7FA", 11, "end")
    s += ring(110, 163, 54, 16, 8)
    return screen(s)


def step_unknown():
    """Запрет «неизвестных приложений» для браузера → «Настройки» (AOSP PackageInstaller, ru)."""
    s = page_behind() + SCRIM
    globe = ('<g transform="translate(78 58)"><circle cx="12" cy="12" r="11" fill="#E8F0FE"/>'
             '<g stroke="' + BLUE + '" stroke-width="1.5" fill="none"><circle cx="12" cy="12" r="7"/><path d="M5 12h14M12 5c-3 4-3 10 0 14M12 5c3 4 3 10 0 14"/></g></g>')
    s += dialog(48, 130, globe + text(90, 100, "Chrome", 10, SYS_FG, 700, "middle")
                + text(24, 120, "В целях безопасности…", 9.5, SYS_DIM) + bars(24, 128, [128, 96], 9, 5)
                + tbtn(90, 164, "Отмена", BLUE, 10, "end") + tbtn(158, 164, "Настройки", BLUE, 10, "end"), x=12, w=156)
    s += ring(100, 153, 60, 15, 7.5)
    return screen(s)


def step_allow():
    """Настройки: «Разрешить установку из этого источника» — включить и вернуться назад."""
    s = '<rect y="18" width="180" height="192" fill="#fff"/>'
    s += (f'<circle cx="21" cy="38" r="12" fill="none" stroke="{GOLD}" stroke-width="2.4" stroke-dasharray="4 3"/>'
          f'<path d="M26 38h-11M20 32l-6 6 6 6" stroke="{SYS_FG}" stroke-width="1.9" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
    s += text(14, 72, "Установка неизвестных", 11.5, SYS_FG, 700) + text(14, 86, "приложений", 11.5, SYS_FG, 700)
    s += ('<g transform="translate(14 100)"><circle cx="12" cy="12" r="11" fill="#E8F0FE"/>'
          '<g stroke="' + BLUE + '" stroke-width="1.5" fill="none"><circle cx="12" cy="12" r="7"/><path d="M5 12h14M12 5c-3 4-3 10 0 14M12 5c3 4 3 10 0 14"/></g></g>')
    s += text(44, 115, "Chrome", 10.5, SYS_FG, 700)
    s += '<path d="M14 134.5h152" stroke="#E3E3E8"/>'
    s += text(14, 156, "Разрешить установку", 10.5, SYS_FG) + text(14, 170, "из этого источника", 10.5, SYS_FG)
    s += toggle(134, 152, True)
    s += ring(134, 152, 30, 17)
    s += bars(14, 188, [120, 80], 9, 4, "#E3E3E8")
    return screen(s)


def step_install():
    """Установщик Android: «Установить приложение?» → «Установить»."""
    s = page_behind() + SCRIM
    s += dialog(64, 120, app_icon(22, 78, 26) + text(56, 96, "Falar", 12, SYS_FG, 700)
                + text(22, 128, "Установить приложение?", 10.5, SYS_FG)
                + tbtn(84, 166, "Отмена", BLUE, 10, "end") + tbtn(158, 166, "Установить", BLUE, 10, "end"), x=12, w=156)
    s += ring(94, 155, 66, 15, 7.5)
    return screen(s)


def if_protect():
    """Play Защита для незнакомого приложения: «Рекомендуется проверка приложения».
    Кнопки по-английски — Scan app / Don't install app; русские подписи ещё не сверены с телефоном."""
    s = page_behind() + SCRIM
    shield = (f'<g transform="translate(78 54)"><path d="M12 1l10 4v7c0 6-4.5 10-10 12C6.5 22 2 18 2 12V5z" fill="#E6F4EA" stroke="#188038" stroke-width="1.6"/>'
              f'<path d="M7.5 12.5l3 3 6-6" stroke="#188038" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></g>')
    s += dialog(46, 150, shield + text(90, 98, "Рекомендуется проверка", 10.5, SYS_FG, 700, "middle") + text(90, 111, "приложения", 10.5, SYS_FG, 700, "middle")
                + bars(26, 120, [128, 110], 8, 4)
                + pill(24, 142, 132, 20, "Проверить приложение", BLUE, "#fff", 9.5)
                + tbtn(90, 182, "Не устанавливать", BLUE, 9.5), x=12, w=156)
    s += ring(24, 142, 132, 20)
    return screen(s)


def if_xiaomi():
    """Xiaomi HyperOS: окно риска с обратным отсчётом — отметить и нажать «OK» (строки global-прошивки, ru)."""
    s = '<rect y="18" width="180" height="192" fill="#fff"/>' + bars(14, 30, [90, 130], 10, 6, "#E3E3E8") + SCRIM
    s += '<rect x="6" y="62" width="168" height="144" rx="18" fill="#fff"/>'
    s += text(90, 84, "Внимание", 12, SYS_FG, 700, "middle") + bars(20, 94, [140, 128, 100], 9, 4)
    s += (f'<rect x="20" y="126" width="11" height="11" rx="2.5" fill="{BLUE}"/>'
          '<path d="M22.5 131.5l2.4 2.4 4-4.4" stroke="#fff" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
    s += text(37, 131, "Я осознаю возможные", 9, SYS_FG) + text(37, 142, "риски…", 9, SYS_FG)
    s += pill(16, 160, 70, 26, "Отмена", "#EEF0F4", SYS_FG, 10, 600) + pill(94, 160, 70, 26, "OK (7)", BLUE, "#fff", 10.5)
    s += ring(94, 160, 70, 26)
    return screen(s)


def if_samsung():
    """Samsung One UI: «Автоблокировка» мешает ставить мимо магазинов — выключить.
    Путь: Настройки → Безопасность и конфиденциальность → Автоблокировка (samsung.com/ru)."""
    s = '<rect y="18" width="180" height="192" fill="#F6F6F8"/>'
    s += f'<path d="M22 38h-9M17 33l-5 5 5 5" stroke="{SYS_FG}" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'
    s += text(14, 82, "Автоблокировка", 16, SYS_FG, 700) + bars(14, 94, [130, 96], 9, 4, "#DADDE3")
    s += '<rect x="10" y="122" width="160" height="42" rx="16" fill="#fff"/>'
    s += text(24, 147, "Автоблокировка", 11, SYS_FG, 700) + toggle(128, 134.5, True)
    s += ring(128, 134.5, 30, 17)
    s += '<rect x="10" y="172" width="160" height="30" rx="14" fill="#fff"/>' + bars(24, 185, [96], 9, 4, "#DADDE3")
    return screen(s)


# Шаг 3 — разрешение «неизвестных источников» — две картинки: запрет с кнопкой «Настройки» и сам переключатель.
STEPS = {"step-1.svg": step_chrome, "step-2.svg": step_open, "step-3a.svg": step_unknown, "step-3b.svg": step_allow,
         "step-4.svg": step_install, "step-5.svg": step_models}
IFS = {"if-protect.svg": if_protect, "if-xiaomi.svg": if_xiaomi, "if-samsung.svg": if_samsung}


def main(out):
    os.makedirs(out, exist_ok=True)
    pics = {**SHOTS, **STEPS, **IFS}
    for name, fn in pics.items():
        with open(os.path.join(out, name), "w", encoding="utf-8") as f:
            f.write(fn())
    print("ok:", ", ".join(pics))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "img"))
