#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Подбор сопоставителя для «списка своих слов».

Задача: пользователь заранее задал имя («Copacabana Palace»), распознавание выдало
«cupa acabando o palácio». Надо найти в распознанном тексте искажённое имя и заменить
на каноническое. Проверяем два сопоставителя на НАСТОЯЩИХ ошибках распознавания:

  орфографический — расстояние редактирования по буквам
  фонетический    — расстояние по согласному скелету (гласные выброшены,
                    согласные сведены в классы: п/б, т/д, к/г, с/з, ш/ж…)

Меряем полноту (сколько искажённых имён найдено) и точность (сколько ложных
срабатываний на фразах, где никаких слов из списка нет).
"""
import json
import re
import sys
import unicodedata

# --- согласные классы: распознавание часто путает звонкость и место артикуляции
CLASSES = {}
for cls, letters in {
    'P': 'pbпб', 'T': 'tdтд', 'K': 'kgcqкгх', 'F': 'fvфв', 'S': 'szçсзц',
    'X': 'xjшжщч', 'M': 'mм', 'N': 'nnñнň', 'L': 'lл', 'R': 'rр',
}.items():
    for ch in letters:
        CLASSES[ch] = cls


def strip_marks(s):
    s = unicodedata.normalize('NFD', s.lower().replace('ё', 'е'))
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')


def skeleton(s):
    """Согласный скелет: диграфы сначала, потом посимвольно, повторы схлопываем."""
    s = strip_marks(s)
    for a, b in (('lh', 'L'), ('nh', 'N'), ('ch', 'X'), ('ss', 'S'), ('rr', 'R'),
                 ('qu', 'K'), ('sc', 'S'), ('ph', 'F'), ('th', 'T')):
        s = s.replace(a, b)
    out = []
    for ch in s:
        c = CLASSES.get(ch)
        if c and (not out or out[-1] != c):
            out.append(c)
    return ''.join(out)


def lev(a, b):
    if a == b:
        return 0
    if not a or not b:
        return max(len(a), len(b))
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def sim(a, b):
    m = max(len(a), len(b))
    return 1.0 - lev(a, b) / m if m else 0.0


MIN_SKEL = 3          # скелет короче трёх согласных совпадает со всем подряд


def find2(text, entry, thr_phon, thr_orth):
    """Комбинированное правило: фонетика решает, орфография ставит нижнюю границу."""
    tp, to = skeleton(entry), strip_marks(entry).replace(' ', '')
    if len(tp) < MIN_SKEL:
        return None
    words = text.split()
    ew = entry.split()
    best = None
    for k in range(max(1, len(ew) - 1), len(ew) + 3):
        for i in range(0, max(0, len(words) - k + 1)):
            win = ' '.join(words[i:i + k])
            sp = sim(skeleton(win), tp)
            so = sim(strip_marks(win).replace(' ', ''), to)
            if sp >= thr_phon and so >= thr_orth and (best is None or sp > best[2]):
                best = (i, i + k, sp, so)
    return best


def evaluate2(thr_phon, thr_orth):
    tp = sum(1 for lang, entry, text, want in CASES if want and find2(text, entry, thr_phon, thr_orth))
    fp = 0
    for lang, entries, negs in (('pt', [c[1] for c in CASES if c[0] == 'pt'], NEGATIVES_PT),
                                ('ru', [c[1] for c in CASES if c[0] == 'ru'], NEGATIVES_RU)):
        for t in negs:
            for e in entries:
                if find2(t, e, thr_phon, thr_orth):
                    fp += 1
    return tp, fp


def find(text, entry, mode, thr):
    """Ищет искажённое entry в text. Возвращает (начало, конец, оценка) по словам или None."""
    words = text.split()
    ew = entry.split()
    target = skeleton(entry) if mode == 'phon' else strip_marks(entry).replace(' ', '')
    if not target:
        return None
    best = None
    # окно от k-1 до k+2 слов: распознавание склеивает и дробит слова
    for k in range(max(1, len(ew) - 1), len(ew) + 3):
        for i in range(0, max(0, len(words) - k + 1)):
            win = ' '.join(words[i:i + k])
            cand = skeleton(win) if mode == 'phon' else strip_marks(win).replace(' ', '')
            s = sim(cand, target)
            if s >= thr and (best is None or s > best[2]):
                best = (i, i + k, s)
    return best


# --- данные: настоящие ошибки распознавания, снятые с телефона (parakeet)
CASES = [
    # (язык, слово из списка, распознанный текст, должно ли найтись)
    ('pt', 'Rua Augusta', 'O hotel fica na rua Augusta, perto do metrô.', True),
    ('pt', 'Copacabana Palace', 'Vamos almoçar no cupa acabando o palácio.', True),
    ('pt', 'Artiom', 'Meu nome é Ation, sou da Rússia.', True),
    ('pt', 'Fogo de Chão', 'Você conhece o restaurante Fogo de Chão?', True),
    ('pt', 'Avenida Paulista', 'Eu moro na Avenida Paulista.', True),
    ('pt', 'caipirinha', 'Era o provar feijoada e capirinha.', True),
    ('pt', 'Ipanema', 'O ônibus para em Ipanema.', True),
    ('pt', 'Ibis', 'Sou hospedado no Hotel Ibis.', True),
    ('pt', 'Consolação', 'A farmácia fica na rua Consolação.', True),
    ('ru', 'Ибис', 'Я живу в Ателебесной улице Августа.', True),
    ('ru', 'Аугуста', 'Я живу в Ателебесной улице Августа.', True),
    ('ru', 'Копакабана', 'Ехать до Капокапанная.', True),
    ('ru', 'Артём', 'Меня зовут Арт<unk>м, я из России.', True),
    ('ru', 'Авенида Паулиста', 'где находится Авенедополиста.', True),
    ('ru', 'фейжоада', 'Я хочу попробовать фишу одной коперенью.', True),
    ('ru', 'кайпиринья', 'Я хочу попробовать фишу одной коперенью.', True),
    ('ru', 'Ипанема', 'останавливается V Panem.', True),
    ('ru', 'Консоласан', 'Мне нужно на улицу Кацовасом.', True),
    ('ru', 'Фогу ди Шан', 'Мы встречаемся у ресторана Фого Дершау.', True),
    ('ru', 'Гуарульос', 'Отвезите меня в аэропорт, Гарулев.', True),
]

# фразы БЕЗ слов из списка: тут любое срабатывание — ложное
NEGATIVES_PT = [
    'Quanto custa isso?', 'Onde fica o banheiro?', 'Eu não falo português.',
    'Você pode me ajudar?', 'Não entendo.', 'Todos dizem que são inocentes.',
    'Eu estava falando com vocês.', 'O tom comeu três cachorros quentes.',
    'A ideia em si não é ruim.', 'Por favor, você poderia falar um pouco mais devagar?',
    'Ela está contente com seu sucesso.', 'Aquele rádio não é maior do que uma caixa de fósforos.',
]
NEGATIVES_RU = [
    'Сколько это стоит?', 'Где находится вокзал?', 'Помогите мне, пожалуйста.',
    'Я хочу есть.', 'Я не понимаю.', 'он покинул комнату не сказав ни слова',
    'вот почему девушки любят огурцы', 'по существу я согласен с вашим мнением',
    'они не смогут тебя остановить', 'здесь очень опасно ходить ночью',
    'он по ошибке взял не ту шляпу', 'это совсем не удивительно не так ли',
]


def evaluate(mode, thr):
    tp = sum(1 for lang, entry, text, want in CASES if want and find(text, entry, mode, thr))
    fp = 0
    for lang, entries, negs in (('pt', [c[1] for c in CASES if c[0] == 'pt'], NEGATIVES_PT),
                                ('ru', [c[1] for c in CASES if c[0] == 'ru'], NEGATIVES_RU)):
        for t in negs:
            for e in entries:
                if find(t, e, mode, thr):
                    fp += 1
    return tp, len(CASES), fp


if __name__ == '__main__':
    print('%-6s %5s %8s %8s  %s' % ('режим', 'порог', 'найдено', 'ложных', 'полнота'))
    for mode in ('orth', 'phon'):
        for thr in (0.95, 0.9, 0.85, 0.8, 0.75, 0.7, 0.65, 0.6, 0.55, 0.5):
            tp, tot, fp = evaluate(mode, thr)
            print('%-6s %5.2f %8d %8d  %.0f%%' % (mode, thr, tp, fp, 100 * tp / tot))
    print('\nкомбинированное правило (фонетика И орфографический пол):')
    print('%9s %9s %8s %8s %8s' % ('порог фон', 'порог орф', 'найдено', 'ложных', 'полнота'))
    for tp_ in (0.9, 0.85, 0.8, 0.75, 0.7):
        for to_ in (0.0, 0.25, 0.35, 0.45, 0.55):
            a, b = evaluate2(tp_, to_)
            print('%9.2f %9.2f %8d %8d %7.0f%%' % (tp_, to_, a, b, 100 * a / len(CASES)))
    if '--detail' in sys.argv:
        thr = 0.75
        print('\nчто ловит фонетический при пороге %.2f:' % thr)
        for lang, entry, text, want in CASES:
            r = find(text, entry, 'phon', thr)
            o = find(text, entry, 'orth', thr)
            print('  %-18s %-44s фон:%-6s орф:%-6s' % (
                entry, text[:44],
                ('%.2f' % r[2]) if r else '—', ('%.2f' % o[2]) if o else '—'))
