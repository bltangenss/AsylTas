// Minimal D1-compatible adapter for integration tests; no production imports.
import {DatabaseSync} from 'node:sqlite';

class Prepared {
  constructor(database, sql, args = []) { this.database = database; this.sql = sql; this.args = args; }
  bind(...args) { return new Prepared(this.database, this.sql, args); }
  async first(column) {
    const row = this.database.sqlite.prepare(this.sql).get(...this.args);
    return row ? (column ? row[column] : {...row}) : null;
  }
  async all() { return this.database.execute(this); }
  async run() { return this.database.execute(this); }
}

export class D1 {
  constructor(path) {
    this.sqlite = new DatabaseSync(path);
    this.sqlite.exec('PRAGMA foreign_keys=ON');
    this.trace = [];
    this.failMutationAt = null;
    this.failSeedAt = null;
    this.beforeNextMutation = null;
  }
  prepare(sql) { this.trace.push(sql); return new Prepared(this, sql); }
  withSession(constraint) {
    if (constraint !== 'first-primary') throw new Error('Unsupported test consistency mode');
    return this;
  }
  execute(statement) {
    const prepared = this.sqlite.prepare(statement.sql);
    const results = prepared.all(...statement.args).map(row => ({...row}));
    const changed = this.sqlite.prepare('SELECT changes() AS changes,last_insert_rowid() AS last_row_id').get();
    return {success:true,results,meta:{changes:Number(changed.changes),last_row_id:Number(changed.last_row_id)}};
  }
  async batch(statements) {
    const mutation = statements[0]?.sql.startsWith('INSERT INTO mutation_guards');
    if (mutation && this.beforeNextMutation) {
      const hook = this.beforeNextMutation;
      this.beforeNextMutation = null;
      await hook();
    }
    const seed = statements.some(item => item.sql.startsWith('INSERT INTO catalog_data'));
    const failureIndex = mutation ? this.failMutationAt : seed ? this.failSeedAt : null;
    if (mutation) this.failMutationAt = null;
    if (seed) this.failSeedAt = null;
    this.sqlite.exec('BEGIN IMMEDIATE');
    try {
      const results = statements.map((item, index) => {
        if (index === failureIndex) throw new Error('Injected SQLite failure: internal-secret-must-not-leak');
        return this.execute(item);
      });
      this.sqlite.exec('COMMIT');
      return results;
    } catch (error) { this.sqlite.exec('ROLLBACK'); throw error; }
  }
  close() { this.sqlite.close(); }
}
