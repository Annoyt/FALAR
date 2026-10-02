// lib/feedback — что бот решает сам, без сети и базы: в какой топик сообщение, как его подписать,
// не слишком ли часто пишет человек, и все тексты бота.

// Топики группы разработчика. Метку ставит приложение первым словом сообщения (Feedback.java);
// «Задачи» — для своих задач разработчика, бот туда ничего не кладёт.
export const TOPICS = {
  translation: { name: 'Переводы', color: 7322096,  tag: '#перевод' },
  bug:         { name: 'Ошибки',   color: 16478047, tag: '#ошибка' },
  idea:        { name: 'Идеи',     color: 16766590, tag: '#идея' },
  tasks:       { name: 'Задачи',   color: 9367192,  tag: null },
};

const BY_TAG = {};
for (const [k, t] of Object.entries(TOPICS)) if (t.tag) BY_TAG[t.tag] = k;

// Сообщение без метки сразу после помеченного — продолжение того же письма.
export const FOLLOW_UP = 30 * 60;

/** Метка в начале текста: '#перевод' → 'translation'; нет метки — null. */
export function tagOf(text) {
  const m = /^\s*(#[\p{L}\p{N}_]+)/u.exec(text || '');
  return m ? BY_TAG[m[1].toLowerCase()] ?? null : null;
}

/** Топик для сообщения: по метке; без метки — топик предыдущего сообщения того же человека,
 *  если оно было недавно; иначе null — общий топик группы. */
export function route(text, person, now) {
  const t = tagOf(text);
  if (t) return t;
  if (person && person.topic && now - (person.last_at || 0) <= FOLLOW_UP) return person.topic;
  return null;
}

/** Имя человека и @username, если есть. */
export function displayName(from) {
  const name = [from.first_name, from.last_name].filter(Boolean).join(' ').trim() || 'без имени';
  return name + (from.username ? ' · @' + from.username : '');
}

/** Подпись над сообщением человека в группе. */
export function header(from) { return '👤 ' + displayName(from); }

// Не больше LIMIT сообщений за WINDOW секунд: имя бота будет на сайте и в приложении.
export const WINDOW = 10 * 60, LIMIT = 15;

/** Счёт сообщений человека в окне. warn — ровно первое лишнее: предупредить один раз, дальше молча. */
export function rate(person, now) {
  let windowAt = person ? person.window_at || 0 : 0, msgs = person ? person.msgs || 0 : 0;
  if (now - windowAt >= WINDOW) { windowAt = now; msgs = 0; }
  msgs++;
  return { windowAt, msgs, ok: msgs <= LIMIT, warn: msgs === LIMIT + 1 };
}

// Пределы Telegram: текст сообщения — 4096 знаков, подпись к медиа — 1024. Берём с запасом:
// String.length считает эмодзи за два.
export const TEXT_MAX = 4000, CAPTION_MAX = 1000;

// Медиа, у которых бывает подпись: туда заголовок помещается подписью, одним сообщением.
export const CAPTIONED = ['photo', 'video', 'document', 'audio', 'voice', 'animation'];

// «Передал» человеку — не чаще раза в ACK_EVERY, в остальное время — реакция на сообщение.
export const ACK_EVERY = 6 * 3600;

/** Команда в начале текста: '/block@falar_bot' → 'block'. */
export function command(text) {
  const m = /^\/([a-z_]+)(?:@\w+)?(?:\s|$)/i.exec(text || '');
  return m ? m[1].toLowerCase() : null;
}

export const GREETING =
  'Здравствуйте! Это бот обратной связи Falar — переводчика между русским и португальским.\n\n' +
  'Напишите, что не так или чего не хватает; можно приложить снимок экрана. ' +
  'Сообщение увидит только разработчик, ответ придёт сюда же.\n\n' +
  'Пришли из приложения, а поле ввода пустое? Текст уже скопирован: вставьте его ' +
  '(долгое нажатие → «Вставить») или нажмите в приложении «Telegram» ещё раз.';
export const ACK = 'Спасибо, передал разработчику. Ответ придёт сюда.';
export const SLOW = 'Слишком много сообщений подряд. Подождите несколько минут — потом можно продолжить.';
export const NOT_READY = 'Бот ещё настраивается, напишите чуть позже.';
export const OWNER_HELP =
  'Вы — разработчик. Сообщения людей приходят сюда, а после /setup в группе с топиками — в её топики.\n' +
  'Ответ человеку — ответом (reply) на его сообщение. /block или /unblock ответом — заблокировать или вернуть.\n' +
  'Ваше сообщение с меткой #перевод, #ошибка или #идея проходит как от человека — так проверяется приложение.\n' +
  '/id — ваш id.';
export const OWNER_HINT = 'Чтобы написать человеку, ответьте (reply) на его сообщение.';
export const SETUP_PRIVATE = 'Отправьте /setup в группе с топиками, куда добавлен бот.';
export const SETUP_NO_FORUM = 'В этой группе выключены топики: включите их в настройках группы и повторите /setup.';
