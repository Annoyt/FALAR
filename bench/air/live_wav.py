#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Звук для проверки цвета кнопки удержания на телефоне (test_mic_device.sh, M6): три фразы записи
комнаты подряд, между ними — тишина той же комнаты по PAUSE секунд, в начале — LEAD секунд тишины
(меньше 1,5 с: дольше — и кнопка до речи честно краснеет, «не слышу»).
Телефон проигрывает его вместо микрофона (--es micfile). Паузы длиннее обычных нарочно: прежний
цвет за секунду тишины сходил к красному, и снимок экрана в паузе это ловит наверняка.

  python3 bench/air/live_wav.py bench/air/rec/near-pt out.wav [номер первой фразы]
  python3 bench/air/live_wav.py bench/air/rec/near-pt out.wav --silence 5

Печатает разметку JSON: где речь, где паузы, длина — в секундах от начала файла. С --silence N —
только тишина той же комнаты, N секунд (из промежутков между фразами): удержание, в котором молчат."""
import array
import json
import sys
import wave

LEAD, PAUSE, TAIL = 0.8, 1.5, 0.8
SR = 16000


def main():
    rec, out = sys.argv[1], sys.argv[2]
    silence = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[3] == '--silence' else None
    first = int(sys.argv[3]) if len(sys.argv) > 3 and silence is None else 10
    w = wave.open(rec + '/room.wav')
    s = array.array('h', w.readframes(w.getnframes()))
    p_off = int(open(rec + '/player.offset').read())
    l_off = int(open(rec + '/listener.offset').read())
    raw = next(int(l.split('\t')[0]) - l_off for l in open(rec + '/listener.tsv', encoding='utf-8')
               if l.split('\t')[1:2] == ['raw_begin'])
    plays = []
    for l in open(rec + '/player.tsv', encoding='utf-8'):
        p = l.rstrip('\n').split('\t')
        if len(p) > 3 and p[1] == 'play':
            plays.append(((int(p[0]) - p_off - raw) / 1000.0, float(p[3]) / 1000.0))
    # Фраза — от её начала по часам проигрывателя (−50 мс) до конца + 0,3 с (динамик отстаёт);
    # тишина паузы — из середины трёхсекундного промежутка перед фразой.
    cut = lambda a, b: s[int(a * SR):int(b * SR)]
    pcm, speech = array.array('h'), []
    if silence is not None:                            # тишина: середины промежутков перед фразами first, first+1, …
        k = first
        while len(pcm) < silence * SR:
            at = plays[k][0]; pcm.extend(cut(at - 2.2, at - 0.6)); k += 1
        pcm = pcm[:int(silence * SR)]
        o = wave.open(out, 'wb'); o.setnchannels(1); o.setsampwidth(2); o.setframerate(SR); o.writeframes(pcm.tobytes()); o.close()
        print(json.dumps({'speech': [], 'pauses': [[0, round(len(pcm) / SR, 3)]], 'len': round(len(pcm) / SR, 3)}))
        return
    at0 = plays[first][0]
    pcm.extend(cut(at0 - 0.6 - LEAD, at0 - 0.6))
    for k in range(first, first + 3):
        at, dur = plays[k]
        if k > first:
            pcm.extend(cut(at - 0.8 - PAUSE, at - 0.8))
        a = len(pcm) / SR
        pcm.extend(cut(at - 0.05, at + dur + 0.3))
        speech.append([round(a, 3), round(len(pcm) / SR, 3)])
    pcm.extend(cut(plays[first + 2][0] + plays[first + 2][1] + 0.8, plays[first + 2][0] + plays[first + 2][1] + 0.8 + TAIL))
    o = wave.open(out, 'wb')
    o.setnchannels(1); o.setsampwidth(2); o.setframerate(SR); o.writeframes(pcm.tobytes()); o.close()
    pauses = [[speech[i][1], speech[i + 1][0]] for i in range(2)]
    print(json.dumps({'speech': speech, 'pauses': pauses, 'len': round(len(pcm) / SR, 3)}))


if __name__ == '__main__':
    main()
