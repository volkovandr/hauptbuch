-- V6 — the tag dimension (data-model §10, plan stage 7e).
--
-- Tags are an orthogonal, overlapping label lens on postings (per-car / per-trip slicing like
-- `Car:Audi`, `Trip:Prague`), distinct from categories (which are accounts, one per posting). A tag
-- is a real entity, not a bare string (data-model §10.1): the rules engine and learned mappings key
-- off a stable `tag_id`, merge needs two ids to fold into one, and rename is a single-row update — a
-- retyped `Audi ` / `audi` must not silently fork. The hierarchy is a self-reference (`Car` with
-- `Car:Audi`, `Car:Skoda` beneath it) and, unlike accounts (§5), is NOT leaves-only: a posting may
-- carry a parent tag directly and/or its leaves, so a parent is simultaneously a taggable label and
-- a rollup (data-model §10.3). There is deliberately no unique(name, parent_id): reuse is decided
-- case-insensitively in the service (like `payee`), leaving room for merge rather than a hard
-- constraint.
--
-- `posting_tag` is the classical many-to-many, attaching a tag to a POSTING, not a transaction
-- (data-model §10.2, forced by FR-TAG-04): a fuel line tagged `Car:Passat` and a snacks line tagged
-- `Trip:Prague` sit on the same split transaction, so the tag must land per-leg. Applying a tag to a
-- whole transaction in the UI is just input convenience — it expands to one row per leg.

-- ── tag (self-referential hierarchy; NOT leaves-only) ────────────────────────
create table tag (
  tag_id     bigint generated always as identity primary key,
  name       text not null,
  parent_id  bigint references tag(tag_id),   -- self-ref; NULL = top level (data-model §10.1)
  deleted_at timestamptz                       -- soft-delete, orthogonal to liveness (data-model §3.5)
);

-- The hierarchy is walked parent→child (label composition, future subtree rollups over §10.3).
create index tag_parent_id_idx on tag (parent_id);

-- ── posting_tag (m2m: a posting carries a tag at most once) ───────────────────
create table posting_tag (
  posting_tag_id bigint generated always as identity primary key,
  posting_id     bigint not null references posting(posting_id),
  tag_id         bigint not null references tag(tag_id),
  unique (posting_id, tag_id)                  -- a tag applies to a posting at most once (§10.1)
);

-- The unique(posting_id, tag_id) already indexes posting_id as its prefix (per-posting reads); the
-- tag_id side needs its own index for the eventual "distinct postings in a tag's subtree" rollups
-- (data-model §10.3, reporting is backlog) and to keep the FK cheap.
create index posting_tag_tag_id_idx on posting_tag (tag_id);
