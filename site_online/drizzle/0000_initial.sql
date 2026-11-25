CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  phone TEXT NOT NULL DEFAULT '',
  address TEXT NOT NULL DEFAULT '',
  salt TEXT NOT NULL CHECK(length(salt)=32),
  password_hash TEXT NOT NULL CHECK(length(password_hash)=64),
  rounds INTEGER NOT NULL CHECK(rounds=100000),
  admin INTEGER NOT NULL DEFAULT 0 CHECK(admin IN (0,1)),
  admin_owner_id TEXT NOT NULL DEFAULT '',
  failed INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0,
  created TEXT NOT NULL,
  CHECK(admin=0 OR length(admin_owner_id)>0)
);
--> statement-breakpoint
CREATE UNIQUE INDEX one_site_admin ON users(admin) WHERE admin=1;
--> statement-breakpoint
CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  owner TEXT NOT NULL,
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
  csrf TEXT NOT NULL,
  expires INTEGER NOT NULL
);
--> statement-breakpoint
CREATE INDEX sessions_user ON sessions(user_id);
--> statement-breakpoint
CREATE INDEX sessions_expiry ON sessions(expires);
--> statement-breakpoint
CREATE TABLE necklace (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL CHECK(stone_id BETWEEN 1 AND 12),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 99),
  PRIMARY KEY(owner,stone_id)
);
--> statement-breakpoint
CREATE TABLE favorites (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL CHECK(stone_id BETWEEN 1 AND 12),
  PRIMARY KEY(owner,stone_id)
);
--> statement-breakpoint
CREATE TABLE collections (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner TEXT NOT NULL,
  name TEXT NOT NULL,
  updated TEXT NOT NULL,
  snapshot TEXT NOT NULL
);
--> statement-breakpoint
CREATE INDEX collections_owner ON collections(owner);
--> statement-breakpoint
CREATE TABLE orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id),
  status TEXT NOT NULL CHECK(status IN ('new','accepted','preparing','ready','cancelled')),
  created TEXT NOT NULL,
  delivery TEXT NOT NULL CHECK(delivery IN ('pickup','delivery')),
  pickup_date TEXT NOT NULL,
  phone TEXT NOT NULL,
  address TEXT NOT NULL,
  note TEXT NOT NULL,
  gift INTEGER NOT NULL CHECK(gift IN (0,1)),
  snapshot TEXT NOT NULL
);
--> statement-breakpoint
CREATE INDEX orders_user ON orders(user_id);
--> statement-breakpoint
CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT NOT NULL);
--> statement-breakpoint
CREATE TABLE reviews (
  user_id INTEGER NOT NULL REFERENCES users(id),
  stone_id INTEGER NOT NULL CHECK(stone_id BETWEEN 1 AND 12),
  rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5),
  text TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY(user_id,stone_id)
);
--> statement-breakpoint
CREATE TABLE messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id),
  subject TEXT NOT NULL,
  text TEXT NOT NULL,
  reply TEXT NOT NULL DEFAULT '',
  created TEXT NOT NULL
);
--> statement-breakpoint
CREATE INDEX messages_user ON messages(user_id);
--> statement-breakpoint
CREATE TABLE runtime (id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL);
--> statement-breakpoint
CREATE TABLE mutation_guards (id TEXT PRIMARY KEY);
