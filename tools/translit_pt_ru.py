#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Практическая транскрипция бразильских имён собственных на кириллицу.

Прототип на Python, чтобы прогнать правила по списку настоящих названий,
прежде чем переносить в TextRules.java. Правила по традиции Гиляревского/Ермоловича
с бразильским произношением: конечное безударное -o -> -у (São Paulo -> Сан-Паулу),
-ão -> -ан, lh -> ль, nh -> нь, ch -> ш, j -> ж, s между гласными -> з.

  python3 tools/translit_pt_ru.py            # прогнать встроенный набор
  python3 tools/translit_pt_ru.py "Rua Augusta"
"""
import re
import sys
import unicodedata

VOWELS = set('aeiouáéíóúâêôãõàäëïöü')

# Диграфы и особые сочетания — проверяются раньше одиночных букв, порядок важен.
DIGRAPHS = [
    ('lh', 'ль'), ('nh', 'нь'), ('ch', 'ш'),
    ('ss', 'с'), ('rr', 'рр'), ('sc', 'с'), ('sç', 'с'), ('xc', 'с'),
    ('qu', 'к'), ('gu', 'г'),
    ('ão', 'ан'), ('ãe', 'айн'), ('õe', 'ойн'), ('ães', 'айнс'), ('ões', 'ойнс'),
    ('ai', 'ай'), ('ei', 'ей'), ('oi', 'ой'), ('ui', 'уй'), ('ái', 'ай'), ('éi', 'ей'), ('ói', 'ой'),
]

SINGLE = {
    'a': 'а', 'á': 'а', 'à': 'а', 'â': 'а', 'ã': 'ан',
    'b': 'б', 'c': 'к', 'ç': 'с', 'd': 'д',
    'e': 'е', 'é': 'э', 'ê': 'е',
    'f': 'ф', 'g': 'г', 'h': '', 'i': 'и', 'í': 'и',
    'j': 'ж', 'k': 'к', 'l': 'л', 'm': 'м', 'n': 'н',
    'o': 'о', 'ó': 'о', 'ô': 'о', 'õ': 'он',
    # 'ã' отдельно ниже: в конце слова традиция даёт «а» (Maracanã -> Маракана),
    # внутри слова и в -ão — носовое «ан» (São -> Сан)
    'p': 'п', 'q': 'к', 'r': 'р', 's': 'с', 't': 'т',
    'u': 'у', 'ú': 'у', 'ü': 'у', 'v': 'в', 'w': 'в',
    'x': 'ш', 'y': 'и', 'z': 'з',
}

# Слова, которые в русской традиции пишутся иначе, чем даёт правило.
EXCEPTIONS = {
    'rio': 'Рио', 'de': 'ди', 'do': 'ду', 'da': 'да', 'dos': 'дус', 'das': 'дас',
    'e': 'и', 'santo': 'Санту', 'santa': 'Санта', 'são': 'Сан',
    'rua': 'Руа', 'avenida': 'Авенида', 'praça': 'Праса', 'largo': 'Ларгу',
    'palace': 'Палас', 'hotel': 'Отель', 'sé': 'Сэ',
}


def _stressed_index(w):
    """Грубое определение ударного слога: явный акут/циркумфлекс, иначе правило по окончанию."""
    for i, ch in enumerate(w):
        if ch in 'áéíóúâêô':
            return i
    return None


def word(w):
    """Транскрибирует одно слово (без учёта регистра на входе)."""
    low = unicodedata.normalize('NFC', w.lower())
    if low in EXCEPTIONS:
        return EXCEPTIONS[low]
    s = low
    out = []
    i = 0
    has_accent = _stressed_index(s) is not None
    while i < len(s):
        # диграфы
        for a, b in DIGRAPHS:
            if s.startswith(a, i):
                # gu/qu перед e,i теряют u; перед a,o — нет
                if a in ('qu', 'gu'):
                    nxt = s[i + 2] if i + 2 < len(s) else ''
                    if nxt in 'ei':
                        out.append(b)
                        i += 2
                        break
                    out.append(b[0] + 'у')
                    i += 2
                    break
                out.append(b)
                i += len(a)
                break
        else:
            ch = s[i]
            prev = s[i - 1] if i else ''
            nxt = s[i + 1] if i + 1 < len(s) else ''
            if ch == 'c':
                out.append('с' if nxt in 'eiéí' else 'к')
            elif ch == 'g':
                out.append('ж' if nxt in 'eiéí' else 'г')
            elif ch == 's':
                out.append('з' if prev in VOWELS and nxt in VOWELS else 'с')
            elif ch == 'x':
                out.append('ш')                       # в бразильских именах почти всегда /ʃ/
            elif ch == 'o' and i == len(s) - 1 and not has_accent:
                out.append('у')                       # бразильское конечное безударное -o
            elif ch == 'o' and i == len(s) - 2 and nxt == 's' and not has_accent:
                out.append('у')                       # -os -> -ус
            elif ch == 'e' and i == len(s) - 1 and not has_accent and len(s) > 2:
                out.append('и')                       # конечное безударное -e -> -и
            elif ch == 'm' and (nxt == '' or nxt not in VOWELS):
                out.append('н')                       # носовой призвук
            elif ch == 'ã':
                out.append('а' if i == len(s) - 1 else 'ан')
            else:
                out.append(SINGLE.get(ch, ch))
            i += 1
    r = ''.join(out)
    r = re.sub(r'(?<=[жшч])ы', 'и', r)
    # после мягкого знака гласная смягчается: ньа -> нья, льу -> лью
    for a, b in (('ьа', 'ья'), ('ьу', 'ью'), ('ьэ', 'ье'), ('ьо', 'ьо')):
        r = r.replace(a, b)
    r = re.sub(r'нн+', 'нн', r)
    return r[:1].upper() + r[1:] if w[:1].isupper() else r


def phrase(text):
    parts = re.split(r'(\W+)', unicodedata.normalize('NFC', text))
    return ''.join(word(p) if p and p[0].isalpha() else p for p in parts)


# ---------------------------------------------------------------- ru -> pt
# Бразильская традиция передачи русских имён: Пушкин -> Púchkin, Чехов -> Tchékhov.
RU2PT_DI = [('щ', 'schtch'), ('ш', 'ch'), ('ч', 'tch'), ('ж', 'j'), ('х', 'kh'), ('ц', 'ts'),
            ('я', 'ia'), ('ю', 'iu'), ('ё', 'io'), ('й', 'i'), ('ы', 'i'), ('э', 'e'),
            ('ъ', ''), ('ь', '')]
RU2PT = {'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'е': 'e', 'з': 'z', 'и': 'i',
         'к': 'k', 'л': 'l', 'м': 'm', 'н': 'n', 'о': 'o', 'п': 'p', 'р': 'r', 'с': 's',
         'т': 't', 'у': 'u', 'ф': 'f'}
RU_VOWELS = set('аеёиоуыэюя')


def word_ru2pt(w):
    low = unicodedata.normalize('NFC', w.lower())
    out = []
    i = 0
    while i < len(low):
        for a, b in RU2PT_DI:
            if low.startswith(a, i):
                out.append(b)
                i += len(a)
                break
        else:
            ch = low[i]
            prev = low[i - 1] if i else ''
            nxt = low[i + 1] if i + 1 < len(low) else ''
            if ch == 'с' and prev in RU_VOWELS and nxt in RU_VOWELS:
                out.append('ss')            # иначе в португальском прочтётся как /z/
            elif ch == 'е' and (i == 0 or prev in RU_VOWELS):
                out.append('ie')
            else:
                out.append(RU2PT.get(ch, ch))
            i += 1
    r = ''.join(out)
    return r[:1].upper() + r[1:] if w[:1].isupper() else r


def phrase_ru2pt(text):
    parts = re.split(r'(\W+)', unicodedata.normalize('NFC', text))
    return ''.join(word_ru2pt(p) if p and p[0].isalpha() else p for p in parts)


SAMPLES_RU = [
    ('Артём', 'Artiom'), ('Пушкин', 'Puchkin'), ('Чехов', 'Tchekhov'), ('Жуков', 'Jukov'),
    ('Санкт-Петербург', 'Sankt-Peterburg'), ('Екатерина', 'Iekaterina'), ('Сергей', 'Sergei'),
    ('Юлия', 'Iuliia'), ('Василиса', 'Vassilissa'), ('Щукин', 'Schtchukin'),
    ('Алексей', 'Aleksei'), ('Мария', 'Mariia'),
]

SAMPLES = [
    ('Rua Augusta', 'Руа Аугуста'),
    ('Avenida Paulista', 'Авенида Паулиста'),
    ('Copacabana', 'Копакабана'),
    ('Ipanema', 'Ипанема'),
    ('Consolação', 'Консоласан'),
    ('Fogo de Chão', 'Фогу ди Шан'),
    ('Guarulhos', 'Гуарульюс'),
    ('São Paulo', 'Сан Паулу'),
    ('Salvador', 'Салвадор'),
    ('Recife', 'Ресифи'),
    ('Belo Horizonte', 'Белу Оризонти'),
    ('Niterói', 'Нитерой'),
    ('feijoada', 'фейжоада'),
    ('caipirinha', 'кайпиринья'),
    ('coxinha', 'кошинья'),
    ('açaí', 'асаи'),
    ('Maracanã', 'Маракана'),
    ('Botafogo', 'Ботафогу'),
    ('Leblon', 'Леблон'),
    ('Tijuca', 'Тижука'),
]

if __name__ == '__main__':
    if len(sys.argv) > 1:
        for a in sys.argv[1:]:
            f = phrase_ru2pt if re.search(r'[А-Яа-яЁё]', a) else phrase
            print('%-24s -> %s' % (a, f(a)))
    else:
        print('=== pt -> ru ===')
        for src, expect in SAMPLES:
            got = phrase(src)
            print('%-20s -> %-22s%s' % (src, got, ' ' if got == expect else ' <- ожидалось: ' + expect))
        print('=== ru -> pt ===')
        for src, expect in SAMPLES_RU:
            got = phrase_ru2pt(src)
            print('%-20s -> %-22s%s' % (src, got, ' ' if got == expect else ' <- ожидалось: ' + expect))
