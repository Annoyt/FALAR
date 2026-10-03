// lib/stats — сводка скачиваний с GitHub без сети и базы: счётчики файлов выпусков, прирост со
// вчерашнего снимка и тексты для топика «Аналитика». Сеть — handlers/scheduled, база — lib/store.

// Какие файлы выпуска считаем. Falar.apk качают кнопкой «Скачать для Android» на сайте, по ссылке в
// README и при обновлении полной сборки; Falar-slim.apk — ссылкой «Облегчённая» внизу сайта и при
// обновлении облегчённой; latest.json каждая
// установленная копия спрашивает сама раз в сутки (Updates.LATEST в приложении), поэтому его прирост
// за день — примерно сколько копий было в сети. Свои телефоны тоже в счёте: отделить их нельзя.
export const APK = 'Falar.apk', SLIM = 'Falar-slim.apk', CHECK = 'latest.json';
const FILES = [APK, SLIM, CHECK];
export const REPO = 'Annoyt/FALAR';

// Время — разработчика: Алматы, UTC+5. Сводка — первым запуском после REPORT_HOUR его дня; первый
// снимок — сразу, чтобы топик ожил в день включения. Цифр к LATE_HOUR так и нет — одно сообщение об этом.
export const TZ = 5, REPORT_HOUR = 9, LATE_HOUR = 12;

const pad = (n) => String(n).padStart(2, '0');
const local = (t) => new Date((t + TZ * 3600) * 1000);
/** 'YYYY-MM-DD' — день разработчика. */
export function dayOf(t) { return local(t).toISOString().slice(0, 10); }
export function hourOf(t) { return local(t).getUTCHours(); }
export function ddmm(t) { const d = local(t); return pad(d.getUTCDate()) + '.' + pad(d.getUTCMonth() + 1); }

/** Пора ли снимать: сегодня ещё не снимали и уже REPORT_HOUR (самый первый снимок — в любой час). */
export function due(now, last) {
  if (!last) return true;
  return last.day !== dayOf(now) && hourOf(now) >= REPORT_HOUR;
}

/** Список выпусков из API GitHub → {counts: {'v0.29.0/Falar.apk': 0, …}, latest: {tag, at}, since: самый ранний тег}.
 *  Черновики не в счёт; предварительный выпуск считается, но последним не бывает. */
export function snapshot(releases) {
  const counts = {};
  let latest = null, since = null;
  for (const r of releases || []) {
    if (!r || r.draft) continue;
    let any = false;
    for (const a of r.assets || []) {
      if (FILES.includes(a.name)) { counts[r.tag_name + '/' + a.name] = Number(a.download_count) || 0; any = true; }
    }
    if (!any) continue;
    since = r.tag_name;   // GitHub отдаёт новые сверху: последний увиденный — самый ранний
    if (!latest && !r.prerelease) latest = { tag: r.tag_name, at: Math.floor(Date.parse(r.published_at) / 1000) || 0 };
  }
  return { counts, latest, since };
}

const fileOf = (k) => k.slice(k.lastIndexOf('/') + 1);
const zero = () => ({ [APK]: 0, [SLIM]: 0, [CHECK]: 0 });

/** Сумма по имени файла за все выпуски. */
export function totals(counts) {
  const t = zero();
  for (const [k, v] of Object.entries(counts || {})) if (fileOf(k) in t) t[fileOf(k)] += v;
  return t;
}

/** Прирост с прошлого снимка по имени файла. Файл нового выпуска — с нуля; счётчик, ставший меньше
 *  (файл перезалили), прибавляет ноль, а не минус. */
export function growth(cur, prev) {
  const g = zero();
  for (const [k, v] of Object.entries(cur || {})) if (fileOf(k) in g) g[fileOf(k)] += Math.max(0, v - ((prev && prev[k]) || 0));
  return g;
}

/** «за сутки» (20–28 ч), «за 17 ч», «за 2 сут.». */
export function period(sec) {
  const h = Math.round(sec / 3600);
  if (h >= 20 && h <= 28) return 'за сутки';
  if (h < 48) return 'за ' + Math.max(1, h) + ' ч';
  return 'за ' + Math.round(h / 24) + ' сут.';
}

const ver = (tag) => String(tag || '').replace(/^v/, '');

/** Текст сводки. prev — прошлый снимок {at, counts} или null: тогда это первая сводка. */
export function report(cur, prev, now) {
  const t = totals(cur.counts), c = cur.counts;
  const all = 'Всего с ' + ver(cur.since) + ': APK ' + t[APK] + ', облегчённый ' + t[SLIM] + ', проверок обновлений ' + t[CHECK];
  const rel = cur.latest
    ? 'Выпуск ' + ver(cur.latest.tag) + ' от ' + ddmm(cur.latest.at) + ': APK ' + (c[cur.latest.tag + '/' + APK] || 0) +
      ', облегчённый ' + (c[cur.latest.tag + '/' + SLIM] || 0)
    : null;
  if (!prev) {
    return ['📊 GitHub · ' + ddmm(now) + ' · первая сводка', all, rel,
      'Дальше — каждый день около ' + pad(REPORT_HOUR) + ':00: что прибавилось за сутки.',
      'APK качают с сайта и при обновлении из приложения: полный — кнопкой «Скачать для Android», облегчённый — ссылкой внизу страницы. ' +
      'Проверку обновлений каждая копия Falar делает сама раз в сутки: прирост за день — примерно сколько копий было в сети.',
    ].filter(Boolean).join('\n');
  }
  const g = growth(c, prev.counts), p = period(now - prev.at);
  return ['📊 GitHub · ' + ddmm(now) + ' · ' + p,
    'APK скачали: +' + g[APK] + ' (облегчённый: +' + g[SLIM] + ')',
    'Проверок обновлений: +' + g[CHECK] + (p === 'за сутки' ? ' — примерно столько копий Falar было в сети' : ''),
    all, rel,
  ].filter(Boolean).join('\n');
}

/** Цифр нет к LATE_HOUR: сообщение одно в день. err — {status, limited} из handlers/scheduled; token — задан ли. */
export function delayed(now, err, token) {
  const s = err && err.status;
  let why = s ? 'HTTP ' + s : 'нет ответа';
  let fix = '';
  if (s === 401) { why += ', токен GitHub не принят'; fix = 'Замените токен: bash bot/cf.sh github'; }
  else if (err && err.limited) {
    why += token ? ', лимит запросов исчерпан' : ', лимит запросов без токена';
    if (!token) fix = 'Чтобы не зависеть от лимита — токен GitHub только для чтения: bash bot/cf.sh github';
  }
  return ['📊 Сводка за ' + ddmm(now) + ' задерживается: GitHub не отдал счётчики (' + why + '). Пробую каждый час.', fix]
    .filter(Boolean).join('\n');
}
