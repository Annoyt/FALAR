// Проверка токена GitHub из bot.env — для bot/cf.sh github: печатает, сколько запросов в час он даёт.
// Токен читается из файла здесь, а не приходит в командной строке: её видно в ps. Сам токен не печатается.
import { readEnv } from './tg.mjs';

const file = process.env.FALAR_BOT_ENV || process.env.HOME + '/.config/falar/bot.env';
const env = readEnv(file);
if (!env.GITHUB_TOKEN) { console.error('нет GITHUB_TOKEN в ' + file); process.exit(1); }
const r = await fetch('https://api.github.com/rate_limit', {
  headers: { 'user-agent': 'falar-feedback-bot', accept: 'application/vnd.github+json', authorization: 'Bearer ' + env.GITHUB_TOKEN },
});
if (r.status !== 200) { console.error('GitHub ответил ' + r.status); process.exit(1); }
const lim = (await r.json()).resources.core.limit;
// Клоны и просмотры для сводки «Аналитика» — только с доступом «Administration: read» к репозиторию.
const t = await fetch('https://api.github.com/repos/Annoyt/FALAR/traffic/clones', {
  headers: { 'user-agent': 'falar-feedback-bot', accept: 'application/vnd.github+json', authorization: 'Bearer ' + env.GITHUB_TOKEN },
});
console.log(lim + ' запросов в час; клоны и просмотры репозитория — ' + (t.status === 200 ? 'видны' : 'не видны (' + t.status + ': нужен доступ «Administration: read» к Annoyt/FALAR)'));
