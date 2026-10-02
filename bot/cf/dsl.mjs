// Ровно столько DSL schema.js (table/integer/text/index), сколько нужно, чтобы построить по нему SQL:
// таблицы для D1 (cf/schema-sql.mjs) и для проверок на столе (test/mock-sdk.mjs) — из одного описания.

function column(type) {
  return (name) => {
    const c = { name, type, pk: false, ai: false, nn: false, uq: false, def: undefined };
    const b = {
      _c: c,
      primaryKey(o) { c.pk = true; c.ai = !!(o && o.autoIncrement); return b; },
      notNull() { c.nn = true; return b; },
      unique() { c.uq = true; return b; },
      default(v) { c.def = v; return b; },
    };
    return b;
  };
}
export const integer = column('INTEGER');
export const text = column('TEXT');
export function index(name) { return { on: (...cols) => ({ name, cols }) }; }
export function table(name, cols, extra) { return { _table: name, cols, extra }; }

/** CREATE TABLE/INDEX IF NOT EXISTS для всех таблиц модуля схемы. Повтор безопасен. */
export function ddl(schema) {
  const out = [];
  for (const t of Object.values(schema)) {
    if (!t || !t._table) continue;
    const cols = Object.entries(t.cols).map(([key, b]) => {
      const c = b._c, name = c.name || key;
      let d = name + ' ' + c.type;
      if (c.pk) d += ' PRIMARY KEY' + (c.ai ? ' AUTOINCREMENT' : '');
      if (c.nn) d += ' NOT NULL';
      if (c.uq) d += ' UNIQUE';
      if (c.def !== undefined) d += ' DEFAULT ' + (typeof c.def === 'string' ? "'" + c.def.replace(/'/g, "''") + "'" : c.def);
      return d;
    });
    out.push('CREATE TABLE IF NOT EXISTS ' + t._table + ' (' + cols.join(', ') + ');');
    if (t.extra) {
      for (const ix of Object.values(t.extra(t.cols))) {
        out.push('CREATE INDEX IF NOT EXISTS ' + ix.name + ' ON ' + t._table + ' (' + ix.cols.map((b) => b._c.name).join(', ') + ');');
      }
    }
  }
  return out;
}
