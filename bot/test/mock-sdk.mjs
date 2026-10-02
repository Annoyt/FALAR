// Заглушка платформенного 'sdk' и 'sdk/db' для проверок на столе.
//   api — записывает вызовы Bot API и отвечает как Telegram (номера сообщений по порядку);
//         отказ задаётся api.fail(method, description, code) на один следующий вызов;
//   db  — db.run/get/all поверх node:sqlite в памяти: те же голые запросы, что и в облаке;
//   table/integer/text/index — DSL schema.js из cf/dsl.mjs: таблицы строятся тем же SQL, что уходит
//         в D1, и проверка ловит расхождение запросов lib/store со схемой.

import { DatabaseSync } from 'node:sqlite';
import { table, integer, text, index, ddl } from '../cf/dsl.mjs';

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

/** Пустая база с таблицами из schema.js — тем же SQL, что уходит в D1 (cf/dsl.mjs). */
export function resetDb(schema) {
  sqlite = new DatabaseSync(':memory:');
  for (const q of ddl(schema)) sqlite.exec(q);
}
export function rows(q, p = {}) { return sqlite.prepare(q).all(p); }

export { table, integer, text, index };
export async function fetch() { throw new Error('fetch в проверках не используется'); }
