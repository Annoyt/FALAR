// handlers/message — каждое сообщение боту.
//
// Человек пишет боту в личку → бот кладёт сообщение разработчику: в закрытую группу с топиками
// (после /setup в ней), а пока группы нет — разработчику в личку. Разработчик отвечает (reply) на
// эту копию → бот отправляет ответ человеку. Люди друг друга не видят.

import { api } from 'sdk';
import { OWNER } from 'lib/owner';
import * as F from 'lib/feedback';
import * as store from 'lib/store';

export default async function (m) {
  if (!m || !m.from || m.from.is_bot) return;
  const now = Math.floor(Date.now() / 1000);
  const text = m.text ?? m.caption ?? '';
  const priv = m.chat.type === 'private';
  if (priv && F.command(text) === 'id') return say(m.chat.id, 'Ваш id: ' + m.from.id);
  if (!OWNER) { if (priv) await say(m.chat.id, F.NOT_READY); return; }
  const owner = m.from.id === OWNER;
  if (!priv) { if (owner) await fromOwner(m, text); return; }            // в группе слушаем только разработчика
  // Разработчик в личке: ответы и команды. Помеченное (#перевод…) — как от человека: проверка приложения.
  // В режиме проверки (/test, полчаса) — всё, кроме ответов и /test, как от человека: /start, кнопки.
  if (owner) {
    const cmd = F.command(text);
    if (cmd === 'test') return testMode(m, now);
    const testing = Number((await store.setting('owner_test_until')) || 0) > now;
    if (m.reply_to_message || (!testing && (cmd || !F.tagOf(text)))) return fromOwner(m, text);
  }
  return fromPerson(m, text, now);
}

async function fromPerson(m, text, now) {
  const p = await store.person(m.from.id);
  if (p && p.blocked) return;
  const cmd = F.command(text);
  if (cmd === 'start' || cmd === 'help') return say(m.chat.id, F.GREETING, undefined, undefined, F.KEYBOARD);
  const r = F.rate(p, now);
  const next = {
    id: m.from.id, name: F.displayName(m.from), topic: p ? p.topic : null, last_at: p ? p.last_at : 0,
    window_at: r.windowAt, msgs: r.msgs, ack_at: p ? p.ack_at : 0, blocked: 0,
  };
  if (!r.ok) {                                         // счёт сохраняется и тогда: поток так и остаётся ограничен
    await store.savePerson(next);
    if (r.warn) await say(m.chat.id, F.SLOW);
    return;
  }
  const choice = F.choiceOf(text);
  if (choice) {                                        // кнопка под полем ввода: тема выбрана заранее
    next.topic = choice; next.last_at = now;
    await store.savePerson(next);
    return say(m.chat.id, F.PROMPTS[choice]);
  }
  const topic = F.route(text, p, now);
  const sent = await deliver(m, topic, F.header(m.from));
  if (sent) for (const id of sent.ids) await store.relay(sent.chat, id, m.chat.id, m.message_id, now);
  next.topic = topic; next.last_at = now;
  // Легло в общий топик — спросить, о чём это: кнопка перенесёт копию сама. Иначе «передал» не чаще
  // раза в ACK_EVERY, в остальное время — реакция.
  const ask = sent && sent.place === 'general';
  const ack = ask || !p || now - (p.ack_at || 0) >= F.ACK_EVERY;
  if (ack) next.ack_at = now;
  await store.savePerson(next);
  if (!sent) return say(m.chat.id, 'Не получилось передать сообщение, попробуйте позже.');
  if (ask) await say(m.chat.id, F.ASK, undefined, undefined, F.askMarkup(m.message_id));
  else if (ack) await say(m.chat.id, F.ACK);
  else await react(m.chat.id, m.message_id, '👍');
}

/** Куда положить: топик группы → общий топик группы → личка разработчика. Удалённый топик
 *  забывается, и следующий /setup создаст его заново. В общем топике на копии — кнопки
 *  разработчику «→ Ошибки / Идеи / Переводы». */
async function deliver(m, topic, head) {
  const group = await store.setting('group');
  const targets = [];
  if (group) {
    const thread = topic ? await store.setting('topic:' + group + ':' + topic) : null;
    if (thread) targets.push({ chat_id: Number(group), message_thread_id: Number(thread), key: 'topic:' + group + ':' + topic, place: 'topic' });
    targets.push({ chat_id: Number(group), place: 'general' });
  }
  targets.push({ chat_id: OWNER, place: 'owner' });
  for (const t of targets) {
    try {
      const markup = t.place === 'general' ? F.ownerMarkup() : undefined;
      return { chat: t.chat_id, place: t.place, ids: await copyTo(m, head, { chat_id: t.chat_id, message_thread_id: t.message_thread_id }, markup) };
    } catch (e) {
      console.error('deliver', t.chat_id, t.message_thread_id, e && e.description);
      if (t.key && /thread|topic/i.test((e && e.description) || '')) await store.setSetting(t.key, null);
    }
  }
  return null;
}

