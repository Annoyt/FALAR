// lib/store — база бота. Голый SQL с именованными параметрами (db.run/get/all из sdk):
// таблицы описаны в schema.js, на столе те же запросы идут в node:sqlite (test/run.mjs).

import { db } from 'sdk';

// Связи копия → человек старше года не нужны: на такое старое не отвечают.
const KEEP = 365 * 24 * 3600;

export async function person(id) {
  return db.get('SELECT * FROM people WHERE id = :id', { ':id': id });
}

export async function savePerson(p) {
  await db.run(
    'INSERT INTO people (id, name, topic, last_at, window_at, msgs, ack_at, blocked) ' +
    'VALUES (:id, :name, :topic, :last_at, :window_at, :msgs, :ack_at, :blocked) ' +
    'ON CONFLICT(id) DO UPDATE SET name = excluded.name, topic = excluded.topic, last_at = excluded.last_at, ' +
    'window_at = excluded.window_at, msgs = excluded.msgs, ack_at = excluded.ack_at, blocked = excluded.blocked',
    { ':id': p.id, ':name': p.name || '', ':topic': p.topic ?? null, ':last_at': p.last_at || 0,
      ':window_at': p.window_at || 0, ':msgs': p.msgs || 0, ':ack_at': p.ack_at || 0, ':blocked': p.blocked ? 1 : 0 });
}

export async function setBlocked(id, on) {
  await db.run('UPDATE people SET blocked = :b WHERE id = :id', { ':b': on ? 1 : 0, ':id': id });
}

export async function relay(chat, msg, person, personMsg, at) {
  await db.run('INSERT INTO relays (chat, msg, person, person_msg, at) VALUES (:chat, :msg, :person, :pm, :at)',
    { ':chat': chat, ':msg': msg, ':person': person, ':pm': personMsg, ':at': at });
  await db.run('DELETE FROM relays WHERE at < :old', { ':old': at - KEEP });
}

export async function findRelay(chat, msg) {
  return db.get('SELECT * FROM relays WHERE chat = :chat AND msg = :msg ORDER BY id DESC LIMIT 1', { ':chat': chat, ':msg': msg });
}

export async function setting(key) {
  const r = await db.get('SELECT value FROM settings WHERE key = :k', { ':k': key });
  return r ? r.value : null;
}

export async function setSetting(key, value) {
  if (value == null) { await db.run('DELETE FROM settings WHERE key = :k', { ':k': key }); return; }
  await db.run('INSERT INTO settings (key, value) VALUES (:k, :v) ON CONFLICT(key) DO UPDATE SET value = excluded.value',
    { ':k': key, ':v': String(value) });
}
