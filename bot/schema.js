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

// Снимки счётчиков скачиваний с GitHub, раз в сутки (handlers/scheduled): только числа —
// {"counts": {"v0.29.0/Falar.apk": 2, …}, "latest": {…}, "since": "v0.21.0"}. День — разработчика (lib/stats).
export const stats = table('stats', {
  day:  text('day').primaryKey(),
  at:   integer('at').notNull(),
  data: text('data').notNull(),
});

// Пакеты статистики приложения по согласию человека (handlers/telemetry): устройство, счётчики
// распознавания, сбои — JSON без звука, текстов и номера установки; адрес не пишется.
export const telemetry = table('telemetry', {
  id:   integer('id').primaryKey({ autoIncrement: true }),
  day:  text('day').notNull(),
  at:   integer('at').notNull(),
  data: text('data').notNull(),
}, (t) => ({
  day: index('idx_telemetry_day').on(t.day),
}));

// Звук реплики, присланный человеком по кнопке и с согласием. Номер (id) приложение показывает ему —
// по нему запись удаляют (/del). Сам звук — в Telegram (chat, msg, file — его file_id), здесь — что сказано.
export const donations = table('donations', {
  id:    integer('id').primaryKey({ autoIncrement: true }),
  day:   text('day').notNull(),
  at:    integer('at').notNull(),
  lang:  text('lang').notNull(),
  durMs: integer('dur_ms').notNull(),
  heard: text('heard').notNull().default(''),
  said:  text('said').notNull().default(''),
  app:   text('app').notNull().default(''),
  chat:  integer('chat'),
  msg:   integer('msg'),
  file:  text('file'),
}, (t) => ({
  day: index('idx_donations_day').on(t.day),
}));

// Группа разработчика и номера её топиков: пишет /setup.
export const settings = table('settings', {
  key:   text('key').primaryKey(),
  value: text('value').notNull(),
});
