// handlers/callback_query — нажатие кнопки темы.
//   c:<тема>:<номер> — человек у себя в личке ответил «о чём это сообщение»;
//   o:<тема>         — разработчик на копии в общем топике группы.
// Копии сообщения переезжают в топик темы (копия туда, старая удаляется), и следующие полчаса
// сообщения этого человека идут туда же.

import { api } from 'sdk';
import { OWNER } from 'lib/owner';
import * as F from 'lib/feedback';
import * as store from 'lib/store';

export default async function (q) {
  const now = Math.floor(Date.now() / 1000);
  const d = F.parseData(q && q.data), msg = q && q.message;
  if (!d || !msg) return answer(q, '');
  let person, pm;
  if (d.who === 'c') {
    if (msg.chat.type !== 'private' || q.from.id !== msg.chat.id) return answer(q, '');
    person = q.from.id; pm = d.pm;
  } else {
    if (!OWNER || q.from.id !== OWNER) return answer(q, 'Раскладывает только разработчик');
    const r = await store.findRelay(msg.chat.id, msg.message_id);
    if (!r) return answer(q, 'Не знаю, чьё это сообщение');
    person = r.person; pm = r.person_msg;
  }
  const moved = await move(person, pm, d.key);
  const p = await store.person(person);
  if (p) await store.savePerson({ ...p, topic: d.key, last_at: now });   // продолжение — туда же
  const name = F.TOPICS[d.key].name;
  await answer(q, moved ? 'Перенесено в «' + name + '»' : 'Тема «' + name + '» запомнена');
  if (d.who === 'c') {                                  // кнопки под ответом человеку больше не нужны
    try {
      await api.editMessageText({ chat_id: msg.chat.id, message_id: msg.message_id, text: F.sortedText(d.key), reply_markup: { inline_keyboard: [] } });
    } catch (e) { console.warn('edit', e && e.description); }
  }
}

/** Копии сообщения человека в группе — в топик темы. Удалить старую копию можно только 48 часов:
 *  не вышло — с неё хотя бы снимаются кнопки. Сколько копий переехало. */
async function move(person, pm, key) {
  const group = await store.setting('group');
  const thread = group ? await store.setting('topic:' + group + ':' + key) : null;
  if (!thread) return 0;
  let n = 0;
  for (const r of await store.relaysOf(person, pm)) {
    if (String(r.chat) !== String(group)) continue;      // копия в личке разработчика — не трогаем
    try {
      const c = await api.copyMessage({ chat_id: Number(group), message_thread_id: Number(thread), from_chat_id: r.chat, message_id: r.msg });
      await store.moveRelay(r.id, Number(group), c.message_id);
      n++;
    } catch (e) { console.error('move', e && e.description); continue; }
    try { await api.deleteMessage({ chat_id: r.chat, message_id: r.msg }); }
    catch (e) {
      try { await api.editMessageReplyMarkup({ chat_id: r.chat, message_id: r.msg, reply_markup: { inline_keyboard: [] } }); }
      catch (e2) { console.warn('unbutton', e2 && e2.description); }
    }
  }
  return n;
}

async function answer(q, text) {
  if (!q || !q.id) return;
  try { await api.answerCallbackQuery({ callback_query_id: q.id, text: text || undefined }); }
  catch (e) { console.warn('answer', e && e.description); }
}
