#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Подсказка для чтения вслух: как слово ЗВУЧИТ в Бразилии, кириллицей и со знаком ударения.

Зеркало Translit.say() из bench/apk/src/dev/agenttranslator/Translit.java — те же таблицы, тот же
порядок правил. Нужно, чтобы проверять правила на фразах затравки разговорника, а не на именах
(для имён есть translit_pt_ru.py), и чтобы убедиться, что порт на Java совпадает с эталоном.

  python3 tools/translit_say.py            # прогнать эталон REFERENCE, показать расхождения
  python3 tools/translit_say.py --java     # то же, плюс сравнить Java и Python слово в слово
  python3 tools/translit_say.py "Onde fica o banheiro?"

Эталон писался руками ДО запуска кода; расхождения кода с эталоном разбираются по одному
и либо чинят правило, либо остаются в списке известных пределов (см. results/2026-09-17-translit-say.md).
В эталоне знак ударения записан апострофом после гласной («о'нджи»), при сравнении он
превращается в комбинируемый акут U+0301, как в Java.
"""
import json
import os
import subprocess
import sys
import tempfile
import unicodedata

SAY_V = "aeiouáéíóúâêôãõà"
ACUTE = "́"
SAY_NOMARK = {"de", "do", "da", "dos", "das", "e", "o", "a", "os", "as", "um", "uns", "em", "com", "que", "se",
              "me", "te", "lhe", "nos", "por", "ao", "aos", "no", "na", "nas", "sem", "ou", "mas", "à", "às"}
SAY_EXC = {"táxi": "та́кси", "fixo": "фи́ксу", "sexo": "се́ксу", "tóxico": "то́ксику", "próximo": "про́симу",
           "máximo": "ма́симу", "auxílio": "аузи́лиу", "sintaxe": "синта́кси", "texto": "те́сту", "exato": "иза́ту",
           "wifi": "уай-фай"}
BASE = {"á": "a", "à": "a", "â": "a", "ã": "a", "é": "e", "ê": "e", "í": "i", "ó": "o", "ô": "o", "õ": "o", "ú": "u"}


def say_v(c): return c in SAY_V
def say_acc(c): return c in "áéíóúâêô"
def say_base(c): return BASE.get(c, c)


def say_diph(prev, c):
    if say_acc(c): return False
    bp, bc = say_base(prev), say_base(c)
    if prev == "ã" and c in "oe": return True
    if prev == "õ" and c == "e": return True
    if c in "ãõ": return False
    return bc in "iu" and bp != bc and bp in "aeiou"


def say_nuclei(w):
    n = []; last_v = -2
    for i, c in enumerate(w):
        if not say_v(c): continue
        prev = w[i - 1] if i > 0 else " "
        if c == "u" and prev in "qg" and i + 1 < len(w) and say_v(w[i + 1]): continue
        if not (last_v == i - 1 and say_diph(prev, c)): n.append(i)
        last_v = i
    return n


def say_nucleus_of(n, i):
    k = -1
    for j, s in enumerate(n):
        if s <= i: k = j
    return k


def say_stress(w, n):
    if len(n) <= 1 or w in SAY_NOMARK: return -1
    for i, c in enumerate(w):
        if say_acc(c): return say_nucleus_of(n, i)
    e = w[:-1] if w.endswith("s") and len(w) > 1 else w
    last = e[-1]
    oxy = last in "iurlzxnãõ" or e.endswith(("ão", "ãe", "õe", "im", "um", "om"))
    return len(n) - 1 if oxy else len(n) - 2


def say_core(w):
    if w in SAY_EXC: return SAY_EXC[w]
    nuc = say_nuclei(w); stress = say_stress(w, nuc)
    out = ""; n = len(w); i = 0
    while i < n:
        c = w[i]; prev = w[i - 1] if i > 0 else " "; nx = w[i + 1] if i + 1 < n else " "; nx2 = w[i + 2] if i + 2 < n else " "
        final_e = c == "e" and (i == n - 1 or (nx == "s" and i == n - 2))
        nucleus = i in nuc
        mark = len(out) if nucleus and say_nucleus_of(nuc, i) == stress else -1
        if say_v(c):
            glide = c == "u" and prev in "qg" and say_v(nx)
            second = not nucleus and not glide
            if glide: piece = "" if say_base(nx) in "ei" else "у"
            elif second: piece = "й" if say_base(c) == "i" else ("" if prev == "o" else "у")
            elif c == "ã":
                if nx == "o": piece = "ау"; i += 1
                elif nx == "e": piece = "айн"; i += 1
                else: piece = "а" if i == n - 1 else "ан"
            elif c == "õ":
                if nx == "e": piece = "ойн"; i += 1
                else: piece = "он"
            else:
                b = say_base(c)
                if b == "a":
                    if nx == "m" and i == n - 2: piece = "ау"; i += 1
                    else: piece = "а"
                elif b == "e":
                    after_glide = prev == "u" and i >= 2 and w[i - 2] in "qg"
                    if final_e and not say_acc(c): piece = "и"
                    elif i == 0 and nx == "x" and say_v(nx2): piece = "и"
                    else: piece = "э" if (i == 0 or (say_v(prev) and not after_glide)) else "е"
                elif b == "i": piece = "и"
                elif b == "o": piece = "у" if (not say_acc(c) and (i == n - 1 or (nx == "s" and i == n - 2))) else "о"
                else: piece = "у"
        else:
            if c == "b": piece = "б"
            elif c == "c":
                if nx == "h": piece = "ш"; i += 1
                else: piece = "с" if nx in "eiéêí" and nx != " " else "к"
            elif c == "ç": piece = "с"
            elif c == "d": piece = "дж" if (say_base(nx) == "i" or (nx == "e" and (i + 1 == n - 1 or (nx2 == "s" and i + 1 == n - 2)))) else "д"
            elif c == "f": piece = "ф"
            elif c == "g":
                if nx == "u" and say_base(nx2) in "ei" and nx2 != " ": piece = "г"; i += 1
                else: piece = "ж" if nx in "eiéêí" and nx != " " else "г"
            elif c == "h": piece = ""
            elif c == "j": piece = "ж"
            elif c == "k": piece = "к"
            elif c == "l":
                if nx == "h": piece = "ль"; i += 1
                else: piece = "л" if say_v(nx) else "у"
            elif c == "m": piece = "н" if (i == n - 1 or (nx == "s" and i == n - 2)) else "м"
            elif c == "n":
                if nx == "h": piece = "нь"; i += 1
                else: piece = "н"
            elif c == "p": piece = "п"
            elif c == "q": piece = "к"
            elif c == "r":
                if nx == "r": piece = "х"; i += 1
                else: piece = "х" if (i == 0 or prev in "nls") else "р"
            elif c == "s":
                if nx == "s": piece = "с"; i += 1
                else: piece = "з" if ((say_v(prev) and say_v(nx)) or (nx in "bdglmnrv" and nx != " ")) else "с"
            elif c == "t":
                if nx == "c" and nx2 == "h": piece = "ч"; i += 2
                else: piece = "ч" if (say_base(nx) == "i" or (nx == "e" and (i + 1 == n - 1 or (nx2 == "s" and i + 1 == n - 2)))) else "т"
            elif c in "vw": piece = "в"
            elif c == "x": piece = "з" if (i == 1 and prev == "e" and say_v(nx)) else "ш"
            elif c == "y": piece = "и"
            elif c == "z": piece = "с" if i == n - 1 else "з"
            else: piece = c
        out += piece
        if mark >= 0 and piece: out = out[:mark + 1] + ACUTE + out[mark + 1:]
        i += 1
    return out.replace("ьа", "ья").replace("ьу", "ью").replace("ьэ", "ье").replace("ьо", "ьё")


def say(word):
    if not word: return word
    a, b = 0, len(word)
    while a < b and not word[a].isalpha(): a += 1
    while b > a and not word[b - 1].isalpha(): b -= 1
    if a >= b: return word
    core = unicodedata.normalize("NFC", word[a:b])
    out = word[:a]
    for k, p in enumerate(core.split("-")):
        if k > 0: out += "-"
        if not p: continue
        r = say_core(p.lower())
        if r and p[0].isupper(): r = r[0].upper() + r[1:]
        out += r
    return out + word[b:]


def say_phrase(s): return " ".join(say(w) for w in s.split(" "))


# Эталон: фразы затравки разговорника (bench/apk/phrasebook_seed.json) и то, как их прочитал бы
# бразилец, — записано руками до запуска кода. Апостроф после гласной — ударение.
REFERENCE = [
    ("Olá", "ола'"), ("Bom dia", "бон джи'а"), ("Boa tarde", "бо'а та'рджи"), ("Boa noite", "бо'а но'йчи"),
    ("Tudo bem?", "ту'ду бен?"), ("Obrigado", "обрига'ду"), ("Muito obrigado", "му'йту обрига'ду"),
    ("De nada", "джи на'да"), ("Por favor", "пор фаво'р"), ("Com licença", "кон лисе'нса"),
    ("Desculpe", "деску'упи"), ("Sim", "син"), ("Não", "нау"), ("Talvez", "тауве'с"), ("Claro", "кла'ру"),
    ("Entendi", "энтенджи'"), ("Não entendi", "нау энтенджи'"),
    ("Pode repetir, por favor?", "по'джи хепечи'р, пор фаво'р?"),
    ("Fale mais devagar, por favor", "фа'ли майс девага'р, пор фаво'р"),
    ("Eu não falo português", "эу нау фа'лу португе'с"), ("Você fala russo?", "восе' фа'ла ху'су?"),
    ("Você fala inglês?", "восе' фа'ла ингле'с?"), ("Quanto custa?", "куа'нту ку'ста?"), ("Muito caro", "му'йту ка'ру"),
    ("Pode fazer desconto?", "по'джи фазе'р деско'нту?"), ("Eu quero comprar", "эу ке'ру компра'р"),
    ("Onde eu pago?", "о'нджи эу па'гу?"), ("Aceita cartão?", "асе'йта карта'у?"), ("Dinheiro", "джинье'йру"),
    ("Onde fica o banheiro?", "о'нджи фи'ка у банье'йру?"), ("Onde fica o metrô?", "о'нджи фи'ка у метро'?"),
    ("Onde fica o hospital?", "о'нджи фи'ка у оспита'у?"), ("Onde fica a farmácia?", "о'нджи фи'ка а фарма'сиа?"),
    ("Como chegar?", "ко'му шега'р?"), ("É longe?", "э ло'нжи?"), ("É perto?", "э пе'рту?"),
    ("À esquerda", "а эске'рда"), ("À direita", "а джире'йта"), ("Em frente", "эн фре'нчи"),
    ("Eu preciso de ajuda", "эу преси'зу джи ажу'да"), ("Me ajude", "ми ажу'джи"), ("Pode me ajudar?", "по'джи ми ажуда'р?"),
    ("Estou perdido", "эсто' перджи'ду"), ("Chame a polícia", "ша'ми а поли'сиа"),
    ("Chame uma ambulância", "ша'ми у'ма амбула'нсиа"), ("Estou doente", "эсто' доэ'нчи"),
    ("Preciso de um médico", "преси'зу джи ун ме'джику"), ("Dói aqui", "дой аки'"), ("Eu gosto", "эу го'сту"),
    ("Ótimo", "о'чиму"), ("Que horas são?", "ки о'рас сау?"), ("Tchau", "чау"), ("Até logo", "ате' ло'гу"),
    ("Até amanhã", "ате' аманья'"), ("Prazer em conhecer", "празе'р эн коньесе'р"), ("Meu nome é", "меу но'ми э"),
    ("Qual é o seu nome?", "куау э у сеу но'ми?"), ("De onde você é?", "джи о'нджи восе' э?"),
    ("Eu sou da Rússia", "эу со да ху'сиа"), ("Água", "а'гуа"), ("Comida", "коми'да"), ("Café", "кафе'"),
    ("Cerveja", "серве'жа"), ("A conta, por favor", "а ко'нта, пор фаво'р"), ("Cardápio", "карда'пиу"),
    ("Garçom", "гарсо'н"), ("Está delicioso", "эста' делисио'зу"), ("Táxi", "та'кси"), ("Ônibus", "о'нибус"),
    ("Aeroporto", "аэропо'рту"), ("Hotel", "оте'у"), ("Praia", "пра'йа"), ("Supermercado", "супермерка'ду"),
    ("oi", "ой"), ("como vai?", "ко'му вай?"), ("como você está?", "ко'му восе' эста'?"), ("obrigada", "обрига'да"),
    ("desculpa", "деску'упа"), ("pode repetir?", "по'джи хепечи'р?"),
    ("eu falo um pouco de português", "эу фа'лу ун по'ку джи португе'с"), ("quanto custa isso?", "куа'нту ку'ста и'су?"),
    ("barato", "бара'ту"), ("troco", "тро'ку"), ("vire à esquerda", "ви'ри а эске'рда"), ("vire à direita", "ви'ри а джире'йта"),
    ("estou perdida", "эсто' перджи'да"), ("está bom", "эста' бон"), ("está ótimo", "эста' о'чиму"),
    ("eu sou russo", "эу со ху'су"), ("eu sou russa", "эу со ху'са"), ("posso usar seu telefone?", "по'су уза'р сеу телефо'ни?"),
    # Слова, которых в затравке нет, но без которых подсказка врёт на первом же шаге: r, l, tch, ão, x, s
    ("Rua Brasil gente tempo", "ху'а брази'у же'нчи те'мпу"), ("mesmo caixa exame irmã", "ме'зму ка'йша иза'ми ирма'"),
    ("carro guarda-chuva falam", "ка'ху гуа'рда-шу'ва фа'лау"), ("coração mãe pão lições", "кораса'у майн пау лисо'йнс"),
]


def with_acute(s):
    return s.replace("'", ACUTE)


def compare(java=False):
    total = 0; bad = []
    jav = {}
    if java:
        jav = run_java([p for p, _ in REFERENCE])
    jbad = []
    for pt, exp in REFERENCE:
        got = say_phrase(pt); want = with_acute(exp)
        gw, ww = got.lower().split(" "), want.lower().split(" ")   # эталон строчными: регистр код зеркалит со входа
        for a, b in zip(gw, ww):
            total += 1
            if a != b: bad.append((pt, b, a))
        if java and jav.get(pt) != got: jbad.append((pt, got, jav.get(pt)))
    print(f"слов проверено: {total}, совпало с эталоном: {total - len(bad)}, расхождений: {len(bad)}")
    for pt, want, got in bad: print(f"  {pt!r}: эталон {want!r}, код {got!r}")
    if java:
        print(f"Java против Python: фраз {len(REFERENCE)}, расходятся {len(jbad)}")
        for pt, py, jv in jbad: print(f"  {pt!r}: python {py!r}, java {jv!r}")
    return len(bad), len(jbad)


def run_java(phrases):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = os.path.join(root, "bench/apk/src/dev/agenttranslator/Translit.java")
    tmp = tempfile.mkdtemp(prefix="say")
    harness = os.path.join(tmp, "Harness.java")
    with open(harness, "w", encoding="utf-8") as f:
        f.write('package dev.agenttranslator;\nimport java.io.*;\npublic class Harness { public static void main(String[] a) throws Exception {\n'
                '  BufferedReader r = new BufferedReader(new InputStreamReader(System.in, "UTF-8")); String l;\n'
                '  while ((l = r.readLine()) != null) { StringBuilder b = new StringBuilder(); for (String w : l.split(" ")) { if (b.length() > 0) b.append(\' \'); b.append(Translit.say(w)); } System.out.println(b); } } }\n')
    subprocess.check_call(["javac", "-encoding", "UTF-8", "-d", tmp, src, harness])
    out = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", tmp, "dev.agenttranslator.Harness"], input="\n".join(phrases) + "\n",
                         capture_output=True, text=True, encoding="utf-8", check=True).stdout.splitlines()
    return dict(zip(phrases, out))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        for a in args: print(say_phrase(a))
        sys.exit(0)
    bad, jbad = compare(java="--java" in sys.argv)
    sys.exit(1 if jbad else 0)
