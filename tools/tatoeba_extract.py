#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Разбор дампов Tatoeba в пары pt-BR <-> ru для словаря быстрых фраз.

Вход  (data/tatoeba/raw): por-rus_links.tsv, por_sentences_detailed.tsv,
      rus_sentences_detailed.tsv, tags.csv, [por-eng_links.tsv, rus-eng_links.tsv]
Выход (data/tatoeba):     pairs_direct.tsv, pivot_candidates.tsv, stats.json

Этапы:
  1. связи por->rus (прямые) и, по флагу, кандидаты через английский пивот
  2. классификация диалекта каждого португальского предложения: теги -> маркеры -> автор
  3. чистка и фильтры (длина, цифры, имена собственные, мусор)
  4. дедупликация по нормализованному ключу (как в Phrasebook.norm на телефоне)

Лицензия данных: Tatoeba CC-BY 2.0 FR (часть предложений CC0). Указание авторства
обязательно при распространении; для личного использования ограничений нет.
"""
import argparse, bz2, collections, json, os, re, sys, unicodedata

RAW = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'data', 'tatoeba', 'raw')
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'data', 'tatoeba')

def opentext(path):
    """Читает файл или его .bz2 — распаковывать выгрузки отдельно не нужно."""
    if os.path.exists(path):
        return open(path, encoding='utf-8')
    if os.path.exists(path + '.bz2'):
        return bz2.open(path + '.bz2', 'rt', encoding='utf-8')
    raise FileNotFoundError(path + ' (.bz2)')


def haspath(path):
    return os.path.exists(path) or os.path.exists(path + '.bz2')


# ---------------------------------------------------------------- нормализация
CONTR = {'vc': 'você', 'tá': 'está', 'ta': 'está', 'pra': 'para', 'pro': 'para o', 'né': 'não é',
         'tô': 'estou', 'to': 'estou', 'cadê': 'onde está', 'q': 'que', 'tb': 'também'}


def norm(s):
    """Повторяет Phrasebook.norm() из приложения: NFC, lower, ё->е, только буквы/цифры/'/-."""
    s = unicodedata.normalize('NFC', s).lower().replace('ё', 'е')
    out = []
    for ch in s:
        if ch in "'-" or ch.isspace():
            out.append(ch)
        elif unicodedata.category(ch)[0] in ('L', 'N'):
            out.append(ch)
        else:
            out.append(' ')
    s = re.sub(r'\s+', ' ', ''.join(out)).strip()
    return ' '.join(CONTR.get(w, w) for w in s.split(' ') if w)


