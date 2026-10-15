// Cloudflare Worker бота обратной связи: Telegram шлёт обновления POST-запросом на /telegram
// (webhook ставит bot/cf.sh deploy). Чужие запросы отсекаются по секрету в заголовке: его знают
// только Telegram и этот Worker.

import handle from 'handlers/message';
import handleButton from 'handlers/callback_query';
import daily from 'handlers/scheduled';
import * as telemetry from 'handlers/telemetry';
import { MAX_PACKET, MAX_AUDIO } from 'lib/telemetry';
import { bind } from 'sdk';
import { setOwner } from 'lib/owner';

/** Данные приложения, присланные по согласию человека: /t — статистика, /audio — звук реплики. Заголовок
 *  X-Falar отсекает случайных сканеров адресов (секретом он не является: он есть в приложении). Ни адрес,
 *  ни тело запроса в журнал не пишутся. */
async function fromApp(req, env, path) {
  const json = (status, body) => Response.json(body, { status });
  if (req.method !== 'POST') return json(405, { ok: false, error: 'POST' });
  if (req.headers.get('x-falar') !== '1') return json(403, { ok: false, error: 'не приложение' });
  const max = path === '/t' ? MAX_PACKET : MAX_AUDIO;
  if (Number(req.headers.get('content-length') || 0) > max) return json(413, { ok: false, error: 'слишком большое' });
  const text = await req.text();
  if (text.length > max) return json(413, { ok: false, error: 'слишком большое' });
  let raw;
  try { raw = JSON.parse(text); } catch (e) { return json(400, { ok: false, error: 'не JSON' }); }
  bind(env); setOwner(env.OWNER);
  try {
    const r = path === '/t' ? await telemetry.packet(raw) : await telemetry.audio(raw);
    return json(r.status, r.body);
  } catch (e) { console.error('приложение ' + path, (e && e.message) || e); return json(500, { ok: false, error: 'сбой бота' }); }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === '/t' || url.pathname === '/audio') return fromApp(req, env, url.pathname);
    if (url.pathname !== '/telegram') return new Response('Falar: бот обратной связи — https://t.me/falar_tbot\n', { status: 404 });
    if (req.method !== 'POST') return new Response('POST only\n', { status: 405 });
    if (!env.WEBHOOK_SECRET || req.headers.get('X-Telegram-Bot-Api-Secret-Token') !== env.WEBHOOK_SECRET) {
      return new Response('forbidden\n', { status: 403 });
    }
    let u;
    try { u = await req.json(); } catch (e) { return new Response('bad json\n', { status: 400 }); }
    bind(env); setOwner(env.OWNER);
    // Ошибка обработки — в журнал (wrangler tail), а Telegram всё равно получает 200: иначе он
    // повторял бы то же обновление снова и снова.
    try {
      if (u && u.message) await handle(u.message, { update: u });
      else if (u && u.callback_query) await handleButton(u.callback_query, { update: u });
    } catch (e) { console.error('handler', (e && e.stack) || e); }
    return new Response('ok\n');
  },
  // Раз в час (cron в wrangler.template.jsonc): сводка скачиваний с GitHub — раз в сутки, остальные
  // запуски только видят, что сегодня она уже есть. GITHUB_TOKEN — необязательный секрет (cf.sh github),
  // GITHUB_API — другой адрес API для проверок (test/e2e.sh).
  async scheduled(controller, env) {
    bind(env); setOwner(env.OWNER);
    try { await daily({ token: env.GITHUB_TOKEN, base: env.GITHUB_API }); }
    catch (e) { console.error('scheduled', (e && e.stack) || e); }
  },
};
