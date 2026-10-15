// lib/telemetry — что присылает приложение по согласию человека (bench/apk/…/Telemetry.java) и как это
// показать в топике «Телеметрия»: проверка без сети и базы, подписи, пределы.
//
// Пакет статистики — раз в сутки: устройство, счётчики распознавания за период, сбои. Звука, текстов
// разговора и номера установки в нём нет, адрес бот не пишет. Звук — отдельно и только по кнопке
// человека («Отправить звук разработчику»), с его текстом и согласием.

export const MAX_PACKET = 16 * 1024, MAX_AUDIO = 1536 * 1024;   // байт тела запроса (звук — в base64)
// За сутки разработчика в Telegram уходит не больше: дальше пакеты — только в базу, звук — отказ.
// Адрес бота есть в приложении, и без предела чужой скрипт засыпал бы топик.
export const POSTS_PER_DAY = 300, AUDIO_PER_DAY = 50;

// Ступени фона комнаты для счётчика asr.noise, dBFS: < −60, −60…−54, −54…−48, −48…−42, ≥ −42
// (−48 — порог шумодава нарезки). Те же границы — в Telemetry.java.
export const NOISE_EDGES = [-60, -54, -48, -42];

const KEY = /^[a-z_][a-z0-9_]{0,31}$/;

/** Копия значения из пакета: только числа, да/нет, короткие строки, неглубокие объекты и списки.
 *  Чужое (длинное, глубокое, не того типа) — ошибка: пакет не принимается целиком. */
export function clean(v, depth = 0) {
  if (typeof v === 'number') {
    if (!Number.isFinite(v) || Math.abs(v) > 1e12) throw new Error('число вне пределов');
    return Math.round(v * 1000) / 1000;
  }
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (v.length > 300) throw new Error('строка длиннее 300');
    return v.replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  }
  if (depth >= 3) throw new Error('слишком глубоко');
  if (Array.isArray(v)) {
    if (v.length > 20) throw new Error('список длиннее 20');
    return v.map((x) => clean(x, depth + 1));
  }
  if (v && typeof v === 'object') {
    const keys = Object.keys(v);
    if (keys.length > 40) throw new Error('больше 40 полей');
    const out = {};
    for (const k of keys) {
      if (!KEY.test(k)) throw new Error('поле ' + k.slice(0, 20));
      out[k] = clean(v[k], depth + 1);
    }
    return out;
  }
  throw new Error('тип ' + (v === null ? 'null' : typeof v));
}

const obj = (x) => x && typeof x === 'object' && !Array.isArray(x);

/** Пакет статистики → проверенная копия {v, app, device, asr?, crashes?}; не тот — ошибка с причиной. */
export function packet(raw) {
  if (!obj(raw) || raw.v !== 1) throw new Error('нужен v: 1');
  const p = clean(raw);
  if (!obj(p.app) || typeof p.app.name !== 'string' || typeof p.app.code !== 'number') throw new Error('нет app.name/app.code');
  if (!obj(p.device)) throw new Error('нет device');
  if (p.asr !== undefined && !obj(p.asr)) throw new Error('asr — не объект');
  if (p.crashes !== undefined && (!Array.isArray(p.crashes) || p.crashes.length > 10)) throw new Error('crashes — не список до 10');
  return p;
}

const pct = (a, b) => (b > 0 ? Math.round((100 * a) / b) + ' %' : '—');
const num = (x) => (typeof x === 'number' ? String(x).replace('.', ',') : String(x));
const pairs = (o) => Object.entries(o || {}).map(([k, v]) => k + ' ' + num(v)).join(', ');
const MIC = { builtin: 'встроенный', wired: 'проводной', bt: 'гарнитура', usb: 'USB', other: 'другой' };
const KNOWN = new Set(['hours', 'turns', 'empty', 'hold', 'listen', 'noise', 'denoise', 'wiener', 'music', 'mic',
  'rtf_ms', 'load_ms', 'edits', 'edit_chars', 'edit_total', 'foreign']);

/** Строка устройства: «Xiaomi Redmi Note 12 · Android 14 · 6 ГБ · SM6225 · прошивка …». */
export function deviceLine(d) {
  return [[d.maker, d.model].filter(Boolean).join(' ') || 'телефон без имени',
    d.android ? 'Android ' + d.android + (d.sdk ? ' (' + d.sdk + ')' : '') : null,
    d.ram_gb ? d.ram_gb + ' ГБ' : null, d.soc || null, d.build ? 'прошивка ' + d.build : null,
  ].filter(Boolean).join(' · ');
}

const appLine = (a) => 'Falar ' + a.name + ' (' + a.code + (a.slim ? ', облегчённая' : '') + ')';