# ------------------------------------------------------- маркеры диалекта pt
# вес 3 — однозначные, 2 — грамматика, 1 — слабые (встречаются в обоих вариантах)
PT_MARKERS = [
    (3, r'\b(autocarro|autocarros|comboio|comboios|telemóve(l|is)|pequeno[- ]almoço|casa[- ]de[- ]banho|'
        r'frigorífico|boleia|sandes|rapariga|raparigas|miúd[oa]s?|ecrã|chávena|talho|autoclismo|camião|'
        r'portagem|montra|rés[- ]do[- ]chão|esferográfica|canalizador|hospedeira|propina|telemóvel|'
        r'pastilha elástica|casa de banho|bicha|gajo|gaja|puto|porreiro|fixe|bué|chatice)\b'),
    (3, r'\b\w*(ónic|ómic|énic|émic|ónim|ómetr|ónia|énio)\w*\b'),
    (3, r'\b(género|géneros|gémeo|gémeos|ténis|bebé|bebés|António|crónic\w*|tónic\w*|fenómen\w*|cómod\w*|'
        r'económic\w*|académic\w*|polémic\w*|irónic\w*|anónim\w*|sinónim\w*|quilómetr\w*)\b'),
    (3, r'\b(facto|factos|contacto|contactos|contactar|acção|acções|acto|actos|actor|actriz|actual\w*|'
        r'adopt\w+|afect\w+|arquitect\w*|baptis\w+|colecç\w+|correcç\w+|direcç\w+|electr\w+|eléctric\w*|'
        r'exact\w*|excepç\w+|óptic\w*|óptim\w*|objectiv\w*|protecç\w+|secç\w+|selecç\w+|Egipto|húmid\w*)\b'),
    (2, r'\b(estou|estás|está|estamos|estão|estava|estavas|estávamos|estavam|esteve|estive|estiveram|'
        r'andava|anda)\s+a\s+(?!par\b|mar\b|bar\b|lar\b|ar\b)\w+(ar|er|ir)\b'),
    (2, r'\b(vós|vos|vosso|vossa|vossos|vossas|convosco|mais pequen[oa])\b'),
    (1, r'\b(és|estás|tens|fazes|queres|podes|sabes|vais|vens|dizes|gostas|achas|tiveste|fizeste|foste|'
        r'viste|comeste|disseste)\b'),
]
BR_MARKERS = [
    (3, r'\b(ônibus|trem|trens|celular|celulares|café da manhã|banheiro|banheiros|geladeira|sorvete|'
        r'garçom|garçonete|açougue|aeromoça|xícara|xícaras|caminhão|carona|pedágio|vitrine|encanador|'
        r'faxineira|sobrenome|bagunça|grana|bacana|cadê|tchau|mamãe|papai|bunda|guri|moleque|'
        r'carteira de motorista|ponto de ônibus|esporte|esportes|registro|suco)\b'),
    (3, r'\b\w*(ônic|ômic|ênic|êmic|ônim|ômetr|ônia|ênio)\w*\b'),
    (3, r'\b(gênero|gêneros|gêmeo|gêmeos|tênis|bebê|bebês|Antônio|crônic\w*|tônic\w*|fenômen\w*|cômod\w*|'
        r'econômic\w*|acadêmic\w*|polêmic\w*|irônic\w*|anônim\w*|sinônim\w*|quilômetr\w*)\b'),
    (3, r'\b(fato|fatos|contato|contatos|contatar|ação|ações|ato|atos|ator|atriz|atual\w*|adot\w+|afet\w+|'
        r'arquitet\w*|batis\w+|coleç\w+|correç\w+|direç\w+|eletr\w+|elétric\w*|exat\w*|exceç\w+|ótic\w*|'
        r'ótim\w*|objetiv\w*|proteç\w+|seç\w+|seleç\w+|Egito|úmid\w*|idéia|platéia|assembléia)\b'),
    (2, r'\b(estou|está|estamos|estão|estava|estavam|estive|esteve|estiveram|fiquei|ficou|continua|vai|vou)\s+\w{2,}ndo\b'),
    (2, r'^(Me|Te|Lhe)\s'),
    (2, r'\b(a gente|você|vocês)\b'),
]
PT_RE = [(w, re.compile(p, re.I)) for w, p in PT_MARKERS]
BR_RE = [(w, re.compile(p, re.I)) for w, p in BR_MARKERS]
BR_TAGS = {'Portuguese from Brazil', 'regionalism:Brazil', 'PtBrasil', 'regionalismo: Brasil',
           'Brazil', 'Brazilian Portuguese'}
PT_TAGS = {'Portuguese from Portugal', 'regionalism:Portugal', 'Portugal', 'not used in Brazil'}


def dialect_score(text):
    br = sum(w for w, r in BR_RE if r.search(text))
    pt = sum(w for w, r in PT_RE if r.search(text))
    return br, pt


# --------------------------------------------------------------------- чтение
def read_links(path, limit_per_key=0):
    out = collections.defaultdict(list)
    with opentext(path) as f:
        for line in f:
            p = line.split()
            if len(p) == 2:
                if limit_per_key and len(out[p[0]]) >= limit_per_key:
                    continue
                out[p[0]].append(p[1])
    return out


def read_sentences(path, keep):
    """id -> (text, user); keep=None -> всё."""
    out = {}
    with opentext(path) as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 4:
                continue
            if keep is not None and p[0] not in keep:
                continue
            out[p[0]] = (p[2], p[3])
    return out


def read_tags(path, ids):
    br, pt = set(), set()
    if not haspath(path):        # tags.csv лежит в tags.tar.bz2, простым bz2 не открывается
        sys.stderr.write('  ВНИМАНИЕ: %s не найден, региональные теги не учитываются\n' % path)
        return br, pt
    with opentext(path) as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 2 or p[0] not in ids:
                continue
            if p[1] in BR_TAGS:
                br.add(p[0])
            elif p[1] in PT_TAGS:
                pt.add(p[0])
    return br, pt


# -------------------------------------------------------------------- фильтры
WORD_RE = re.compile(r"[^\W\d_]+", re.UNICODE)
BAD_CHARS = re.compile(r'[<>\[\]{}|\\/@#*_=~^]')
DIGIT = re.compile(r'\d')


