// handlers/telemetry — приём данных приложения, присланных по согласию человека (cf/worker.js: POST /t и
// POST /audio). Всё ложится в топик «Телеметрия» группы разработчика (топик бот создаёт сам) и в базу.
//
//   /t      пакет статистики раз в сутки: устройство, счётчики распознавания, сбои — без звука и текстов;
//   /audio  звук одной реплики по кнопке «Отправить звук разработчику» с текстом и согласием; в ответ —
//           номер записи: по нему человек может попросить удалить её, разработчик удаляет командой /del.

import { api } from 'sdk';
import { OWNER } from 'lib/owner';
import * as S from 'lib/stats';
import * as T from 'lib/telemetry';
import * as store from 'lib/store';
import { toTopic, textTo } from 'lib/topics';

const reply = (status, body) => ({ status, body });

/** Пакет статистики. Пакет принят — 200, даже если сверх суточного предела в Telegram он уже не ушёл. */
export async function packet(raw) {
  if (!OWNER) return reply(503, { ok: false, error: 'бот не настроен' });
  let p;
  try { p = T.packet(raw); } catch (e) { return reply(400, { ok: false, error: e.message }); }
  const now = Math.floor(Date.now() / 1000), day = S.dayOf(now);
  await store.saveTelemetry(day, now, JSON.stringify(p));
  if ((await store.telemetryCount(day)) <= T.POSTS_PER_DAY) await textTo('data', T.packetText(p));
  return reply(200, { ok: true });
}

/** Звук реплики. Номер записи — в ответе; не дошёл до Telegram — записи нет, приложение скажет «не вышло». */
export async function audio(raw) {
  if (!OWNER) return reply(503, { ok: false, error: 'бот не настроен' });
  let a;
  try { a = T.audio(raw); } catch (e) { return reply(400, { ok: false, error: e.message }); }
  const now = Math.floor(Date.now() / 1000), day = S.dayOf(now);
  if ((await store.donationCount(day)) >= T.AUDIO_PER_DAY) return reply(429, { ok: false, error: 'на сегодня хватит' });
  let bytes;
  try { bytes = Uint8Array.from(atob(a.b64), (c) => c.charCodeAt(0)); } catch (e) { return reply(400, { ok: false, error: 'звук — не base64' }); }
  const n = await store.newDonation(day, now, a);
  const file = new File([bytes], 'falar-' + n + a.ext, { type: a.mime }), caption = T.audioCaption(a, n);
  const sent = await toTopic('data', (to) => (a.mime === 'audio/ogg'
    ? api.sendVoice({ ...to, voice: file, caption })
    : api.sendDocument({ ...to, document: file, caption })));
  if (!sent) { await store.dropDonation(n); return reply(502, { ok: false, error: 'не дошло до Telegram' }); }
  const f = sent.m && (sent.m.voice || sent.m.document || sent.m.audio);
  await store.placeDonation(n, sent.chat, sent.msg, f ? f.file_id : null);
  return reply(200, { ok: true, n });
}
