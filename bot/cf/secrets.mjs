// Секреты Worker из bot.env одним JSON — в `wrangler secret bulk` через трубу (bot/cf.sh):
//   node cf/secrets.mjs | npx --no-install wrangler secret bulk
// На диск и в командную строку они так не попадают.
import { readEnv } from './tg.mjs';

const file = process.env.FALAR_BOT_ENV || process.env.HOME + '/.config/falar/bot.env';
const env = readEnv(file);
for (const k of ['BOT_TOKEN', 'WEBHOOK_SECRET']) {
  if (!env[k]) { console.error('нет ' + k + ' в ' + file); process.exit(1); }
}
process.stdout.write(JSON.stringify({ BOT_TOKEN: env.BOT_TOKEN, WEBHOOK_SECRET: env.WEBHOOK_SECRET, OWNER: env.FALAR_BOT_OWNER || '0' }));
