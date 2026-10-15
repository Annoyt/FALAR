// lib/topics — сообщение бота в топик группы разработчика по ключу темы (F.TOPICS: «Аналитика»,
// «Телеметрия»). Топика нет — бот создаёт его; удалён — забывает и создаёт заново; группы нет или туда
// не вышло — разработчику в личку.

import { api } from 'sdk';
import { OWNER } from 'lib/owner';
import * as F from 'lib/feedback';
import * as store from 'lib/store';

/** send(to) зовёт Bot API с адресом to = {chat_id, message_thread_id?} и возвращает его сообщение.
 *  Итог: {chat, msg, place: 'topic' | 'owner', m — сообщение целиком} или null — не ушло никуда. */
export async function toTopic(key, send) {
  const group = await store.setting('group');
  if (group) {
    const k = 'topic:' + group + ':' + key, t = F.TOPICS[key];
    for (let attempt = 0; attempt < 2; attempt++) {
      let thread = await store.setting(k);
      try {
        if (!thread) {
          thread = (await api.createForumTopic({ chat_id: Number(group), name: t.name, icon_color: t.color })).message_thread_id;
          await store.setSetting(k, thread);
        }
        const m = await send({ chat_id: Number(group), message_thread_id: Number(thread) });
        return { chat: Number(group), msg: m && m.message_id, place: 'topic', m };
      } catch (e) {
        console.error('топик ' + key, e && e.description);
        if (!(thread && /thread|topic/i.test((e && e.description) || ''))) break;
        await store.setSetting(k, null);
      }
    }
  }
  try {
    const m = await send({ chat_id: OWNER });
    return { chat: OWNER, msg: m && m.message_id, place: 'owner', m };
  } catch (e) { console.error('топик ' + key + ': личка', e && e.description); return null; }
}

/** Текст в топик. true — ушло. */
export async function textTo(key, text) {
  return !!(await toTopic(key, (to) => api.sendMessage({ ...to, text, link_preview_options: { is_disabled: true } })));
}