/** Счётчики распознавания словами; незнакомые поля — как есть: приложение могло прибавить новые. */
export function asrLines(a) {
  const out = [];
  const turns = Object.values(a.turns || {}).reduce((s, x) => s + (Number(x) || 0), 0);
  out.push('#распознавание' + (a.hours ? ' за ' + num(Math.round(a.hours)) + ' ч' : '') + ': фраз ' + turns +
    (a.turns ? ' (' + pairs(a.turns) + ')' : '') +
    (a.empty !== undefined ? ', не распознано ' + a.empty + ' (' + pct(a.empty, turns + a.empty) + ')' : '') +
    (a.hold !== undefined || a.listen !== undefined ? ', кнопкой ' + (a.hold || 0) + ', слушанием ' + (a.listen || 0) : ''));
  if (Array.isArray(a.noise)) {
    const names = ['<−60', '−60…−54', '−54…−48', '−48…−42', '≥−42'];
    out.push('фон, dBFS: ' + a.noise.map((n, i) => (names[i] || '?') + ' ' + n).join(' · '));
  }
  const gate = [a.denoise !== undefined && 'шумодав ' + a.denoise, a.wiener !== undefined && 'Винер ' + a.wiener,
    a.music !== undefined && 'музыка ' + a.music].filter(Boolean);
  if (gate.length) out.push('нарезка: ' + gate.join(', '));
  if (obj(a.mic)) out.push('микрофон: ' + Object.entries(a.mic).map(([k, v]) => (MIC[k] || k) + ' ' + v).join(', '));
  const speed = [a.rtf_ms !== undefined && '1 с речи — ' + num(Math.round(a.rtf_ms) / 1000) + ' с',
    a.load_ms !== undefined && 'загрузка моделей ' + num(Math.round(a.load_ms / 100) / 10) + ' с'].filter(Boolean);
  if (speed.length) out.push(speed.join(', '));
  const more = [a.edits !== undefined && 'правок ' + a.edits + (a.edit_total ? ' (' + pct(a.edit_chars || 0, a.edit_total) + ' знаков)' : ''),
    a.foreign !== undefined && 'чужой голос отброшен ' + a.foreign].filter(Boolean);
  if (more.length) out.push(more.join(', '));
  const rest = Object.entries(a).filter(([k]) => !KNOWN.has(k));
  if (rest.length) out.push('ещё: ' + rest.map(([k, v]) => k + '=' + JSON.stringify(v)).join(', '));
  return out;
}

const when = (t, tz = 5) => {
  const d = new Date((t + tz * 3600) * 1000), p = (n) => String(n).padStart(2, '0');
  return p(d.getUTCDate()) + '.' + p(d.getUTCMonth() + 1) + ' ' + p(d.getUTCHours()) + ':' + p(d.getUTCMinutes());
};
const KIND = { crash: 'сбой', native: 'сбой в библиотеке', anr: 'зависание', oom: 'нехватка памяти', killed: 'закрыто системой' };

/** Сообщение в топик «Телеметрия» по пакету статистики. */
export function packetText(p) {
  const d = p.device, lines = [
    '#устройство · ' + appLine(p.app) + (p.app.ui ? ' · интерфейс ' + p.app.ui : ''),
    deviceLine(d),
  ];
  if (obj(d.fx)) {
    const fx = { ns: 'шумоподавление', agc: 'автоусиление', aec: 'эхоподавление' };
    lines.push('обработка звука в телефоне: ' + Object.entries(d.fx).map(([k, v]) => (fx[k] || k) + (v ? ' есть' : ' нет')).join(', '));
  }
  if (Array.isArray(p.app.modules) && p.app.modules.length) lines.push('модули: ' + p.app.modules.join(', '));
  if (p.asr) lines.push(...asrLines(p.asr));
  for (const c of p.crashes || []) {
    lines.push('#сбой · ' + (KIND[c.kind] || c.kind || 'сбой') + (c.what ? ' · ' + c.what : '') + (c.where ? ' · ' + c.where : '') +
      (c.v ? ' · ' + c.v : '') + (typeof c.at === 'number' ? ' · ' + when(c.at) : ''));
  }
  const t = lines.join('\n');
  return t.length > 4000 ? t.slice(0, 3999) + '…' : t;
}

const LANGS = ['pt', 'ru'];
const MIME = { 'audio/ogg': '.ogg', 'audio/wav': '.wav' };

/** Присланный звук → {lang, durMs, heard, said, mime, ext, b64, app, device}; без согласия или не тот — ошибка. */
export function audio(raw) {
  if (!obj(raw) || raw.v !== 1) throw new Error('нужен v: 1');
  if (raw.consent !== true) throw new Error('нет согласия');
  if (!LANGS.includes(raw.lang)) throw new Error('язык — pt или ru');
  if (!MIME[raw.mime]) throw new Error('звук — audio/ogg или audio/wav');
  const dur = Number(raw.dur_ms);
  if (!(dur >= 300 && dur <= 60000)) throw new Error('длина — от 0,3 до 60 с');
  const text = (s) => (typeof s === 'string' ? s.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, ' ').trim().slice(0, 1000) : '');
  if (typeof raw.audio !== 'string' || raw.audio.length < 16 || raw.audio.length > (MAX_AUDIO * 4) / 3) throw new Error('нет звука');
  if (!/^[A-Za-z0-9+/]+=*$/.test(raw.audio)) throw new Error('звук — не base64');
  const app = obj(raw.app) ? clean(raw.app) : {}, device = obj(raw.device) ? clean(raw.device) : {};
  return { lang: raw.lang, durMs: Math.round(dur), heard: text(raw.heard), said: text(raw.said) || text(raw.heard),
    mime: raw.mime, ext: MIME[raw.mime], b64: raw.audio, app, device };
}

/** Подпись к звуку в топике (Telegram: до 1024 знаков) — тексты укорачиваются, номер остаётся. */
export function audioCaption(a, n) {
  const head = '#звук · ' + a.lang + ' · ' + num(Math.round(a.durMs / 100) / 10) + ' с · № ' + n + '\n' +
    [a.app.name ? 'Falar ' + a.app.name : null, a.device.model ? deviceLine(a.device) : null].filter(Boolean).join(' · ');
  const same = a.said === a.heard;
  const room = Math.max(40, 1000 - head.length - 40), cut = (s, k) => (s.length > k ? s.slice(0, k - 1) + '…' : s);
  const body = same ? '\nРаспознано верно: ' + cut(a.heard, room)
    : '\nРаспознано: ' + cut(a.heard, Math.floor(room / 2)) + '\nНа самом деле: ' + cut(a.said, Math.floor(room / 2));
  return (head + body).slice(0, 1024);
}
