import { table, integer, text, index } from 'sdk/db';

// Кто писал боту. Имя — для подписи в группе; topic и last_at — чтобы продолжение письма
// (снимок экрана следом за текстом) легло в тот же топик; window_at и msgs — ограничение частоты;
// ack_at — когда человеку последний раз отвечали «передал»; blocked — /block разработчика.
export const people = table('people', {
  id:       integer('id').primaryKey(),
  name:     text('name').notNull().default(''),
  topic:    text('topic'),
  lastAt:   integer('last_at').notNull().default(0),
  windowAt: integer('window_at').notNull().default(0),
  msgs:     integer('msgs').notNull().default(0),
  ackAt:    integer('ack_at').notNull().default(0),
  blocked:  integer('blocked').notNull().default(0),
});

// Копия у разработчика → чьё это сообщение. По ней ответ (reply) разработчика уходит человеку.
// Только номера, без текста.
export const relays = table('relays', {
  id:        integer('id').primaryKey({ autoIncrement: true }),
  chat:      integer('chat').notNull(),
  msg:       integer('msg').notNull(),
  person:    integer('person').notNull(),
  personMsg: integer('person_msg').notNull(),
  at:        integer('at').notNull(),
}, (t) => ({
  chatMsg: index('idx_relays_chat_msg').on(t.chat, t.msg),
}));

// Группа разработчика и номера её топиков: пишет /setup.
export const settings = table('settings', {
  key:   text('key').primaryKey(),
  value: text('value').notNull(),
});
