#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Какой плейсхолдер переживает MT.

Маскирование заменяет числа, цены и адреса на плейсхолдер, MT переводит текст вокруг него,
а unmask возвращает содержимое обратно. Если модель плейсхолдер искажает — слот теряется
и его содержимое уезжает в конец фразы. Проверяем кандидатов на настоящей модели проекта.

Результат замера (12.09.2026): «N1» выживает 6/6 в pt->ru, но 1/6 в ru->pt — модель съедает
букву и оставляет «1». «XQ1» и «ZZ1» выживают 6/6 в обе стороны. Выбран XQ.
"""
import sys
import ctranslate2
import sentencepiece as spm

FORMS = ['N%d', 'XQ%d', 'ZZ%d', 'Q%dQ']
TESTS = {
    'pt2ru': ['O mercado fica na rua %s.', 'Vá pela Avenida %s até a praça.',
              'Custa %s reais e fica perto.', 'Ele mora na rua %s hoje.',
              'O ônibus %s para aqui?', 'Encontro você às %s na %s.'],
    'ru2pt': ['Мне нужно на улицу %s.', 'Отвезите меня на проспект %s.',
              'Это стоит %s рублей.', 'Я живу на улице %s сейчас.',
              'Автобус %s здесь останавливается?', 'Встретимся в %s на %s.'],
}


def main():
    for d, tests in TESTS.items():
        sp = spm.SentencePieceProcessor(model_file='models/opus-onnx/%s/source.spm' % d)
        tp = spm.SentencePieceProcessor(model_file='models/opus-onnx/%s/target.spm' % d)
        tr = ctranslate2.Translator('models/opus-ct2/%s' % d, device='cpu', compute_type='int8',
                                    inter_threads=4, intra_threads=2)
        print('===', d)
        for f in FORMS:
            sents, want = [], []
            for t in tests:
                ph = [f % (i + 1) for i in range(t.count('%s'))]
                sents.append(t % tuple(ph))
                want.append(ph)
            res = tr.translate_batch([sp.encode(s, out_type=str) + ['</s>'] for s in sents],
                                     max_batch_size=8, beam_size=1)
            outs = [tp.decode(r.hypotheses[0]) for r in res]
            ok = sum(1 for o, w in zip(outs, want) if all(x in o for x in w))
            print('  %-5s целых %d/%d' % (f.replace('%d', 'k'), ok, len(tests)))
            if ok < len(tests) and '--show' in sys.argv:
                for o, w in zip(outs, want):
                    if not all(x in o for x in w):
                        print('       ломается:', o[:70])


if __name__ == '__main__':
    main()
