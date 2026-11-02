#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка tools/colab/parakeet_ft.py на ПК.

    python3 tools/colab/test_parakeet_ft.py              # разметка: знаки, междометия, отбор CORAA — без NeMo
    python3 tools/colab/test_parakeet_ft.py --pipeline   # весь путь в малом размере (профиль smoke) — нужен NeMo
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parakeet_ft as P  # noqa: E402

fails = 0


def eq(got, want, what):
    global fails
    if got != want:
        fails += 1
        print(f'ПРОВАЛ {what}: {got!r} ≠ {want!r}')


def units():
    eq(P.words_norm('Ёлка, «ёж»! Guarda-chuva d\'água.'), ['елка', 'еж', 'guarda-chuva', "d'água"], 'нормализация слов')
    eq(round(P.wer('a b c d', 'a x c'), 2), 0.5, 'WER')
    eq(P.wer('', ''), 0.0, 'WER пустых')
    # междометия: убираются, знак после них остаётся у слова слева
    eq(P.drop_fillers('Então, a gente, minha mãe participava ãh, e ao mesmo', 'pt'),
       'Então, a gente, minha mãe participava, e ao mesmo', 'междометие со знаком')
    eq(P.drop_fillers('uh', 'pt'), '', 'одно междометие — пусто')
    eq(P.drop_fillers('Ну э-э давай завтра', 'ru'), 'Ну давай завтра', 'русское междометие')
    eq(P.drop_fillers('Tá bom, né?', 'pt'), 'Tá bom, né?', '«né» — слово, не междометие')
    # знаки и заглавные — от parakeet, слова — из разметки
    t, miss = P.pnc_transfer('eu quero um café por favor', 'Eu quero um café, por favor.')
    eq((t, miss), ('Eu quero um café, por favor.', 0.0), 'знаки при совпадении слов')
    t, miss = P.pnc_transfer('eu quero um café por favor', 'Eu quero o café, por favor.')
    eq(t, 'Eu quero um café, por favor.', 'слово разметки вместо ошибки parakeet')
    eq(round(miss, 3), round(1 / 6, 3), 'доля несовпавших слов')
    t, miss = P.pnc_transfer('сколько денег осталось на сбербанке', 'Сколько денег осталось на Сбербанке?')
    eq(t, 'Сколько денег осталось на Сбербанке?', 'русский, имя с заглавной от parakeet')
    t, _ = P.pnc_transfer('não tranquilo vamos só pedir pra ela', 'Não, tranquilo. Vamos só pedir pra ela.')
    eq(t, 'Não, tranquilo. Vamos só pedir pra ela.', 'два предложения')
    t, miss = P.pnc_transfer('bom dia', 'Obrigado.')
    eq(t, 'Bom dia.', 'ничего не совпало — слова разметки, оформление ответа (фраза всё равно отбрасывается)')
    eq(miss, 1.0, 'ничего не совпало — доля 1')
    # отбор CORAA
    ok = dict(variety='pt_br', up_votes='2', down_votes='1', votes_for_noise_or_low_voice='0.0',
              votes_for_second_voice='0.0')
    eq(P.coraa_ok(ok), True, 'CORAA: хорошая фраза')
    eq(P.coraa_ok(dict(ok, variety='pt_pt')), False, 'CORAA: pt-PT — мимо')
    eq(P.coraa_ok(dict(ok, votes_for_second_voice='1.0')), False, 'CORAA: второй голос — мимо')
    eq(P.coraa_ok(dict(ok, up_votes='1', down_votes='1')), False, 'CORAA: голоса поровну — мимо')
    # разметка: цифры у parakeet при словах в разметке — фраза отбрасывается; со знаками — как есть
    rows = [dict(text='tenho dois filhos', pnc=False), dict(text='mil novecentos e quarenta', pnc=False),
            dict(text='Tenho dois filhos.', pnc=True)]
    keep, why = P.label_rows(rows, ['Tenho dois filhos.', 'Em 1940.', 'Tenho dois filhos.'], P.CFG)
    eq([r['text'] for r in keep], ['Tenho dois filhos.', 'Tenho dois filhos.'], 'разметка: оставлено')
    eq(why['цифры'], 1, 'разметка: отброшено из-за цифр')
    # поток с повтором: обрыв посреди — тот же порядок, без повторов и пропусков
    calls = {'n': 0}

    def flaky():
        calls['n'] += 1
        for k in range(10):
            if calls['n'] == 1 and k == 6:
                raise OSError('499 Client Error (проверка)')
            yield f'строка {k}'
    got = [x for _, x in P.robust_rows(flaky, wait0=0)]
    eq(got, [f'строка {k}' for k in range(10)], 'поток с повтором: все строки по порядку, один раз')
    eq(calls['n'], 2, 'поток с повтором: пересоздан один раз')


def pipeline():
    """Весь путь в малом размере: профиль smoke, данные — потоком с Hugging Face, шум — свой (DEMAND и голоса
    записей комнаты), комнаты — простые синтетические; на видеокарте ПК (4 ГБ) кодировщик заморожен."""
    import json
    import subprocess
    root = Path(__file__).resolve().parents[2]
    main = root.parent.parent if root.parent.name == '.worktrees' else root
    work = main / '.stand' / 'parakeet-ft-smoke'
    cfg = dict(work=str(work / 'work'), cache=str(work / 'cache'), profile='smoke', base=str(main / 'models' / 'parakeet-nemo' / 'parakeet-tdt-0.6b-v3.nemo'),
               noise_src='dirs', noise_dirs=[str(main / 'data' / 'noise' / 'demand')], speech_dirs=[str(main / 'bench' / 'air' / 'fresh')],
               rir_src='synth', freeze_encoder=True, export_fp32=False)
    work.mkdir(parents=True, exist_ok=True)
    (work / 'cfg.json').write_text(json.dumps(cfg, ensure_ascii=False), encoding='utf-8')
    for stage in ('data', 'labels', 'noise', 'train', 'evaluate', 'export'):
        r = subprocess.run([sys.executable, str(Path(P.__file__)), stage, '--cfg', str(work / 'cfg.json')])
        if r.returncode:
            print(f'ПРОВАЛ этап {stage} (код {r.returncode})')
            return 1
    for f in ('encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt'):
        if not (Path(cfg['work']) / 'export' / f).exists():
            print('ПРОВАЛ нет', f)
            return 1
    print('весь путь прошёл:', work)
    return 0


if __name__ == '__main__':
    units()
    print('parakeet_ft: проверки разметки ' + ('прошли' if not fails else f'— провалов {fails}'))
    if '--pipeline' in sys.argv:
        sys.exit(pipeline() or (1 if fails else 0))
    sys.exit(1 if fails else 0)
