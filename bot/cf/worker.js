// Cloudflare Worker бота обратной связи: Telegram шлёт обновления POST-запросом на /telegram
// (webhook ставит bot/cf.sh deploy). Чужие запросы отсекаются по секрету в заголовке: его знают
// только Telegram и этот Worker.

import handle from 'handlers/message';
import handleButton from 'handlers/callback_query';
import daily from 'handlers/scheduled';
import { bind } from 'sdk';
import { setOwner } from 'lib/owner';

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
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