/** Копия сообщения человека с подписью — одним сообщением, где можно, иначе подпись и копия.
 *  Кнопки (markup) — на первом из них. */
async function copyTo(m, head, to, markup) {
  if (to.message_thread_id === undefined) delete to.message_thread_id;
  const mk = markup ? { reply_markup: markup } : {};
  if (m.text != null) {
    const full = head + '\n\n' + m.text;
    if (full.length <= F.TEXT_MAX) return [(await api.sendMessage({ ...to, ...mk, text: full, link_preview_options: { is_disabled: true } })).message_id];
  } else if (F.CAPTIONED.some((k) => m[k])) {
    const cap = head + (m.caption ? '\n\n' + m.caption : '');
    if (cap.length <= F.CAPTION_MAX) return [(await api.copyMessage({ ...to, ...mk, from_chat_id: m.chat.id, message_id: m.message_id, caption: cap })).message_id];
  }
  const a = await api.sendMessage({ ...to, ...mk, text: head });
  const b = await api.copyMessage({ ...to, from_chat_id: m.chat.id, message_id: m.message_id });
  return [a.message_id, b.message_id];
}

async function fromOwner(m, text) {
  const cmd = F.command(text);
  const thread = m.is_topic_message ? m.message_thread_id : undefined;
  if (cmd === 'setup') return setup(m);
  if (cmd === 'start' || cmd === 'help') return say(m.chat.id, F.OWNER_HELP, thread);
  if (cmd === 'id') return say(m.chat.id, 'Ваш id: ' + m.from.id + (m.chat.type === 'private' ? '' : ', группа: ' + m.chat.id), thread);
  // В топике каждое сообщение формально — ответ на его заглавное: это не ответ человеку.
  const re = m.reply_to_message && !m.reply_to_message.forum_topic_created ? m.reply_to_message : null;
  if (!re) { if (m.chat.type === 'private') await say(m.chat.id, F.OWNER_HINT); return; }   // в группе свои заметки — молча
  const r = await store.findRelay(m.chat.id, re.message_id);
  if (!r) { if (re.from && re.from.is_bot) await say(m.chat.id, 'Не знаю, чьё это сообщение: ответьте на сообщение человека.', thread, m.message_id); return; }
  if (cmd === 'block' || cmd === 'unblock') {
    await store.setBlocked(r.person, cmd === 'block');
    return react(m.chat.id, m.message_id, '👌');
  }
  try {
    await api.copyMessage({ chat_id: r.person, from_chat_id: m.chat.id, message_id: m.message_id,
      reply_parameters: { message_id: r.person_msg, allow_sending_without_reply: true } });
    await react(m.chat.id, m.message_id, '👌');
  } catch (e) {
    await say(m.chat.id, 'Не доставлено: ' + ((e && e.description) || e), thread, m.message_id);
  }
}

/** /test — полчаса бот видит разработчика обычным человеком; повтор — выключить. */
async function testMode(m, now) {
  const on = Number((await store.setting('owner_test_until')) || 0) > now;
  await store.setSetting('owner_test_until', on ? null : now + F.TEST_FOR);
  return say(m.chat.id, on ? F.TEST_OFF : F.TEST_ON);
}

/** /setup в группе с топиками: группа запоминается, недостающие топики создаются. Повтор — безопасен. */
async function setup(m) {
  if (m.chat.type === 'private') return say(m.chat.id, F.SETUP_PRIVATE);
  if (!m.chat.is_forum) return say(m.chat.id, F.SETUP_NO_FORUM);
  const g = m.chat.id;
  await store.setSetting('group', g);
  const names = [];
  for (const [key, t] of Object.entries(F.TOPICS)) {
    if (!(await store.setting('topic:' + g + ':' + key))) {
      try {
        const ft = await api.createForumTopic({ chat_id: g, name: t.name, icon_color: t.color });
        await store.setSetting('topic:' + g + ':' + key, ft.message_thread_id);
      } catch (e) {
        return say(g, 'Не смог создать топик «' + t.name + '»: ' + ((e && e.description) || e) +
          '. Сделайте бота администратором с правом «Управление темами» и повторите /setup.');
      }
    }
    names.push('«' + t.name + '»');
  }
  return say(g, 'Готово. Топики: ' + names.join(', ') + '. Сообщения без метки — в общий топик. ' +
    'Ответ человеку — ответом (reply) на его сообщение.');
}

async function say(chat_id, text, thread, replyTo, markup) {
  const p = { chat_id, text, link_preview_options: { is_disabled: true } };
  if (markup) p.reply_markup = markup;
  if (thread) p.message_thread_id = thread;
  if (replyTo) p.reply_parameters = { message_id: replyTo, allow_sending_without_reply: true };
  try { return await api.sendMessage(p); } catch (e) { console.error('say', chat_id, e && e.description); }
}

async function react(chat_id, message_id, emoji) {
  try { await api.setMessageReaction({ chat_id, message_id, reaction: [{ type: 'emoji', emoji }] }); }
  catch (e) { console.warn('react', e && e.description); }
}