class NameCounter:
    """Токены, которые почти всегда пишутся с большой буквы не в начале — имена/топонимы."""

    def __init__(self):
        self.total, self.capped = collections.Counter(), collections.Counter()

    def add(self, text):
        for w in WORD_RE.findall(text)[1:]:
            lw = w.lower()
            self.total[lw] += 1
            if w[0].isupper():
                self.capped[lw] += 1

    def names(self, min_count=15, cap_ratio=0.85):
        return {w for w, n in self.total.items() if n >= min_count and self.capped[w] / n >= cap_ratio}


def clean(text):
    t = unicodedata.normalize('NFC', text).strip()
    t = re.sub(r'\s+', ' ', t)
    return t


def acceptable(text, names, max_words, max_chars, allow_digits=False):
    if len(text) > max_chars or len(text) < 2:
        return False
    if BAD_CHARS.search(text):
        return False
    if not allow_digits and DIGIT.search(text):
        return False
    if text.count('"') % 2 or text.count('«') != text.count('»'):
        return False
    words = WORD_RE.findall(text)
    if not words or len(words) > max_words:
        return False
    if any(w.lower() in names for w in words):
        return False
    if sum(1 for w in words if w.isupper() and len(w) > 2) > 1:
        return False
    return True


PERSONAL_PT = re.compile(r'\b(eu|me|mim|meu|minha|meus|minhas|você|vocês|te|ti|teu|tua|seu|sua|a gente|'
                         r'nós|nos|nosso|nossa|comigo|contigo|conosco|senhor|senhora|tu)\b', re.I)
PERSONAL_RU = re.compile(r'\b(я|мне|меня|мной|мой|моя|мои|моё|ты|тебя|тебе|тобой|твой|твоя|вы|вас|вам|вами|'
                         r'ваш|ваша|мы|нас|нам|нами|наш|наша)\b', re.I)
IMPER_RU = re.compile(r'\b\w+(йте|ите|ьте)\b', re.I)


def conversational(pt, ru):
    return bool(PERSONAL_PT.search(pt) or PERSONAL_RU.search(ru) or '?' in pt or '!' in pt
                or IMPER_RU.search(ru) or len(WORD_RE.findall(pt)) <= 3)


