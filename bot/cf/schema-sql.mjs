// SQL таблиц бота из schema.js — для `wrangler d1 execute --file` (bot/cf.sh deploy).
//   node cf/schema-sql.mjs > .cf-schema.sql
import { registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
registerHooks({
  resolve(spec, ctx, next) {
    if (spec === 'sdk/db') return { url: pathToFileURL(path.join(ROOT, 'cf/dsl.mjs')).href, shortCircuit: true };
    if (spec === 'schema') return { url: pathToFileURL(path.join(ROOT, 'schema.js')).href, shortCircuit: true };
    return next(spec, ctx);
  },
});
const { ddl } = await import(pathToFileURL(path.join(ROOT, 'cf/dsl.mjs')).href);
console.log(ddl(await import('schema')).join('\n'));
