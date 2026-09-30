#!/usr/bin/env python3
"""Правка слов снимка перед переводом: словарь португальских форм data/ocr_words_pt.txt.gz.

Распознаватель строк теряет узкие буквы и ударения («vnagre», «camim», «aicionados», «días»),
а переводчик на таком слове выдумывает: «краситель камыш» вместо «кармин», «смузи» вместо
«добавленных сахаров». Правка — эталон для OcrWords.java, приложение повторяет её буква в букву
(OcrWordsTest сверяет по bench/ocr/runs/golden/words.tsv).

Правится только слово, которого нет в словаре, и только так, как ошибается распознаватель:
  * ударения — «maximo» → «máximo», «ESCRITORIO» → «ESCRITÓRIO» (форма, частая в корпусе);
  * пропущенная буква — «vnagre» → «vinagre», «SANTS» → «SANTOS»;
  * сдвоенная гласная — «Áagua» → «Água», «charuutos» → «charutos»;
  * слипшиеся слова — «NÃOCONTÉMGLÚTEN» → «NÃO CONTÉM GLÚTEN», «EstudantesCarteira».
Замены буквы на другую нет: на наборе вывесок она чинила одно слово и портила два («CERVA» →
«CERVO», английское «Train» → «Traiu»). Слово с заглавной посреди текста — скорее имя: для него
годится только очень частое слово корпуса, а имя из корпуса («Carl», «Caim») не подставляется
в строчное слово и не бывает частью разбивки.

Словарь — все формы португальской части Tatoeba (CC-BY 2.0 FR) с частотой и пометкой «имя»
(чаще с заглавной не в начале предложения), плюс целые слова словаря переводчика с частотой 0:
они «известны» и не правятся, но сами в замену не идут.

  .venv/bin/python tools/ocr_words.py build     # data/ocr_words_pt.txt.gz из data/tatoeba/raw
  .venv/bin/python tools/ocr_words.py eval      # набор bench/ocr: слова до и после, каждая правка
  .venv/bin/python tools/ocr_words.py golden    # эталон для OcrWordsTest
  echo 'Ingredientes: vnagre' | .venv/bin/python tools/ocr_words.py fix
"""
import bz2, collections, gzip, io, os, re, sys, unicodedata

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Сжат: 72 тыс. строк текстом раздували каждую разницу с main до 78 тыс. строк, а сравнивать
# по строкам производный файл незачем. gzip без имени и времени — пересборка даёт те же байты.
OUT = os.path.join(R, 'data', 'ocr_words_pt.txt.gz')
RAW = os.path.join(R, 'data', 'tatoeba', 'raw', 'por_sentences_detailed.tsv.bz2')
PIECES = os.path.join(R, 'models', 'mt', 'pt2ru', 'pt2ru_source_pieces.tsv')
RUN = os.path.join(R, 'bench', 'ocr', 'runs', 'ref')

TOKEN = re.compile(r'[^\W\d_]+')
LATIN = re.compile(r'[a-zà-öø-ÿ]+')
CAMEL = re.compile(r'([^\W\d_]*[a-zà-öø-ÿ])([A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ]+)')
PLAIN = 'abcdefghijklmnopqrstuvwxyz'
VOWELS = set('aeiou')

MIN_FREQ = 2        # замена — слово, встреченное в корпусе хотя бы дважды: единичное чаще опечатка
ACCENT_MIN = 3      # замена по одним ударениям
TITLE_MIN = 50      # слово с заглавной посреди текста — скорее имя; заменяем только на частое
MIN_LEN = 4         # пропущенную букву ищем у слов от четырёх букв
# Чек печатается без знаков над буквами, и там «MACA» — maçã (яблоко), но «maca»
# (носилки) — тоже слово, и ударения не возвращались: переводчик писал «мак». В строке без
# единого знака, прописными, берётся форма со знаками, если она в BARE_RATIO раз чаще: maçã 386 против
# maca 7. Порог — по словарю: до 10 раз пары двусмысленны (faca «нож» 276 против faça 1162, esta
# против está, coco против cocô). Только в строках таблицы: на вывеске «SECRETARIA DE SAUDE» (ведомство)
# так стала бы «секретаршей» — secretária в корпусе в 23 раза чаще.
BARE_RATIO = 10
BARE_MIN = 4
SPLIT_LEN = 7       # разбиваем слова от семи букв
SPLIT_PART = 10     # часть разбивки от трёх букв — не реже 10 раз в корпусе и не имя
SPLIT_GEO = 30      # средняя (геометрическая) частота частей — не ниже 30: «CONTÉM·GLÚTEN» — 42
SPLIT3 = 150.0      # разбивка на три части должна быть в 150 раз вероятнее, чем на две
FUNC2 = {'de', 'da', 'do', 'as', 'os', 'em', 'no', 'na', 'um', 'ao'}
# Английские служебные слова, которых нет в португальском, — в словаре с пометкой E: они известны
# (правка их не трогает — иначе «line» → «lince», «here» → «herde»), но в долю португальских слов не
# идут. В португальских предложениях Tatoeba они попадаются в цитатах («the» — 22 раза), и
# английский абзац таблички набирал долю 0,42 и уходил в перевод. «for» («se for»), «time» («time
# de futebol»), «me», «no», «do», «a», «as» — португальские и здесь не стоят.
ENGLISH = set('the and of is was with that this which from are were be been by on at it its or not '
              'have has had you your we they their his her our will would can an if but all one what '
              'there more when who into out up about my he she him them then than these those some '
              'any how why where here very just only also after before over under through first while '
              'other such should could may must being because between both same own off again most '
              'many much now well way like get got make made new old see year years people state line '
              'built city street'.split())


