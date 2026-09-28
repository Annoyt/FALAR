#!/usr/bin/env python3
"""Кодировщик OPUS-MT вместе с ключами и значениями перекрёстного внимания: encoder_kv_model.onnx.

Зачем. Перевод держал в памяти по три файла на направление, и два из них — декодеры с одними и теми
же весами: decoder_model.onnx делает первый шаг, decoder_with_past_model.onnx — остальные. Это
216 МБ на направление, загруженные дважды (results/2026-09-28-memory.md: перевод — 1,3 ГБ из 2,6).
Первый шаг отличается от остальных одним: он считает K/V перекрёстного внимания по выходу
кодировщика. Этот кусок — для каждого слоя квантование, MatMulInteger, масштаб, смещение, Reshape,
Transpose — переносится в кодировщик как есть, и первый шаг делает тот же decoder_with_past с
пустым прошлым декодера. decoder_model.onnx больше не нужен.

Объединённый декодер с ветвлением If отвергнут: ONNX Runtime упаковывает веса для каждого ядра
отдельно, а общего контейнера упакованных весов в Java-API нет — обе ветки держали бы свою копию.

Единственная правка переносимого куска — откуда берётся размер пакета: трассировка брала его из
скрытых состояний декодера (Shape → Gather 0), теперь — из выхода кодировщика, где он тот же.
Скрипт проверяет, что других зависимостей от декодера нет.

    python3 -m venv --system-site-packages /tmp/falar-venv && /tmp/falar-venv/bin/pip install onnx
    /tmp/falar-venv/bin/python tools/mt_encoder_kv.py models/mt/pt2ru models/mt/ru2pt
"""
import copy
import os
import sys

import onnx
from onnx import helper, numpy_helper


def cross_kv(dec):
    """Подграф decoder_model: encoder_hidden_states → present.*.encoder.*."""
    g = dec.graph
    prod = {o: n for n in g.node for o in n.output}
    inits = {t.name: t for t in g.initializer}
    cons = {}
    for n in g.node:
        for i in n.input:
            cons.setdefault(i, []).append(n)
    outs = [o for o in g.output if '.encoder.' in o.name]
    seen, done, order, redirected = set(), set(), [], 0   # done — узлы: у DynamicQuantizeLinear три выхода, узел один

    def const_value(name):
        n = prod.get(name)
        if n is not None and n.op_type == 'Constant':
            return numpy_helper.to_array(n.attribute[0].t)
        if name in inits:
            return numpy_helper.to_array(inits[name])
        return None

    def visit(name):
        nonlocal redirected
        if name in seen or name in inits or name == '' or name == 'encoder_hidden_states':
            return
        seen.add(name)
        if name not in prod:
            raise SystemExit(f'K/V перекрёстного внимания зависят от входа «{name}» — перенести нельзя')
        n = prod[name]
        if id(n) in done:
            return
        done.add(id(n))
        if n.op_type == 'Shape' and n.input[0] != 'encoder_hidden_states':
            # Размер пакета от скрытых состояний декодера: берём его у кодировщика — только если
            # из формы действительно берётся нулевое измерение, иначе смысл поменялся бы.
            for c in cons.get(n.output[0], []):
                idx = const_value(c.input[1]) if c.op_type == 'Gather' and len(c.input) > 1 else None
                if idx is None or int(idx) != 0:
                    raise SystemExit(f'форма «{n.input[0]}» используется не только для размера пакета: {c.op_type}')
            n = copy.deepcopy(n)
            n.input[0] = 'encoder_hidden_states'
            redirected += 1
        else:
            for i in n.input:
                visit(i)
        order.append(n)

    for o in outs:
        visit(o.name)
    used = sorted({i for n in order for i in n.input if i in inits})
    hid = next(i for i in g.input if i.name == 'encoder_hidden_states')
    graph = helper.make_graph(order, 'cross_kv', [hid], outs, [inits[u] for u in used])
    return graph, redirected, sum(len(inits[u].raw_data) for u in used)


def build(d):
    enc = onnx.load(os.path.join(d, 'encoder_model.onnx'))
    dec = onnx.load(os.path.join(d, 'decoder_model.onnx'))
    kv, redirected, kv_bytes = cross_kv(dec)
    e = enc.graph
    # Имена второго графа, совпавшие с именами кодировщика (трассировки нумеруют независимо),
    # переименовываются; вход и выходы не трогаем.
    taken = {t.name for t in e.initializer} | {o for n in e.node for o in n.output} | {i.name for i in e.input}
    keep = {'encoder_hidden_states'} | {o.name for o in kv.output}
    names = {t.name for t in kv.initializer} | {o for n in kv.node for o in n.output}
    ren = {x: 'kv/' + x for x in names if x in taken and x not in keep}
    for t in kv.initializer:
        t.name = ren.get(t.name, t.name)
    for n in kv.node:
        n.input[:] = [ren.get(i, i) for i in n.input]
        n.output[:] = [ren.get(o, o) for o in n.output]
        if n.name:
            n.name = 'kv' + n.name if n.name.startswith('/') else 'kv/' + n.name
    # Выход кодировщика становится входом куска K/V; наружу — только K/V: decoder_with_past
    # скрытые состояния кодировщика не принимает, ему нужно прошлое перекрёстного внимания.
    last = e.output[0].name
    for n in kv.node:
        n.input[:] = [last if i == 'encoder_hidden_states' else i for i in n.input]
    g = helper.make_graph(list(e.node) + list(kv.node), 'encoder_kv', list(e.input), list(kv.output),
                          list(e.initializer) + list(kv.initializer))
    m = helper.make_model(g, opset_imports=enc.opset_import, ir_version=enc.ir_version,
                          producer_name='falar tools/mt_encoder_kv.py')
    onnx.checker.check_model(m)
    out = os.path.join(d, 'encoder_kv_model.onnx')
    onnx.save(m, out)
    print(f'{out}: {os.path.getsize(out) / 1e6:.1f} МБ · узлов K/V {len(kv.node)}, весов K/V {kv_bytes / 1e6:.1f} МБ, '
          f'размер пакета перенаправлен в {redirected} местах, переименовано {len(ren)}')


if __name__ == '__main__':
    for d in sys.argv[1:]:
        build(d)
