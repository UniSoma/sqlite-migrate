(ns sqlite-migrate.corpus
  "The curated nasty-schema corpus (ticket sqm-01kzcv5g8zb3): quoted and
  keyword identifiers, generated columns, named and unnamed constraints,
  deferrable foreign keys, column-level and table-level FOREIGN KEY
  clauses (named, multiple per table, trailing CHECK after REFERENCES),
  partial and expression indexes, STRICT and
  WITHOUT ROWID tables, views, triggers (including on views), and a
  virtual table. Statements are spelled the way SQLite stores them
  (leading keywords uppercase) so stored CREATE sql equals the source
  text. Later slices reuse these as deterministic regression seeds.")

(def nasty-declaration
  ["CREATE TABLE \"order\" (id INTEGER PRIMARY KEY AUTOINCREMENT, \"group\" TEXT NOT NULL COLLATE NOCASE DEFAULT 'none', total REAL DEFAULT (1.0 + 2.0), CONSTRAINT total_positive CHECK (total > 0))"
   "CREATE TABLE items (sku TEXT, qty INT NOT NULL DEFAULT -1, price ANY, subtotal REAL GENERATED ALWAYS AS (qty * price) VIRTUAL, big REAL GENERATED ALWAYS AS (qty * 100) STORED, order_id INTEGER CONSTRAINT fk_order REFERENCES \"order\"(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED, CONSTRAINT sku_pk PRIMARY KEY (sku), UNIQUE (sku, qty), CHECK (qty <> 0)) STRICT, WITHOUT ROWID"
   "CREATE INDEX idx_items_qty ON items (qty DESC) WHERE qty > 0"
   "CREATE INDEX idx_order_expr ON \"order\" ((total * 2), \"group\" COLLATE RTRIM)"
   "CREATE UNIQUE INDEX idx_order_group ON \"order\" (\"group\")"
   "CREATE VIEW v_totals AS SELECT id, total FROM \"order\" WHERE total > 0"
   "CREATE TRIGGER trg_order_touch AFTER UPDATE ON \"order\" BEGIN UPDATE \"order\" SET total = total WHERE id = NEW.id; END"
   "CREATE TRIGGER trg_view_insert INSTEAD OF INSERT ON v_totals BEGIN INSERT INTO \"order\" (total) VALUES (NEW.total); END"
   "CREATE VIRTUAL TABLE notes USING fts5(body, tokenize = 'porter')"
   "CREATE TABLE shipments (id INTEGER PRIMARY KEY, order_id INTEGER, item_sku TEXT, note_ref INTEGER REFERENCES \"order\"(id) ON DELETE SET NULL CHECK (note_ref <> 0), CONSTRAINT fk_ship_order FOREIGN KEY (order_id) REFERENCES \"order\"(id) ON UPDATE CASCADE, FOREIGN KEY (item_sku) REFERENCES items(sku) DEFERRABLE INITIALLY DEFERRED)"])