def plain(s):
    return ''.join(c for c in unicodedata.normalize('NFD', s.lower()) if unicodedata.category(c) != 'Mn')


def is_upper(s):
    return s == s.upper() and s != s.lower()


def build():
    cnt, cap, low, up = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
    with bz2.open(RAW, 'rt', encoding='utf-8') as f:
        for line in f:
            p = line.split('\t')
            if len(p) < 3:
                continue
            s, start, end = p[2], True, 0
            for m in TOKEN.finditer(s):
                w = m.group(0)
                if re.search(r'[.!?:;"«“—]', s[end:m.start()]):
                    start = True
                lw = w.lower(); cnt[lw] += 1
                if len(w) > 1 and is_upper(w):
                    up[lw] += 1
                if not start:
                    (cap if w[:1].isupper() else low)[lw] += 1
                start, end = False, m.end()
    words = {w: (c, cap[w] > low[w] and w not in ENGLISH) for w, c in cnt.items() if LATIN.fullmatch(w)}
    extra = 0
    for line in open(PIECES, encoding='utf-8'):
        t = line.split('\t')[0]
        if t.startswith('▁') and LATIN.fullmatch(t[1:]) and len(t) > 3 and t[1:] not in words:
            words[t[1:]] = (0, False); extra += 1
    for w in ENGLISH:
        words.setdefault(w, (0, False))
    # Опечатка без ударений («nao» — 1 раз против 79 932 «não», «voce» из словаря переводчика) иначе
    # считалась бы известным словом, и распознанное «NAO» оставалось без ударения, а «NAOCONTEM» — слитно.
    # Сокращения, которые в корпусе пишутся прописными («SOS»), — не опечатки: иначе «SOS» → «SÓS».
    top = {}
    for w, (c, _) in words.items():
        top[plain(w)] = max(top.get(plain(w), 0), c)
    typos = [w for w, (c, _) in words.items()
             if w == plain(w) and c <= 2 and top[w] >= 50 * max(1, c) and 2 * up[w] < max(1, c) and w not in ENGLISH]
    for w in typos:
        del words[w]
    rows = sorted(words.items(), key=lambda kv: (plain(kv[0]), -kv[1][0], kv[0]))
    text = ''.join(f'{w} {c}{" " + fl if fl else ""}\n'
                   for w, (c, name) in rows for fl in [('N' if name else '') + ('E' if w in ENGLISH else '')])
    with open(OUT, 'wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as f:
        f.write(text.encode('utf-8'))
    print(f'{OUT}: {len(rows)} форм ({extra} только из словаря переводчика, {len(typos)} опечаток без ударений убрано), '
          f'имён {sum(1 for _, (c, n) in rows if n)}, {os.path.getsize(OUT) // 1024} КБ')


class Words:
    def __init__(self, path=OUT):
        self.freq, self.names, self.english, self.idx = {}, set(), set(), {}
        src = gzip.open(path, 'rt', encoding='utf-8') if path.endswith('.gz') else open(path, encoding='utf-8')
        for line in src:
            p = line.split()
            if len(p) < 2:
                continue
            w = p[0]; self.freq[w] = int(p[1]); fl = p[2] if len(p) > 2 else ''
            if 'N' in fl:
                self.names.add(w)
            if 'E' in fl:
                self.english.add(w)
            self.idx.setdefault(plain(w), []).append(w)   # файл уже по убыванию частоты внутри формы

    def known(self, w):
        return w in self.freq or plain(w) in self.idx

    def count(self, w):
        """Частота слова или, если его нет, самой частой формы с теми же буквами без ударений."""
        if w in self.freq:
            return self.freq[w]
        f = self.idx.get(plain(w))
        return self.freq[f[0]] if f else 0

    def target(self, f, capital, fmin):
        return self.freq[f] >= fmin and (capital or f not in self.names) and f not in self.english

    def best(self, w, title, capital, bare=False):
        """Замена для слова w (строчными) или None. bare — строка чека без знаков над буквами."""
        if w in self.freq:
            if not bare or len(w) < BARE_MIN:
                return None
            f = self.idx[plain(w)][0]                   # самая частая форма с теми же буквами
            ok = f != w and self.freq[f] >= BARE_RATIO * max(1, self.freq[w]) and self.target(f, capital, ACCENT_MIN)
            return f if ok else None
        p = plain(w)
        fmin = TITLE_MIN if title else MIN_FREQ
        if len(p) >= 3 and p in self.idx:
            f = self.idx[p][0]
            return f if self.target(f, capital, max(ACCENT_MIN, fmin)) else None
        if len(p) < MIN_LEN:
            return None
        best = None
        for e in self.edits(p):
            for f in self.idx.get(e, ()):
                if self.target(f, capital, fmin):
                    c = self.freq[f]
                    if best is None or c > best[0] or (c == best[0] and f < best[1]):
                        best = (c, f)
        return best[1] if best else None

    @staticmethod
    def edits(p):
        """Чем слово могло быть до распознавания: плюс буква где угодно, минус сдвоенная гласная."""
        out = []
        for i in range(len(p) + 1):
            a, b = p[:i], p[i:]
            if b and b[0] in VOWELS and ((a and a[-1] == b[0]) or (len(b) > 1 and b[1] == b[0])):
                out.append(a + b[1:])
            for ch in PLAIN:
                out.append(a + ch + b)
        return out

    def accent(self, x):
        """Часть разбивки с ударениями по словарю: «NAOCONTEM» → «NÃO CONTÉM»."""
        lx = x.lower()
        if lx in self.freq:
            return x
        f = self.idx.get(plain(lx))
        return case_as(x, f[0]) if f else x

    def part(self, s):
        if len(s) < 2:
            return 0
        if len(s) == 2:
            return self.count(s) if s in FUNC2 else 0
        if s in self.names or s in self.english:
            return 0
        c = self.count(s)
        return c if c >= SPLIT_PART else 0

    def split(self, t):
        m = CAMEL.fullmatch(t)
        if m and self.count(m.group(1).lower()) >= 3 and self.count(m.group(2).lower()) >= 3:
            return [m.group(1), m.group(2)]
        p = t.lower(); n = len(p)
        if (t[:1].isupper() and not is_upper(t)) or n < SPLIT_LEN or self.known(p):
            return None
        best = None
        for i in range(2, n - 1):
            fa = self.part(p[:i])
            if not fa:
                continue
            fb = self.part(p[i:])
            if fb and (best is None or fa * fb > best[0]):
                best = (fa * fb, [t[:i], t[i:]])
            for j in range(i + 2, n - 1):
                fb, fc = self.part(p[i:j]), self.part(p[j:])
                if fb and fc and (best is None or fa * fb * fc / SPLIT3 > best[0]):
                    best = (fa * fb * fc / SPLIT3, [t[:i], t[i:j], t[j:]])
        if best is None:
            return None
        prod = 1.0
        for x in best[1]:
            prod *= self.count(x.lower())
        return best[1] if prod >= SPLIT_GEO ** len(best[1]) else None

    def fix(self, text, changes=None, bare=False):
        def rep(m):
            t = m.group(0); lw = t.lower()
            if len(lw) < 3:
                return t
            title = t[:1].isupper() and not is_upper(t)
            b = self.best(lw, title, t[:1].isupper(), bare)
            if b is not None and b != lw:
                r = case_as(t, b)
            elif not self.known(lw):
                s = self.split(t)
                if not s:
                    return t
                r = ' '.join(self.accent(x) for x in s)
            else:
                return t
            if changes is not None:
                changes.append((t, r))
            return r
        return TOKEN.sub(rep, text)

    @staticmethod
    def bare(text):
        """Строка прописными без единого знака над буквами — как печатает кассовый аппарат. Единицы
        («kg», «un», «ml») бывают строчными — в счёт идут слова от трёх букв."""
        ws = [w for w in TOKEN.findall(text) if len(w) >= 3]
        return bool(ws) and all(is_upper(w) for w in ws) and plain(text) == text.lower()

    def share(self, text):
        """Доля известных слов от трёх букв; -2 — слов меньше двух, судить не по чему."""
        ws = [w for w in TOKEN.findall(text) if len(w) >= 3]
        if len(ws) < 2:
            return -2.0
        return sum(1 for w in ws if self.known(w.lower()) and w.lower() not in self.english) / len(ws)


def case_as(src, dst):
    if is_upper(src) and len(src) > 1:
        return dst.upper()
    if src[:1].isupper():
        return dst[:1].upper() + dst[1:]
    return dst


def evaluate():
    sys.path.insert(0, os.path.join(R, 'tools'))
    import ocr_eval
    W = Words(); G = ocr_eval.gt()
    rows0, rows1, tags = [], [], collections.Counter()
    print('Правки на выводе распознавания (bench/ocr/runs/ref):')
    for i in sorted(G):
        txt = open(os.path.join(RUN, i + '.txt'), encoding='utf-8').read()
        gw = collections.Counter(ocr_eval.words(' '.join(G[i])))
        ch = []
        fixed = '\n'.join(W.fix(l, ch) for l in txt.split('\n'))
        for a, b in ch:
            ina = all(gw[x] for x in ocr_eval.words(a)); inb = all(gw[x] for x in ocr_eval.words(b))
            tag = 'лучше' if inb and not ina else 'хуже' if ina and not inb else 'так же'
            tags[tag] += 1
            print(f'  {tag:6} {i}: {a} → {b}')
        rows0.append(ocr_eval.score(G[i], txt)); rows1.append(ocr_eval.score(G[i], fixed))
    for name, rows in (('до', rows0), ('после', rows1)):
        t = ocr_eval.total(rows)
        print(f'{name:5}: слова {t["words"]:.1%} · без диакр. {t["words_na"]:.1%} · точность {t["prec"]:.1%} · CER строк {t["cer"]:.1%}')
    print('правок:', dict(tags))
    print('Правки на самой расшифровке (всё, что здесь меняется, — лишнее или исправление вывески):')
    for i in sorted(G):
        ch = []
        for l in G[i]:
            W.fix(l, ch)
        for a, b in ch:
            print(f'  {i}: {a} → {b}')
    print('Абзацы с долей известных слов ниже 0,5 (после правки):')
    for i in sorted(G):
        p = os.path.join(RUN, i + '.para')
        for para in open(p, encoding='utf-8').read().split('\n') if os.path.exists(p) else []:
            s0 = W.share(para); s1 = W.share(W.fix(para))
            if 0 <= s1 < 0.5:
                print(f'  {i} {s0:.2f}→{s1:.2f}: {para[:90]}')


def damaged(G, seed=7):
    """Строки расшифровки, испорченные так, как портит распознаватель: пропала буква, снято
    ударение, сдвоена гласная, слиплись два слова. Нужны эталону, чтобы правка срабатывала
    часто: на выводе распознавания набора она меняет лишь каждую двадцатую строку."""
    import random
    rnd, out = random.Random(seed), []
    for i in sorted(G):
        for l in G[i]:
            for _ in range(2):
                ws = l.split(' ')
                for k in range(len(ws)):
                    w, r = ws[k], rnd.random()
                    if len(w) > 3 and r < 0.15:
                        j = rnd.randrange(1, len(w) - 1); ws[k] = w[:j] + w[j + 1:]
                    elif r < 0.25:
                        ws[k] = plain(w) if w.islower() else w.upper() if r < 0.2 else w
                    elif r < 0.3:
                        j = next((j for j, c in enumerate(w) if c.lower() in 'aeiou'), -1)
                        if j >= 0: ws[k] = w[:j] + w[j] + w[j:]
                    elif r < 0.35 and k + 1 < len(ws):
                        ws[k] = w + ws[k + 1]; ws[k + 1] = ''
                out.append(' '.join(x for x in ws if x))
    return out


def golden():
    W = Words(); out = os.path.join(R, 'bench', 'ocr', 'runs', 'golden'); os.makedirs(out, exist_ok=True)
    sys.path.insert(0, os.path.join(R, 'tools'))
    import ocr_eval
    lines = []
    for i in sorted(ocr_eval.gt()):
        for ext in ('.txt', '.para'):
            p = os.path.join(RUN, i + ext)
            if os.path.exists(p):
                lines += [l for l in open(p, encoding='utf-8').read().split('\n') if l.strip()]
        lines += ocr_eval.gt()[i]
    lines += damaged(ocr_eval.gt())
    with open(os.path.join(out, 'words.tsv'), 'w', encoding='utf-8') as f:
        for l in lines:
            f.write(f'{l}\t{W.fix(l)}\t{W.share(l):.6f}\n')
    print(f'{out}/words.tsv: {len(lines)} строк, изменено {sum(1 for l in lines if W.fix(l) != l)}')


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'fix'
    if cmd == 'build':
        build()
    elif cmd == 'eval':
        evaluate()
    elif cmd == 'golden':
        golden()
    else:
        W = Words(); ch = []
        for line in sys.stdin:
            print(W.fix(line.rstrip('\n'), ch))
        for a, b in ch:
            print(f'  {a} → {b}', file=sys.stderr)
