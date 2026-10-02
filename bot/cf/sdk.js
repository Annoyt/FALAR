// Модуль 'sdk' на Cloudflare Workers: то же, что handlers/ и lib/ ждут от платформы — api (Bot API) и
// db (db.run/get/all с именованными параметрами), — поверх fetch и базы D1.
// Окружение Worker (токен, база) приходит с запросом: bind(env) в cf/worker.js.

export class BotApiError extends Error {
  constructor(method, code, description, parameters) {
    super(method + ': ' + description);
    this.method = method; this.code = code; this.description = description; this.parameters = parameters;
  }
}

let TOKEN = '', BASE = 'https://api.telegram.org', D1 = null;

/** Токен бота, база и (для проверок) другой адрес Bot API — из окружения Worker. */
export function bind(env) {
  TOKEN = env.BOT_TOKEN || '';
  BASE = (env.TG_API || 'https://api.telegram.org').replace(/\/+$/, '');
  D1 = env.DB || null;
}

/** api.sendMessage({...}) → result; ошибка Telegram → BotApiError с кодом и описанием. */
export const api = new Proxy({}, {
  get(_, method) {
    if (typeof method !== 'string' || method === 'then') return undefined;   // не «thenable»: await api не зовёт Bot API
    return async (params = {}) => {
      const r = await fetch(BASE + '/bot' + TOKEN + '/' + method, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(params),
      });
      let j = null;
      try { j = await r.json(); } catch (e) { /* не JSON — ниже ошибка по статусу */ }
      if (!j) throw new BotApiError(method, r.status, 'HTTP ' + r.status);
      if (!j.ok) throw new BotApiError(method, j.error_code, j.description, j.parameters);
      return j.result;
    };
  },
});

/** ':name' → '?N': D1 именованных параметров не понимает. Имя, встреченное дважды, — один номер. */
export function positional(q, params = {}) {
  const names = [];
  const sql = q.replace(/:([A-Za-z_][A-Za-z0-9_]*)/g, (_, n) => {
    let i = names.indexOf(n);
    if (i < 0) { names.push(n); i = names.length - 1; }
    return '?' + (i + 1);
  });
  return { sql, values: names.map((n) => (params[':' + n] === undefined ? null : params[':' + n])) };
}

function stmt(q, p) {
  if (!D1) throw new Error('нет базы D1: в wrangler.jsonc нужна привязка DB');
  const { sql, values } = positional(q, p);
  return D1.prepare(sql).bind(...values);
}

export const db = {
  async run(q, p) { await stmt(q, p).run(); return []; },
  async get(q, p) { return (await stmt(q, p).first()) ?? null; },
  async all(q, p) { return (await stmt(q, p).all()).results || []; },
};
