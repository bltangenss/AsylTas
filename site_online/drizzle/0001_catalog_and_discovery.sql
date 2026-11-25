-- Additive v4 schema. Legacy tables remain intact. Existing rows are copied
-- exactly once by the API's guarded initialization transaction after deployment.
CREATE TABLE catalog_data (
  id INTEGER PRIMARY KEY CHECK(id BETWEEN 1 AND 48),
  name TEXT NOT NULL,
  category TEXT NOT NULL,
  carats TEXT NOT NULL,
  price_per_carat TEXT NOT NULL,
  transparency INTEGER NOT NULL CHECK(transparency BETWEEN 0 AND 100),
  record TEXT NOT NULL
);
--> statement-breakpoint
CREATE INDEX catalog_category ON catalog_data(category);
--> statement-breakpoint
CREATE TABLE app_versions (key TEXT PRIMARY KEY,value TEXT NOT NULL);
--> statement-breakpoint
CREATE TABLE necklace_v4 (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL REFERENCES catalog_data(id),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 99),
  PRIMARY KEY(owner,stone_id)
);
--> statement-breakpoint
CREATE TABLE favorites_v4 (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL REFERENCES catalog_data(id),
  PRIMARY KEY(owner,stone_id)
);
--> statement-breakpoint
CREATE TABLE reviews_v4 (
  user_id INTEGER NOT NULL REFERENCES users(id),
  stone_id INTEGER NOT NULL REFERENCES catalog_data(id),
  rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5),
  text TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY(user_id,stone_id)
);
--> statement-breakpoint
CREATE TABLE stone_comparisons (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL REFERENCES catalog_data(id),
  ordinal INTEGER NOT NULL,
  PRIMARY KEY(owner,stone_id)
);
--> statement-breakpoint
CREATE TABLE stone_history (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL REFERENCES catalog_data(id),
  viewed TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  PRIMARY KEY(owner,stone_id)
);
--> statement-breakpoint
CREATE INDEX history_owner_order ON stone_history(owner,ordinal DESC);
--> statement-breakpoint
CREATE TABLE stone_notes (
  owner TEXT NOT NULL,
  stone_id INTEGER NOT NULL REFERENCES catalog_data(id),
  text TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY(owner,stone_id)
);
