#!/usr/bin/env python3
"""Скачивает проверочный набор снимков для офлайн-чтения — bench/ocr/photos/pNN.jpg.

Снимки — с Wikimedia Commons под свободными лицензиями (CC BY, CC BY-SA, CC0, общественное
достояние), в git не лежат: в репозитории только bench/ocr/photos.json (адрес, sha256, автор,
лицензия, страница файла) и расшифровка текста bench/ocr/gt.txt. Каждый файл сверяется по sha256.

  python3 tools/ocr_photos.py
"""
import hashlib, json, os, sys, time, urllib.request

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(R, 'bench', 'ocr')
UA = 'FalarOCRBench/0.1 (https://github.com/Annoyt/FALAR; offline OCR test set)'

rows = json.load(open(os.path.join(B, 'photos.json'), encoding='utf-8'))
os.makedirs(os.path.join(B, 'photos'), exist_ok=True)
bad = 0
for r in rows:
    p = os.path.join(B, 'photos', r['id'] + '.jpg')
    if os.path.exists(p) and hashlib.sha256(open(p, 'rb').read()).hexdigest() == r['sha256']:
        continue
    for k in range(4):
        try:
            b = urllib.request.urlopen(urllib.request.Request(r['url'], headers={'User-Agent': UA}), timeout=90).read()
            break
        except Exception as e:
            print(f'  {r["id"]}: {e}, повтор', file=sys.stderr); time.sleep(15)
    else:
        bad += 1; continue
    if hashlib.sha256(b).hexdigest() != r['sha256']:
        print(f'  {r["id"]}: sha256 не сошёлся — файл на Commons поменялся', file=sys.stderr); bad += 1; continue
    open(p, 'wb').write(b); print(f'  {r["id"]}  {len(b) // 1024} КБ  {r["license"]}  {r["title"]}')
    time.sleep(1)
print('готово' if bad == 0 else f'не скачано или не сошлось: {bad}')
sys.exit(1 if bad else 0)
