// Проверки бота на столе: node test/run.mjs (или npm test в bot/).
// Модули бота импортируют друг друга голыми именами ('sdk', 'lib/store'), как требует платформа;
// здесь эти имена ведут в файлы проекта и в заглушку test/mock-sdk.mjs.

import { registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const MAP = { 'sdk': 'test/mock-sdk.mjs', 'sdk/db': 'test/mock-sdk.mjs', 'schema': 'schema.js' };
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
eq(F.command('/block@falar_tbot'), 'block', 'F3 команда с именем бота');
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
await handle(grp('/setup@falar_tbot'));
cs = calls();
eq(sent(cs, 'createForumTopic').map((c) => c.params.name), ['Переводы', 'Ошибки', 'Идеи', 'Задачи', 'Аналитика'], 'S3 пять топиков');
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

// ---- кнопки темы: человек выбирает сам, разработчик раскладывает одним касанием
const button = (await import('handlers/callback_query')).default;
let qid = 1;
const cb = (from, data, message) => ({ id: String(qid++), from, data, message });
const VERA = { id: 2003, is_bot: false, first_name: 'Вера' };
const GLEB = { id: 2004, is_bot: false, first_name: 'Глеб' };
await handle(grp('/setup'));            // топики на месте (после E2 «Ошибки» пересоздан)
calls();
for (const k of Object.keys(F.TOPICS)) topic[k] = Number(sdk.rows("SELECT value FROM settings WHERE key = :k", { ':k': 'topic:' + GROUP + ':' + k })[0].value);
NOW += 7 * 3600;
await handle(dm(VERA, '/start'));
cs = calls();
eq(cs[0].params.reply_markup && cs[0].params.reply_markup.keyboard.flat().map((b) => b.text), ['🐞 Ошибка', '💡 Предложение', '🌐 Перевод'], 'KB1 /start — кнопки темы под полем ввода');
await handle(dm(VERA, '🐞 Ошибка'));
cs = calls();
eq(cs.map((c) => [c.method, c.params.chat_id, c.params.text]), [['sendMessage', VERA.id, F.PROMPTS.bug]], 'KB2 кнопка «Ошибка» — просьба описать, никуда не пересылается');
await handle(dm(VERA, 'Не открывается камера'));
s = sent(calls(), 'sendMessage');
eq([s[0].params.chat_id, s[0].params.message_thread_id, !!s[0].params.reply_markup], [GROUP, topic.bug, false], 'KB3 следующее сообщение — в «Ошибки», без кнопок');
const g1 = dm(GLEB, 'Хорошее приложение, но хочу тёмную тему');
await handle(g1);
cs = calls();
s = sent(cs, 'sendMessage');
const gen = s.find((x) => x.params.chat_id === GROUP);
eq([gen.params.message_thread_id, gen.params.reply_markup.inline_keyboard[0].map((b) => b.callback_data)], [undefined, ['o:bug', 'o:idea', 'o:translation']], 'KB4 без темы — в общий топик, на копии кнопки разработчику');
const askMsg = s.find((x) => x.params.chat_id === GLEB.id);
eq([askMsg.params.text, askMsg.params.reply_markup.inline_keyboard[0].map((b) => b.callback_data)],
   [F.ASK, ['c:bug:' + g1.message_id, 'c:idea:' + g1.message_id, 'c:translation:' + g1.message_id]], 'KB4 человеку — «о чём это?» с кнопками');
const genCopy = sdk.rows('SELECT msg FROM relays WHERE person = :p', { ':p': GLEB.id })[0].msg;
await button(cb(GLEB, 'c:idea:' + g1.message_id, { message_id: 777, chat: { id: GLEB.id, type: 'private' } }));
cs = calls();
let cp = sent(cs, 'copyMessage');
eq(cp.map((x) => [x.params.chat_id, x.params.message_thread_id, x.params.from_chat_id, x.params.message_id, x.params.reply_markup]), [[GROUP, topic.idea, GROUP, genCopy, undefined]], 'KB5 нажатие человека — копия в «Идеи», без кнопок');
eq(sent(cs, 'deleteMessage').map((x) => [x.params.chat_id, x.params.message_id]), [[GROUP, genCopy]], 'KB5 копия из общего топика удалена');
eq(sent(cs, 'answerCallbackQuery').map((x) => x.params.text), ['Перенесено в «Идеи»'], 'KB5 ответ на нажатие');
eq(sent(cs, 'editMessageText').map((x) => [x.params.message_id, x.params.text, x.params.reply_markup.inline_keyboard.length]), [[777, F.sortedText('idea'), 0]], 'KB5 под ответом человеку кнопок больше нет');
const moved = sdk.rows('SELECT msg FROM relays WHERE person = :p', { ':p': GLEB.id })[0].msg;
ok(moved !== genCopy, 'KB5 связь — уже с новой копией');
await handle(dm(GLEB, 'И ещё шрифт крупнее'));
s = sent(calls(), 'sendMessage');
eq([s[0].params.chat_id, s[0].params.message_thread_id], [GROUP, topic.idea], 'KB6 продолжение — туда, куда отнесли');
await handle(grp('Сделаю', { is_topic_message: true, message_thread_id: topic.idea, reply_to_message: { message_id: moved, from: BOT } }));
eq(sent(calls(), 'copyMessage').map((x) => [x.params.chat_id, x.params.reply_parameters.message_id]), [[GLEB.id, g1.message_id]], 'KB7 ответ на перенесённую копию доходит человеку');
NOW += F.FOLLOW_UP + 1;
const g2 = dm(GLEB, 'а ещё вопрос про обновления');
await handle(g2);
calls();
const gen2 = sdk.rows('SELECT msg FROM relays WHERE person = :p AND person_msg = :m', { ':p': GLEB.id, ':m': g2.message_id })[0].msg;
await button(cb(BORIS, 'o:bug', { message_id: gen2, chat: { id: GROUP, type: 'supergroup' } }));
cs = calls();
eq([cs.length, cs[0].method, cs[0].params.text], [1, 'answerCallbackQuery', 'Раскладывает только разработчик'], 'KB8 чужой не раскладывает');
sdk.api.fail('deleteMessage', 'Bad Request: message can\'t be deleted');
await button(cb(ME, 'o:bug', { message_id: gen2, chat: { id: GROUP, type: 'supergroup' } }));
cs = calls();
eq(sent(cs, 'copyMessage').map((x) => x.params.message_thread_id), [topic.bug], 'KB9 разработчик — одним касанием в «Ошибки»');
eq(sent(cs, 'editMessageReplyMarkup').map((x) => [x.params.message_id, x.params.reply_markup.inline_keyboard.length]), [[gen2, 0]], 'KB9 старше 48 ч не удалить — с неё снимаются кнопки');
eq(sent(cs, 'editMessageText').length, 0, 'KB9 у человека ничего не правится');
eq(sdk.rows('SELECT topic FROM people WHERE id = :p', { ':p': GLEB.id })[0].topic, 'bug', 'KB9 продолжение человека — туда, куда отнёс разработчик');
await button(cb(GLEB, 'zzz', { message_id: 1, chat: { id: GLEB.id, type: 'private' } }));
await button(cb(GLEB, 'c:bug:' + g2.message_id, { message_id: 1, chat: { id: 1, type: 'private' } }));
await button(cb(GLEB, 'c:bug', { message_id: 1, chat: { id: GLEB.id, type: 'private' } }));
await button(cb(ME, 'o:bug:' + gen2, { message_id: gen2, chat: { id: GROUP, type: 'supergroup' } }));
cs = calls();
eq(cs.map((c) => c.method), ['answerCallbackQuery', 'answerCallbackQuery', 'answerCallbackQuery', 'answerCallbackQuery'], 'KB10 мусор, чужой чат, «c:» без номера, «o:» с номером — только ответ на нажатие');
await handle(dm(GLEB, '🌐 Перевод'));
eq(calls().map((c) => c.params.text), [F.PROMPTS.translation], 'KB11 кнопка под полем ввода в любой момент меняет тему');

// ---- режим проверки разработчика
await handle(dm(ME, '/test'));
eq(calls().map((c) => c.params.text), [F.TEST_ON], 'T1 /test — режим проверки включён');
await handle(dm(ME, '/start'));
cs = calls();
ok(cs[0].params.text === F.GREETING && !!cs[0].params.reply_markup, 'T2 в режиме проверки /start — как у человека, с кнопками');
await handle(dm(ME, '💡 Предложение'));
eq(calls().map((c) => c.params.text), [F.PROMPTS.idea], 'T3 кнопка — как у человека');
await handle(dm(ME, 'проверка связи'));
s = sent(calls(), 'sendMessage');
eq([s[0].params.chat_id, s[0].params.message_thread_id], [GROUP, topic.idea], 'T4 сообщение без метки — в «Идеи», как у человека');
const myCopy = sdk.rows('SELECT msg FROM relays WHERE person = :p ORDER BY id DESC LIMIT 1', { ':p': OWNER })[0].msg;
await handle(dm(ME, 'ответ себе', { reply_to_message: { message_id: myCopy, from: BOT, chat: { id: OWNER, type: 'private' } } }));
eq(sent(calls(), 'copyMessage').length, 0, 'T5 ответ (reply) в личке — по-прежнему разработчика, не пересылается в группу');
NOW += F.TEST_FOR + 1;
await handle(dm(ME, 'после получаса'));
eq(calls().map((c) => c.params.text), [F.OWNER_HINT], 'T6 через полчаса режим гаснет сам');
await handle(dm(ME, '/test'));
await handle(dm(ME, '/test'));
eq(calls().map((c) => c.params.text), [F.TEST_ON, F.TEST_OFF], 'T7 повторный /test — выключить');

// ---- сводка скачиваний с GitHub (lib/stats, handlers/scheduled)
const S = await import('lib/stats');
const daily = (await import('handlers/scheduled')).default;
const SAVED_NOW = NOW;
const at = (d, h, m = 5) => Date.UTC(2026, 9, d, h - S.TZ, m) / 1000;   // d.10.2026 h:m по Алматы
eq([S.dayOf(at(3, 16)), S.hourOf(at(3, 16)), S.ddmm(at(3, 16))], ['2026-10-03', 16, '03.10'], 'A1 день и час — по Алматы');
eq(S.dayOf(at(4, 2)), '2026-10-04', 'A1 02:05 по Алматы — уже новый день, хотя по UTC ещё 03.10');
ok(S.due(at(3, 3), null), 'A2 самый первый снимок — в любой час');
ok(!S.due(at(3, 20), { day: '2026-10-03' }), 'A2 сегодня уже был — нет');
ok(!S.due(at(4, 8), { day: '2026-10-03' }), 'A2 новый день, но до 9:00 — рано');
ok(S.due(at(4, 9), { day: '2026-10-03' }), 'A2 новый день с 9:00 — пора');
const REL = (tag, date, n, x = {}) => ({ tag_name: tag, published_at: date, draft: false, prerelease: false,
  assets: [{ name: 'Falar.apk', download_count: n[0] }, { name: 'Falar-slim.apk', download_count: n[1] },
    { name: 'latest.json', download_count: n[2] }, { name: 'SHA256SUMS.txt', download_count: 7 }], ...x });
const list1 = [REL('v0.30.0', '2026-10-04T00:00:00Z', [9, 9, 9], { draft: true }),
  REL('v0.29.1-beta', '2026-10-03T12:00:00Z', [1, 0, 0], { prerelease: true }),
  REL('v0.29.0', '2026-10-03T08:00:00Z', [2, 0, 1]), REL('v0.28.0', '2026-10-02T07:00:00Z', [3, 1, 5]),
  { tag_name: 'v0.20.0', published_at: '2026-09-01T00:00:00Z', draft: false, assets: [] }];
const sn = S.snapshot(list1);
eq(Object.keys(sn.counts).length, 9, 'A3 три файла с трёх выпусков; черновик и SHA256SUMS не в счёте');
eq([sn.latest.tag, sn.since], ['v0.29.0', 'v0.28.0'], 'A3 последний — не предварительный; «всего с» — ранний выпуск с файлами');
eq(S.totals(sn.counts), { 'Falar.apk': 6, 'Falar-slim.apk': 1, 'latest.json': 6 }, 'A4 суммы по файлам');
eq(S.growth({ 'v1/Falar.apk': 5, 'v2/Falar.apk': 2, 'v1/latest.json': 3 }, { 'v1/Falar.apk': 3, 'v1/latest.json': 4 }),
  { 'Falar.apk': 4, 'Falar-slim.apk': 0, 'latest.json': 0 }, 'A5 прирост: новый выпуск — с нуля, перезалитый файл — не минус');
eq([S.period(24 * 3600), S.period(17 * 3600), S.period(49 * 3600), S.period(60)], ['за сутки', 'за 17 ч', 'за 2 сут.', 'за 1 ч'], 'A6 срок словами');
let txt = S.report(sn, null, at(3, 16));
eq(txt.split('\n').slice(0, 3), ['📊 GitHub · 03.10 · первая сводка', 'Всего с 0.28.0: APK 6, облегчённый 1, проверок обновлений 6',
  'Выпуск 0.29.0 от 03.10: APK 2, облегчённый 0'], 'A7 первая сводка: всего и последний выпуск');
ok(/каждый день около 09:00/.test(txt) && /примерно сколько копий/.test(txt), 'A7 когда дальше и как читать');
const list2 = [REL('v0.29.0', '2026-10-03T08:00:00Z', [5, 1, 4]), REL('v0.28.0', '2026-10-02T07:00:00Z', [3, 1, 9])];
txt = S.report(S.snapshot(list2), { at: at(3, 9), counts: sn.counts }, at(4, 9));
eq(txt.split('\n').slice(0, 3), ['📊 GitHub · 04.10 · за сутки', 'APK скачали: +3 (облегчённый: +1)',
  'Проверок обновлений: +7 — примерно столько копий Falar было в сети'], 'A8 сводка за сутки');
txt = S.report(S.snapshot(list2), { at: at(3, 16), counts: sn.counts }, at(4, 9));
eq([txt.split('\n')[0], txt.split('\n')[2]], ['📊 GitHub · 04.10 · за 17 ч', 'Проверок обновлений: +7'], 'A8 не сутки — срок словами, без «столько копий»');
eq(S.delayed(at(4, 12), { status: 403, limited: true }, false),
  '📊 Сводка за 04.10 задерживается: GitHub не отдал счётчики (HTTP 403, лимит запросов без токена). Пробую каждый час.\n' +
  'Чтобы не зависеть от лимита — токен GitHub только для чтения: bash bot/cf.sh github', 'A9 лимит без токена — почему и как помочь');
ok(!/cf\.sh/.test(S.delayed(at(4, 12), { status: 502 }, false)), 'A9 502 — без совета про токен');
ok(/токен GitHub не принят/.test(S.delayed(at(4, 12), { status: 401 }, true)), 'A9 401 — токен не принят');
eq(S.delayed(at(4, 12), {}, false).split('\n').length, 1, 'A9 нет ответа — одна строка');

// обработчик: GitHub — поддельный fetch, Telegram и база — заглушки
const fetch0 = globalThis.fetch, gh = [];
let ghList = list1, ghFail = 0;
globalThis.fetch = async (url, o) => {
  gh.push({ url, headers: o.headers });
  if (ghFail) return { status: ghFail, headers: { get: (h) => (h === 'x-ratelimit-remaining' ? '0' : null) }, json: async () => ({}) };
  return { status: 200, headers: { get: () => null }, json: async () => ghList };
};
const anKey = 'topic:' + GROUP + ':analytics';
const thread = () => Number(sdk.rows('SELECT value FROM settings WHERE key = :k', { ':k': anKey })[0].value);
calls();
await sdk.db.run('DELETE FROM settings WHERE key = :k', { ':k': anKey });
NOW = at(3, 16); await daily();
cs = calls();
eq(sent(cs, 'createForumTopic').map((c) => [c.params.chat_id, c.params.name]), [[GROUP, 'Аналитика']], 'A10 топика нет — бот создаёт «Аналитику» сам');
const an = thread();
s = sent(cs, 'sendMessage');
eq(s.map((c) => [c.params.chat_id, c.params.message_thread_id]), [[GROUP, an]], 'A10 первая сводка — в «Аналитику»');
ok(s[0].params.text.startsWith('📊 GitHub · 03.10 · первая сводка'), 'A10 текст первой сводки');
ok(gh.length === 1 && gh[0].url === 'https://api.github.com/repos/Annoyt/FALAR/releases?per_page=100&page=1' &&
  gh[0].headers['user-agent'] === 'falar-feedback-bot' && !('authorization' in gh[0].headers), 'A10 один запрос: адрес, user-agent, без токена');
eq(sdk.rows('SELECT day, at FROM stats'), [{ day: '2026-10-03', at: at(3, 16) }], 'A10 снимок дня — в базе');
gh.length = 0; NOW = at(3, 17); await daily();
eq([calls().length, gh.length], [0, 0], 'A11 в тот же день — ни GitHub, ни сообщений');
NOW = at(4, 8); await daily();
eq([calls().length, gh.length], [0, 0], 'A11 назавтра до 9:00 — рано');
ghList = list2; NOW = at(4, 9); await daily();
s = sent(calls(), 'sendMessage');
eq(s.map((c) => c.params.text.split('\n').slice(0, 2)), [['📊 GitHub · 04.10 · за 17 ч', 'APK скачали: +3 (облегчённый: +1)']], 'A12 назавтра в 9:05 — прирост с 16:05 вчера');
eq(sdk.rows('SELECT day FROM stats ORDER BY day').map((r) => r.day), ['2026-10-03', '2026-10-04'], 'A12 второй снимок');
ghFail = 403; NOW = at(5, 9); await daily();
eq([calls().length, sdk.rows('SELECT count(*) AS n FROM stats')[0].n], [0, 2], 'A13 GitHub отказал в 9:05 — молча и без снимка, попробует через час');
NOW = at(5, 12); await daily();
s = sent(calls(), 'sendMessage');
eq(s.map((c) => c.params.message_thread_id), [an], 'A13 к 12:00 цифр нет — одно сообщение в «Аналитику»');
ok(/лимит запросов без токена/.test(s[0].params.text) && /cf\.sh github/.test(s[0].params.text), 'A13 почему и как помочь');
NOW = at(5, 13); await daily();
eq(calls().length, 0, 'A13 второй раз за день не пишет');
ghFail = 0; gh.length = 0; NOW = at(5, 14); await daily({ token: 'gh-t' });
ok(sent(calls(), 'sendMessage').length === 1 && gh[0].headers.authorization === 'Bearer gh-t', 'A14 GitHub ожил — сводка в тот же день; токен — в authorization');
NOW = at(6, 9);
sdk.api.fail('sendMessage', 'Bad Request: message thread not found');
await daily();
cs = calls();
eq(cs.map((c) => c.method), ['sendMessage', 'createForumTopic', 'sendMessage'], 'A15 топик удалили — создать заново');
ok(thread() !== an && cs[2].params.message_thread_id === thread(), 'A15 сводка — в новый топик');
await sdk.db.run("DELETE FROM settings WHERE key = 'group'");
NOW = at(7, 9); await daily();
eq(sent(calls(), 'sendMessage').map((c) => [c.params.chat_id, c.params.message_thread_id ?? null]), [[OWNER, null]], 'A16 группы нет — разработчику в личку');
await sdk.db.run("INSERT INTO settings (key, value) VALUES ('group', :g)", { ':g': String(GROUP) });
owner.setOwner(0); gh.length = 0; NOW = at(8, 9); await daily();
eq([calls().length, gh.length], [0, 0], 'A17 бот не настроен — сводки нет');
owner.setOwner(OWNER);
sdk.api.fail('sendMessage', 'Forbidden: bot was kicked from the supergroup chat', 403);
await daily();
eq(calls().map((c) => [c.method, c.params.chat_id]), [['sendMessage', GROUP], ['sendMessage', OWNER]], 'A18 в группу не вышло (не топик) — в личку');
globalThis.fetch = fetch0; NOW = SAVED_NOW;

// ---- прослойка Cloudflare (cf/sdk.js): именованные параметры для D1 и Bot API через fetch
const cf = await import(pathToFileURL(path.join(ROOT, 'cf/sdk.js')).href);
eq(cf.positional('SELECT * FROM t WHERE a = :a AND b = :b OR a = :a', { ':a': 1, ':b': 'x' }),
   { sql: 'SELECT * FROM t WHERE a = ?1 AND b = ?2 OR a = ?1', values: [1, 'x'] }, 'CF1 :имя → ?N, повтор — тот же номер');
eq(cf.positional('UPDATE t SET v = :v WHERE k = :k', { ':k': 'a' }).values, [null, 'a'], 'CF1 нет значения — NULL, а не undefined');
eq(cf.positional('SELECT 1', {}), { sql: 'SELECT 1', values: [] }, 'CF1 без параметров');
ok(cf.api.then === undefined, 'CF2 api не «thenable»');
const realFetch = globalThis.fetch, seen = [];
globalThis.fetch = async (url, o) => { seen.push([url, JSON.parse(o.body)]);
  return { status: 200, json: async () => (url.endsWith('/getMe') ? { ok: true, result: { id: 9 } } : { ok: false, error_code: 403, description: 'Forbidden: bot was blocked by the user' }) }; };
cf.bind({ BOT_TOKEN: 'T', TG_API: 'http://t/' });
eq(await cf.api.getMe(), { id: 9 }, 'CF3 ответ без обёртки ok/result');
eq(seen[0][0], 'http://t/botT/getMe', 'CF3 адрес: база без лишней «/», токен, метод');
let err = null;
try { await cf.api.sendMessage({ chat_id: 1, text: 'x' }); } catch (e) { err = e; }
ok(err instanceof cf.BotApiError && err.code === 403 && /blocked/.test(err.description), 'CF4 ошибка Telegram — BotApiError с кодом и описанием');
globalThis.fetch = async () => ({ status: 502, json: async () => { throw new SyntaxError('html'); } });
err = null; try { await cf.api.getMe(); } catch (e) { err = e; }
ok(err instanceof cf.BotApiError && err.code === 502, 'CF4 не JSON (502 от прокси) — BotApiError с HTTP-кодом');
globalThis.fetch = realFetch;

console.log((fails ? 'ПРОВАЛОВ: ' + fails : 'Всё прошло') + ' (проверок: ' + checks + ')');
process.exit(fails ? 1 : 0);