# ---------------------------------------------------------------------- главное
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--max-words', type=int, default=9)
    ap.add_argument('--max-chars', type=int, default=70)
    ap.add_argument('--pivot', action='store_true', help='добавить кандидатов через английский')
    ap.add_argument('--pivot-cap', type=int, default=2, help='сколько переводов брать с каждой стороны пивота')
    ap.add_argument('--raw', default=RAW)
    ap.add_argument('--out', default=OUT)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    st = {}

    sys.stderr.write('1/6 связи...\n')
    direct = read_links(os.path.join(a.raw, 'por-rus_links.tsv'))
    need_por, need_rus = set(direct), {r for v in direct.values() for r in v}
    pivot_pairs = []
    if a.pivot:
        pe = read_links(os.path.join(a.raw, 'por-eng_links.tsv'))
        re_ = read_links(os.path.join(a.raw, 'rus-eng_links.tsv'))
        eng2rus = collections.defaultdict(list)
        for rid, engs in re_.items():
            for e in engs:
                eng2rus[e].append(rid)
        for pid, engs in pe.items():
            seen = set()
            for e in engs:
                for rid in eng2rus.get(e, [])[:a.pivot_cap]:
                    if rid in direct.get(pid, ()) or (pid, rid) in seen:
                        continue
                    seen.add((pid, rid))
                    pivot_pairs.append((pid, rid, e))
                if len(seen) >= a.pivot_cap * a.pivot_cap:
                    break
        need_por |= {p for p, _, _ in pivot_pairs}
        need_rus |= {r for _, r, _ in pivot_pairs}
    st['links_direct'] = sum(len(v) for v in direct.values())
    st['pivot_candidates'] = len(pivot_pairs)

    sys.stderr.write('2/6 предложения...\n')
    por = read_sentences(os.path.join(a.raw, 'por_sentences_detailed.tsv'), need_por)
    rus = read_sentences(os.path.join(a.raw, 'rus_sentences_detailed.tsv'), need_rus)
    st['por_loaded'], st['rus_loaded'] = len(por), len(rus)

    sys.stderr.write('3/6 диалект (проход по всем por)...\n')
    br_tag, pt_tag = set(), set()
    all_por_ids = set()
    with opentext(os.path.join(a.raw, 'por_sentences_detailed.tsv')) as f:
        for line in f:
            p = line.split('\t', 1)
            if p:
                all_por_ids.add(p[0])
    br_tag, pt_tag = read_tags(os.path.join(a.raw, 'tags.csv'), all_por_ids)
    del all_por_ids
    user_br, user_pt, user_n = collections.Counter(), collections.Counter(), collections.Counter()
    nc_pt = NameCounter()
    with opentext(os.path.join(a.raw, 'por_sentences_detailed.tsv')) as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 4:
                continue
            sid, text, user = p[0], p[2], p[3]
            nc_pt.add(text)
            b, pscore = dialect_score(text)
            if sid in br_tag:
                b += 6
            if sid in pt_tag:
                pscore += 6
            if b or pscore:
                user_br[user] += b
                user_pt[user] += pscore
            user_n[user] += 1
    user_label = {}
    for u in user_n:
        b, pscore = user_br[u], user_pt[u]
        if b + pscore < 5:
            user_label[u] = '?'
        elif b >= 3 * pscore:
            user_label[u] = 'BR'
        elif pscore >= 3 * b:
            user_label[u] = 'PT'
        else:
            user_label[u] = '?'
    st['users'] = {k: sum(1 for v in user_label.values() if v == k) for k in ('BR', 'PT', '?')}
    st['sentences_by_user_label'] = {
        k: sum(n for u, n in user_n.items() if user_label.get(u, '?') == k) for k in ('BR', 'PT', '?')}
    st['top_users'] = [(u, user_label[u], user_n[u]) for u, _ in user_n.most_common(12)]

    def label(sid):
        if sid in br_tag:
            return 'BR'
        if sid in pt_tag:
            return 'PT'
        text, user = por.get(sid, ('', ''))
        b, pscore = dialect_score(text)
        if b > pscore:
            return 'BR'
        if pscore > b:
            return 'PT'
        return user_label.get(user, '?')

    sys.stderr.write('4/6 имена собственные (проход по всем rus)...\n')
    nc_ru = NameCounter()
    with opentext(os.path.join(a.raw, 'rus_sentences_detailed.tsv')) as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 3:
                nc_ru.add(p[2])
    names = nc_pt.names() | nc_ru.names()
    st['proper_nouns'] = len(names)
    del nc_pt, nc_ru

    sys.stderr.write('5/6 фильтры и дедупликация...\n')
    drop = collections.Counter()

    def emit(rows, pid, rid, src_kind, eng=None):
        pt_text, _ = por.get(pid, (None, None))
        ru_text, _ = rus.get(rid, (None, None))
        if not pt_text or not ru_text:
            drop['no_text'] += 1
            return
        lab = label(pid)
        if lab == 'PT':
            drop['pt_pt_kept_pt2ru_only'] += 1
        pt_text, ru_text = clean(pt_text), clean(ru_text)
        if not acceptable(pt_text, names, a.max_words, a.max_chars):
            drop['pt_filter'] += 1
            return
        if not acceptable(ru_text, names, a.max_words, a.max_chars + 10):
            drop['ru_filter'] += 1
            return
        if not re.search(r'[А-Яа-яЁё]', ru_text):
            drop['ru_not_cyrillic'] += 1
            return
        rows.append({'pid': pid, 'rid': rid, 'pt': pt_text, 'ru': ru_text, 'lab': lab,
                     'conv': conversational(pt_text, ru_text), 'kind': src_kind, 'eng': eng or ''})

    rows = []
    for pid, rids in direct.items():
        for rid in rids:
            emit(rows, pid, rid, 'direct')
    st['direct_kept'] = len(rows)
    prows = []
    for pid, rid, eng in pivot_pairs:
        emit(prows, pid, rid, 'pivot', eng)
    st['pivot_kept'] = len(prows)
    st['dropped'] = dict(drop)

    sys.stderr.write('6/6 запись...\n')
    def write(path, data):
        with open(path, 'w', encoding='utf-8') as f:
            f.write('pt\tru\tlabel\tconv\tkind\tpid\trid\n')
            for r in data:
                f.write('%s\t%s\t%s\t%d\t%s\t%s\t%s\n' %
                        (r['pt'], r['ru'], r['lab'], r['conv'], r['kind'], r['pid'], r['rid']))
    write(os.path.join(a.out, 'pairs_direct.tsv'), rows)
    if prows:
        write(os.path.join(a.out, 'pivot_candidates.tsv'), prows)
    st['labels'] = dict(collections.Counter(r['lab'] for r in rows))
    st['conversational'] = sum(1 for r in rows if r['conv'])
    with open(os.path.join(a.out, 'stats.json'), 'w', encoding='utf-8') as f:
        json.dump(st, f, ensure_ascii=False, indent=1, default=dict)
    print(json.dumps(st, ensure_ascii=False, indent=1, default=dict))


if __name__ == '__main__':
    main()
