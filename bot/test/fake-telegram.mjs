// Поддельный Bot API для test/e2e.sh: записывает вызовы и отвечает так, как отвечает Telegram.
//   node test/fake-telegram.mjs 8790
//   GET /calls — все вызовы [{token, method, params}]; POST /reset — забыть их;
//   POST /fail/<method>/<код> + тело-описание — следующий такой вызов вернёт ошибку.
// Вызов с файлом (multipart: голос, документ) записывается так же: поля — строками, файл — {file, type, size}.
// Заодно — API GitHub для сводки скачиваний: GET /repos/<владелец>/<репо>/releases отдаёт список,
//   заданный POST /gh/releases (тело — JSON); GET /gh/calls — запросы [{url, ua, auth}].
import http from 'node:http';

const calls = [], fails = [], ghCalls = [];
let ghReleases = [];
let id = 500;
http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const buf = Buffer.concat(chunks), body = buf.toString();
  res.setHeader('content-type', 'application/json');
  if (req.method === 'GET' && req.url === '/calls') return res.end(JSON.stringify(calls));
  if (req.method === 'POST' && req.url === '/reset') { calls.length = 0; fails.length = 0; return res.end('{}'); }
  if (req.method === 'POST' && req.url === '/gh/releases') { ghReleases = JSON.parse(body); return res.end('{}'); }
  if (req.method === 'GET' && req.url === '/gh/calls') return res.end(JSON.stringify(ghCalls));
  if (req.method === 'GET' && req.url.startsWith('/repos/')) {
    ghCalls.push({ url: req.url, ua: req.headers['user-agent'] || null, auth: req.headers.authorization || null });
    return res.end(JSON.stringify(/^\/repos\/[^/]+\/[^/]+\/releases\?/.test(req.url) ? ghReleases : []));
  }
  let m = /^\/fail\/(\w+)\/(\d+)$/.exec(req.url);
  if (req.method === 'POST' && m) { fails.push({ method: m[1], code: Number(m[2]), description: body }); return res.end('{}'); }
  m = /^\/bot([^/]*)\/(\w+)$/.exec(req.url);
  if (!m) { res.statusCode = 404; return res.end(JSON.stringify({ ok: false, error_code: 404, description: 'Not Found' })); }
  let params = {};
  const ct = req.headers['content-type'] || '';
  if (ct.startsWith('multipart/form-data')) {
    for (const [k, v] of await new Response(buf, { headers: { 'content-type': ct } }).formData()) {
      params[k] = typeof v === 'string' ? v : { file: v.name, type: v.type, size: v.size };
    }
  } else if (body) params = JSON.parse(body);
  calls.push({ token: m[1], method: m[2], params });
  const f = fails.findIndex((x) => x.method === m[2]);
  if (f >= 0) {
    const x = fails.splice(f, 1)[0];
    res.statusCode = x.code;
    return res.end(JSON.stringify({ ok: false, error_code: x.code, description: x.description }));
  }
  let result = { message_id: id++, chat: { id: params.chat_id }, date: 0 };
  if (m[2] === 'createForumTopic') result = { message_thread_id: id++, name: params.name, icon_color: params.icon_color };
  if (m[2] === 'setMessageReaction' || m[2] === 'deleteMessage') result = true;
  if (m[2] === 'sendVoice') result.voice = { file_id: 'fv' + result.message_id };
  res.end(JSON.stringify({ ok: true, result }));
}).listen(Number(process.argv[2] || 8790), '127.0.0.1');
