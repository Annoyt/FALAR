// Заглушка платформенного 'sdk' и 'sdk/db' для проверок на столе.
//   api — записывает вызовы Bot API и отвечает как Telegram (номера сообщений по порядку);
//         отказ задаётся api.fail(method, description, code) на один следующий вызов;
//   db  — db.run/get/all поверх node:sqlite в памяти: те же голые запросы, что и в облаке;
//   table/integer/text/index — ровно столько DSL schema.js, сколько нужно, чтобы построить по нему
//         таблицы: так проверка ловит и расхождение запросов lib/store со схемой.

import { DatabaseSync } from 'node:sqlite';

export class BotApiError extends Error {
  constructor(method, code, description) { super(description); this.method = method; this.code = code; this.description = description; }
}

export const calls = [];
const fails = [];
let nextId = 100;
export const api = new Proxy({}, {
  get(_, method) {
    if (method === 'fail') return (m, description, code = 400) => fails.push({ m, description, code });
    if (method === 'reset') return () => { calls.length = 0; fails.length = 0; };
    return async (params) => {
      calls.push({ method, params });
      const i = fails.findIndex((f) => f.m === method);
      if (i >= 0) { const f = fails.splice(i, 1)[0]; throw new BotApiError(method, f.code, f.description); }
      if (method === 'createForumTopic') return { message_thread_id: nextId++, name: params.name, icon_color: params.icon_color };
      if (method === 'setMessageReaction') return true;
      return { message_id: nextId++, chat: { id: params.chat_id } };
    };
  },
});

let sqlite = new DatabaseSync(':memory:');
export const db = {
  async run(q, p = {}) { sqlite.prepare(q).run(p); return []; },
  async get(q, p = {}) { return sqlite.prepare(q).get(p) ?? null; },
  async all(q, p = {}) { return sqlite.prepare(q).all(p); },
};

/** Пустая база с таблицами из schema.js. */
export function resetDb(schema) {
  sqlite = new DatabaseSync(':memory:');
  for (const t of Object.values(schema)) {
    if (!t || !t._table) continue;
    const cols = Object.entries(t.cols).map(([key, b]) => {
      const c = b._c, name = c.name || key;
      let d = name + ' ' + c.type;
      if (c.pk) d += ' PRIMARY KEY' + (c.ai ? ' AUTOINCREMENT' : '');
      if (c.nn) d += ' NOT NULL';
      if (c.uq) d += ' UNIQUE';
      if (c.def !== undefined) d += ' DEFAULT ' + (typeof c.def === 'string' ? "'" + c.def.replace(/'/g, "''") + "'" : c.def);
      return d;
    });
    sqlite.exec('CREATE TABLE ' + t._table + ' (' + cols.join(', ') + ')');
    if (t.extra) {
      for (const ix of Object.values(t.extra(t.cols))) {
        sqlite.exec('CREATE INDEX ' + ix.name + ' ON ' + t._table + ' (' + ix.cols.map((b) => b._c.name).join(', ') + ')');
      }
    }
  }
}
export function rows(q, p = {}) { return sqlite.prepare(q).all(p); }

function column(type) {
  return (name) => {
    const c = { name, type, pk: false, ai: false, nn: false, uq: false, def: undefined };
    const b = {
      _c: c,
      primaryKey(o) { c.pk = true; c.ai = !!(o && o.autoIncrement); return b; },
      notNull() { c.nn = true; return b; },
      unique() { c.uq = true; return b; },
      default(v) { c.def = v; return b; },
    };
    return b;
  };
}
export const integer = column('INTEGER');
export const text = column('TEXT');
export function index(name) { return { on: (...cols) => ({ name, cols }) }; }
export function table(name, cols, extra) { return { _table: name, cols, extra }; }
export async function fetch() { throw new Error('fetch в проверках не используется'); }
