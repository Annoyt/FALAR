// Bot API с токеном из bot.env — для bot/cf.sh:
//   node cf/tg.mjs getMe
//   node cf/tg.mjs setWebhook '{"url":"https://…/telegram"}'   # secret_token добавится из файла
// Токен и секрет webhook читаются из файла здесь, а не приходят в командной строке: её видно в ps.
// Печатается ответ Telegram — в нём токена нет.
import fs from 'node:fs';

export function readEnv(file) {
  const env = {};
  for (const l of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = /^([A-Z0-9_]+)=(.*)$/.exec(l);
    if (m) env[m[1]] = m[2];
  }
  return env;
}

if (import.meta.main) {
  const file = process.env.FALAR_BOT_ENV || process.env.HOME + '/.config/falar/bot.env';
  const env = readEnv(file);
  if (!env.BOT_TOKEN) { console.error('нет BOT_TOKEN в ' + file + ': bash bot/cf.sh token'); process.exit(1); }
  const [method, json] = process.argv.slice(2);
  const params = json ? JSON.parse(json) : {};
  if (method === 'setWebhook') params.secret_token = env.WEBHOOK_SECRET;
  const r = await fetch('https://api.telegram.org/bot' + env.BOT_TOKEN + '/' + method, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(params),
  });
  const j = await r.json();
  console.log(JSON.stringify(j, null, 1));
  process.exit(j.ok ? 0 : 1);
}
