// handlers/scheduled — запуск по расписанию Worker, раз в час (cron в wrangler.template.jsonc).
//
// Раз в сутки бот снимает с GitHub счётчики скачиваний файлов выпусков и кладёт сводку в топик
// «Аналитика» группы разработчика; топика нет — создаёт его сам. Снимок (только числа) ложится в базу
// после того, как сводка ушла: завтрашний прирост считается от него. Не вышло — следующий час
// попробует снова; остальные запуски видят, что сегодня сводка уже есть, и ничего не делают.

import { OWNER } from 'lib/owner';
import * as S from 'lib/stats';
import * as store from 'lib/store';
import { textTo } from 'lib/topics';

/** cfg: token — токен GitHub (необязательно), base — другой адрес API (для проверок). */
export default async function (cfg = {}) {
  if (!OWNER) return;
  const now = Math.floor(Date.now() / 1000);
  const last = await store.lastSnapshot();
  if (!S.due(now, last)) return;
  let cur;
  try {
    cur = S.snapshot(await releases(cfg));
  } catch (e) {
    console.error('stats: GitHub', e.status || '', e.message);
    const day = S.dayOf(now);
    if (S.hourOf(now) >= S.LATE_HOUR && (await store.setting('stats_notice')) !== day) {
      if (await textTo('analytics', S.delayed(now, e, !!cfg.token))) await store.setSetting('stats_notice', day);
    }
    return;
  }
  const prev = last ? { at: last.at, ...JSON.parse(last.data) } : null;
  if (await textTo('analytics', S.report(cur, prev, now))) await store.saveSnapshot(S.dayOf(now), now, JSON.stringify(cur));
}

/** Все выпуски репозитория: страницами по 100. Ошибка — Error со status и limited (лимит запросов). */
async function releases({ token, base } = {}) {
  const root = (base || 'https://api.github.com').replace(/\/+$/, '');
  const out = [];
  for (let page = 1; page <= 5; page++) {
    const r = await fetch(root + '/repos/' + S.REPO + '/releases?per_page=100&page=' + page, {
      headers: {
        'user-agent': 'falar-feedback-bot', accept: 'application/vnd.github+json', 'x-github-api-version': '2022-11-28',
        ...(token ? { authorization: 'Bearer ' + token } : {}),
      },
    });
    if (r.status !== 200) {
      throw Object.assign(new Error('HTTP ' + r.status), {
        status: r.status, limited: (r.status === 403 || r.status === 429) && r.headers.get('x-ratelimit-remaining') === '0',
      });
    }
    const list = await r.json();
    if (!Array.isArray(list)) throw Object.assign(new Error('не список выпусков'), { status: r.status });
    out.push(...list);
    if (list.length < 100) break;
  }
  return out;
}
