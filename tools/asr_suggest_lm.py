#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 11 results/2026-10-27-asr-confidence.md, источник «языковая модель»: по фразе, где отмечено сомнительное
слово, локальная модель предлагает три слова, которые, вероятно, сказали на самом деле. Видеокарта ПК (llama.cpp с
CUDA: PYTHONPATH=~/tools/llama-cuda), жадно (temperature 0), кэш с продолжением.

    PYTHONPATH=~/tools/llama-cuda .venv/bin/python tools/asr_suggest_lm.py слова.json кэш.json --model путь.gguf

слова.json — выписка tools/asr_suggest.py --dump; берутся неверные сомнительные слова, заменяющие одно слово эталона.
Ответ сверяется с эталоном там же: tools/asr_suggest.py … --lm кэш.json.
"""
import argparse
import json
import os
import re
import sys

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import asr_confidence as ac  # noqa: E402

LANG = {'pt': 'Brazilian Portuguese', 'ru': 'Russian'}


def prompt(item):
    words = item['sent'].split()
    i = item['idx']
    marked = ' '.join(words[:i] + [f'[[{words[i]}]]'] + words[i + 1:])
    lang = LANG[item['lang']]
    return (f'This {lang} sentence comes from speech recognition, without punctuation. The word in double brackets '
            f'was probably misheard.\n\n{marked}\n\nWhat did the speaker most likely say instead of '
            f'[[{words[i]}]]? Give exactly 3 different {lang} words, most likely first, separated by commas, '
            f'and nothing else.')


def parse(text):
    out = []
    for p in re.split(r'[,\n;/]+', text):
        p = re.sub(r'^\s*(\d+[.)]|[-*•])\s*', '', p).strip().strip('"«»\'.')
        n = ac.norm(p)
        if len(n) == 1 and n[0] not in out:
            out.append(n[0])
    return out[:3]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('items'); ap.add_argument('cache')
    ap.add_argument('--model', required=True)
    a = ap.parse_args()
    from llama_cpp import Llama
    llm = Llama(model_path=a.model, n_ctx=1024, n_gpu_layers=-1, n_threads=1, verbose=False)
    items = [g for g in json.load(open(a.items, encoding='utf-8')) if not g['ok'] and g['ref']]
    cache = json.load(open(a.cache, encoding='utf-8')) if os.path.exists(a.cache) else {}
    raw_path = a.cache[:-5] + '.raw.json'
    raw = json.load(open(raw_path, encoding='utf-8')) if os.path.exists(raw_path) else {}
    for k, g in enumerate(items):
        key = f'{g["phrase"]}#{g["idx"]}'
        if key in cache: continue
        r = llm.create_chat_completion(messages=[{'role': 'user', 'content': prompt(g)}], temperature=0.0, max_tokens=40)
        txt = r['choices'][0]['message']['content']
        cache[key] = parse(txt); raw[key] = txt
        if k % 20 == 0:
            json.dump(cache, open(a.cache + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(a.cache + '.tmp', a.cache)
            json.dump(raw, open(raw_path, 'w', encoding='utf-8'), ensure_ascii=False)
            print(f'{k + 1}/{len(items)}', flush=True)
    json.dump(cache, open(a.cache + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(a.cache + '.tmp', a.cache)
    json.dump(raw, open(raw_path, 'w', encoding='utf-8'), ensure_ascii=False)
    print(f'готово: {len(cache)} ответов')


if __name__ == '__main__':
    main()
