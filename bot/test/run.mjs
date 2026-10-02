// Проверки бота на столе: node test/run.mjs (или npm test в bot/).
// Модули бота импортируют друг друга голыми именами ('sdk', 'lib/store'), как требует платформа;
// здесь эти имена ведут в файлы проекта и в заглушку test/mock-sdk.mjs.

import { registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const MAP = { 'sdk': 'test/mock-sdk.mjs', 'sdk/db': 'test/mock-sdk.mjs', 'schema': 'schema.js', 'lib/owner': 'test/owner.mjs' };
registerHooks({
  resolve(spec, ctx, next) {
    if (MAP[spec]) return { url: pathToFileURL(path.join(ROOT, MAP[spec])).href, shortCircuit: true };
    if (/^(lib|handlers)\//.test(spec)) return { url: pathToFileURL(path.join(ROOT, spec + '.js')).href, shortCircuit: true };
    return next(spec, ctx);
  },
});

const sdk = await import('sdk');
const schema = await import('schema');
const owner = await import('lib/owner');
const F = await import('lib/feedback');
const handle = (await import('handlers/message')).default;

let fails = 0, checks = 0;
function ok(c, what) { checks++; if (!c) { fails++; console.log('  ПРОВАЛ: ' + what); } }
function eq(a, b, what) { ok(JSON.stringify(a) === JSON.stringify(b), what + ': ' + JSON.stringify(a) + ' ≠ ' + JSON.stringify(b)); }

let NOW = 1_800_000_000;
Date.now = () => NOW * 1000;
const OWNER = 1000, GROUP = -100500;
const ME = { id: OWNER, is_bot: false, first_name: 'Разработчик' };
const ANNA = { id: 2001, is_bot: false, first_name: 'Анна', last_name: 'Иванова', username: 'anna' };
const BORIS = { id: 2002, is_bot: false, first_name: 'Борис' };
const BOT = { id: 9, is_bot: true, first_name: 'Falar' };
let mid = 1;
const dm = (from, text, x = {}) => ({ message_id: mid++, from, chat: { id: from.id, type: 'private' }, date: NOW, ...(text == null ? {} : { text }), ...x });
const grp = (text, x = {}, chat = { id: GROUP, type: 'supergroup', is_forum: true }) => ({ message_id: mid++, from: ME, chat, date: NOW, text, ...x });
const calls = () => sdk.calls.splice(0);
const sent = (cs, method) => cs.filter((c) => c.method === method);

// ---- чистые правила
eq(F.tagOf('#перевод · португальский → русский'), 'translation', 'F1 метка перевода');
eq(F.tagOf('  #ОШИБКА что-то'), 'bug', 'F1 метка в любом регистре, с пробелами впереди');
eq(F.tagOf('#идея'), 'idea', 'F1 метка идеи');
eq(F.tagOf('у меня #ошибка'), null, 'F1 метка только в начале');
eq(F.tagOf('#переводы'), null, 'F1 похожее слово — не метка');
eq(F.route('ещё снимок', { topic: 'bug', last_at: NOW - 60 }, NOW), 'bug', 'F2 продолжение — в тот же топик');
eq(F.route('ещё снимок', { topic: 'bug', last_at: NOW - F.FOLLOW_UP - 1 }, NOW), null, 'F2 через полчаса — в общий');
eq(F.route('#идея а вот', { topic: 'bug', last_at: NOW }, NOW), 'idea', 'F2 метка главнее продолжения');
eq(F.command('/block@falar_feedback_bot'), 'block', 'F3 команда с именем бота');
eq(F.command('/start abc'), 'start', 'F3 команда с параметром');
eq(F.command('/'), null, 'F3 голая косая — не команда');
eq(F.header(ANNA), '👤 Анна Иванова · @anna', 'F4 подпись');
eq(F.header({ id: 1 }), '👤 без имени', 'F4 подпись без имени');
let r = F.rate(null, NOW); eq([r.msgs, r.ok, r.warn], [1, true, false], 'F5 первое сообщение');
r = F.rate({ window_at: NOW - 10, msgs: F.LIMIT }, NOW); eq([r.ok, r.warn], [false, true], 'F5 первое лишнее — предупредить');
r = F.rate({ window_at: NOW - 10, msgs: F.LIMIT + 1 }, NOW); eq([r.ok, r.warn], [false, false], 'F5 дальше — молча');
r = F.rate({ window_at: NOW - F.WINDOW, msgs: 99 }, NOW); eq([r.msgs, r.ok], [1, true], 'F5 новое окно');

// ---- бот не настроен
sdk.resetDb(schema);
owner.setOwner(0);
await handle(dm(ANNA, 'привет'));
let cs = calls();
eq(cs.map((c) => [c.method, c.params.chat_id, c.params.text]), [['sendMessage', ANNA.id, F.NOT_READY]], 'B1 без разработчика — «настраивается», никуда не пересылается');
await handle(dm(ANNA, '/id'));
eq(calls()[0].params.text, 'Ваш id: 2001', 'B1 /id отвечает и ненастроенный');
owner.setOwner(OWNER);

// ---- без группы: личка разработчика
await handle(dm(ANNA, '/start'));
cs = calls();
eq(cs.map((c) => [c.method, c.params.chat_id]), [['sendMessage', ANNA.id]], 'B2 /start — только приветствие человеку');
ok(cs[0].params.text === F.GREETING, 'B2 текст приветствия');
const rep = dm(ANNA, '#перевод · португальский → русский\nИсходник: Bom dia\nПеревод приложения: Добрый день');
await handle(rep);
cs = calls();
let s = sent(cs, 'sendMessage');
eq(s.length, 2, 'B3 два сообщения: копия разработчику и «передал» человеку');
eq(s[0].params.chat_id, OWNER, 'B3 копия — разработчику в личку');
ok(s[0].params.text.startsWith('👤 Анна Иванова · @anna\n\n#перевод'), 'B3 подпись и текст одним сообщением');
eq([s[1].params.chat_id, s[1].params.text], [ANNA.id, F.ACK], 'B3 человеку — «передал»');
let rel = sdk.rows('SELECT * FROM relays');
eq(rel.map((x) => [x.chat, x.person, x.person_msg]), [[OWNER, ANNA.id, rep.message_id]], 'B3 связь копия → человек');
const copyId = rel[0].msg;
await handle(dm(ME, 'Спасибо, поправим', { reply_to_message: { message_id: copyId, from: BOT, chat: { id: OWNER, type: 'private' } } }));
cs = calls();
eq(sent(cs, 'copyMessage').map((c) => [c.params.chat_id, c.params.from_chat_id, c.params.reply_parameters.message_id]),
   [[ANNA.id, OWNER, rep.message_id]], 'B4 ответ разработчика — человеку, ответом на его сообщение');
eq(sent(cs, 'setMessageReaction').length, 1, 'B4 разработчику — реакция «доставлено»');
await handle(dm(ME, 'просто так'));
eq(calls().map((c) => c.params.text), [F.OWNER_HINT], 'B5 разработчик без ответа — подсказка');

// ---- /setup
await handle(dm(ME, '/setup'));
eq(calls().map((c) => c.params.text), [F.SETUP_PRIVATE], 'S1 /setup в личке — «в группе»');
await handle(grp('/setup', {}, { id: -1007, type: 'supergroup' }));
eq(calls().map((c) => c.params.text), [F.SETUP_NO_FORUM], 'S2 группа без топиков');
await handle(grp('/setup@falar_feedback_bot'));
cs = calls();
eq(sent(cs, 'createForumTopic').map((c) => c.params.name), ['Переводы', 'Ошибки', 'Идеи', 'Задачи'], 'S3 четыре топика');
ok(/^Готово/.test(sent(cs, 'sendMessage')[0].params.text), 'S3 «Готово»');
const topic = {};
for (const k of Object.keys(F.TOPICS)) topic[k] = Number(sdk.rows("SELECT value FROM settings WHERE key = :k", { ':k': 'topic:' + GROUP + ':' + k })[0].value);
await handle(grp('/setup'));
eq(sent(calls(), 'createForumTopic').length, 0, 'S4 повторный /setup топики не плодит');
// чужой в группе не командует
await handle({ message_id: mid++, from: BORIS, chat: { id: GROUP, type: 'supergroup', is_forum: true }, text: '/setup' });
eq(calls().length, 0, 'S5 /setup не от разработчика — тишина');

// ---- в группу по топикам
NOW += 7 * 3600;
const bug = dm(ANNA, '#ошибка Вылетает при снимке');
await handle(bug);
cs = calls();
s = sent(cs, 'sendMessage');
eq([s[0].params.chat_id, s[0].params.message_thread_id], [GROUP, topic.bug], 'G1 #ошибка — в «Ошибки»');
eq([s[1].params.chat_id, s[1].params.text], [ANNA.id, F.ACK], 'G1 через 7 ч — снова «передал»');
NOW += 300;
const photo = dm(ANNA, null, { photo: [{ file_id: 'x' }] });
await handle(photo);
cs = calls();
let c = sent(cs, 'copyMessage');
eq([c[0].params.chat_id, c[0].params.message_thread_id, c[0].params.caption], [GROUP, topic.bug, '👤 Анна Иванова · @anna'], 'G2 снимок следом — туда же, подпись подписью');
eq(sent(cs, 'sendMessage').length, 0, 'G2 второе за 6 ч — без «передал»…');
eq(sent(cs, 'setMessageReaction').map((x) => x.params.message_id), [photo.message_id], 'G2 …а реакцией');
NOW += F.FOLLOW_UP + 1;
await handle(dm(ANNA, 'и ещё вопрос'));
s = sent(calls(), 'sendMessage');
eq([s[0].params.chat_id, s[0].params.message_thread_id], [GROUP, undefined], 'G3 без метки через полчаса — в общий топик');

// ответ из топика
rel = sdk.rows('SELECT * FROM relays WHERE chat = :g ORDER BY id', { ':g': GROUP });
const bugCopy = rel.find((x) => x.person_msg === bug.message_id).msg;
await handle(grp('Посмотрю', { is_topic_message: true, message_thread_id: topic.bug, reply_to_message: { message_id: bugCopy, from: BOT } }));
cs = calls();
eq(sent(cs, 'copyMessage').map((x) => [x.params.chat_id, x.params.reply_parameters.message_id]), [[ANNA.id, bug.message_id]], 'G4 ответ в топике — человеку');
await handle(grp('заметка себе', { is_topic_message: true, message_thread_id: topic.bug, reply_to_message: { message_id: topic.bug, from: BOT, forum_topic_created: { name: 'Ошибки' } } }));
eq(calls().length, 0, 'G5 сообщение в топике без ответа — не человеку и без подсказок');
await handle(grp('а это кому?', { reply_to_message: { message_id: 1, from: BOT } }));
ok(/Не знаю, чьё/.test(calls()[0].params.text), 'G6 ответ на сообщение бота без связи — «не знаю, чьё»');

// блокировка
await handle(grp('/block', { reply_to_message: { message_id: bugCopy, from: BOT } }));
eq(sent(calls(), 'setMessageReaction').length, 1, 'K1 /block ответом — реакция');
await handle(dm(ANNA, '#ошибка опять'));
eq(calls().length, 0, 'K1 заблокированный — ни копии, ни ответа');
await handle(grp('/unblock', { reply_to_message: { message_id: bugCopy, from: BOT } }));
calls();
await handle(dm(ANNA, '#ошибка опять'));
ok(sent(calls(), 'sendMessage').some((x) => x.params.chat_id === GROUP), 'K2 после /unblock — снова доходит');

// частота
for (let i = 0; i < F.LIMIT; i++) await handle(dm(BORIS, 'сообщение ' + i));
cs = calls();
eq(sent(cs, 'sendMessage').filter((x) => x.params.chat_id === GROUP).length, F.LIMIT, 'L1 15 сообщений подряд доходят');
await handle(dm(BORIS, 'лишнее'));
eq(calls().map((x) => [x.params.chat_id, x.params.text]), [[BORIS.id, F.SLOW]], 'L2 первое лишнее — предупреждение, без копии');
await handle(dm(BORIS, 'ещё лишнее'));
eq(calls().length, 0, 'L3 дальше — молча');
NOW += F.WINDOW;
await handle(dm(BORIS, 'через 10 минут'));
ok(sent(calls(), 'sendMessage').some((x) => x.params.chat_id === GROUP), 'L4 новое окно — снова доходит');

// разработчик проверяет приложение своей меткой
await handle(dm(ME, '#идея Тёмная тема по расписанию'));
s = sent(calls(), 'sendMessage');
eq([s[0].params.chat_id, s[0].params.message_thread_id], [GROUP, topic.idea], 'O1 метка от разработчика — как от человека, в «Идеи»');

// длинное
await handle(dm(ANNA, '#перевод ' + 'а'.repeat(4100)));
cs = calls();
eq([sent(cs, 'sendMessage')[0].params.text, sent(cs, 'copyMessage').length], ['👤 Анна Иванова · @anna', 1], 'D1 длинный текст — подпись и копия');
const longId = cs.find((x) => x.method === 'copyMessage');
ok(longId && longId.params.message_thread_id === topic.translation, 'D1 копия — в «Переводы»');
await handle(dm(ANNA, null, { document: { file_id: 'd' }, caption: 'б'.repeat(1001) }));
cs = calls();
eq([sent(cs, 'sendMessage')[0].params.text, sent(cs, 'copyMessage')[0].params.caption], ['👤 Анна Иванова · @anna', undefined], 'D2 длинная подпись — подпись отдельно, копия как есть');
await handle(dm(ANNA, null, { sticker: { file_id: 's' } }));
cs = calls();
eq([sent(cs, 'sendMessage').length, sent(cs, 'copyMessage').length], [1, 1], 'D3 стикер — подпись и копия');

// сбои доставки
NOW += F.FOLLOW_UP + 1;
sdk.api.fail('sendMessage', 'Bad Request: message thread not found');
await handle(dm(ANNA, '#ошибка в удалённый топик'));
cs = calls();
s = sent(cs, 'sendMessage');
eq(s.slice(0, 2).map((x) => [x.params.chat_id, x.params.message_thread_id]), [[GROUP, topic.bug], [GROUP, undefined]], 'E1 удалённый топик — в общий');
eq(sdk.rows("SELECT count(*) AS n FROM settings WHERE key = :k", { ':k': 'topic:' + GROUP + ':bug' })[0].n, 0, 'E1 удалённый топик забыт');
await handle(grp('/setup'));
eq(sent(calls(), 'createForumTopic').map((x) => x.params.name), ['Ошибки'], 'E2 /setup пересоздаёт только его');
sdk.api.fail('sendMessage', 'Forbidden: bot was kicked from the supergroup chat', 403);
sdk.api.fail('sendMessage', 'Forbidden: bot was kicked from the supergroup chat', 403);
await handle(dm(ANNA, '#идея группа пропала'));
s = sent(calls(), 'sendMessage');
eq(s.map((x) => x.params.chat_id).slice(0, 3), [GROUP, GROUP, OWNER], 'E3 группы нет — в личку разработчику');
rel = sdk.rows('SELECT chat FROM relays ORDER BY id DESC LIMIT 1');
eq(rel[0].chat, OWNER, 'E3 связь — с личкой, ответить можно оттуда');
sdk.api.fail('copyMessage', 'Forbidden: bot was blocked by the user', 403);
await handle(grp('Ответ', { reply_to_message: { message_id: bugCopy, from: BOT } }));
ok(/Не доставлено: Forbidden: bot was blocked by the user/.test(sent(calls(), 'sendMessage')[0].params.text), 'E4 человек остановил бота — разработчику «не доставлено»');

// чужое
await handle({ message_id: mid++, from: BORIS, chat: { id: -42, type: 'group' }, text: '#ошибка' });
await handle(dm(BOT, '#ошибка от бота'));
eq(calls().length, 0, 'X1 чужие группы и боты — тишина');
const bobRow = sdk.rows('SELECT name FROM people WHERE id = :id', { ':id': BORIS.id })[0];
eq(bobRow.name, 'Борис', 'X2 в базе — имя, текста сообщений нет');
ok(!sdk.rows('PRAGMA table_info(relays)').some((x) => /text|body/.test(x.name)), 'X2 в связях нет текста');

console.log((fails ? 'ПРОВАЛОВ: ' + fails : 'Всё прошло') + ' (проверок: ' + checks + ')');
process.exit(fails ? 1 : 0);
