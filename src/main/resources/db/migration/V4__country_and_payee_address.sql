-- V4 — the country reference list and the payee address (register §3.4, plan stage 7b).
--
-- The payee picker's create-new parses a typed string like `Rewe - Dortmund - Germany` into
-- Name / City / Country. City stays free text (no gazetteer), but the LAST segment is validated
-- against a small, stable, offline country list — matched by canonical name OR a common alias, so
-- `France` / `Frankreich` / `FR` all resolve to the same row. That validation is what tells a
-- city from a country (register §3.4), so the list needs its aliases.
--
-- `country` is externally-defined, so it keeps its natural key — the ISO-3166 alpha-3 code — exactly
-- like `currency` keeps its ISO-4217 code (data-model §3.0, exception 1). Aliases live in their own
-- `country_alias` table (an alias → country mapping) rather than an array column, so the match is a
-- plain indexed equality join and adding a spelling is one insert.
--
-- Seeded set: not all ~250 countries, but the ones the owner's life actually touches (the EUR/CHF
-- neighbourhood plus the common foreign cases), mirroring the currency seed's "only what's in use"
-- stance (data-model §3.1). More can be added later without a schema change.

-- ── country (externally-defined — natural key) ───────────────────────────────
create table country (
  country_code text primary key,   -- ISO-3166 alpha-3, e.g. 'DEU', 'FRA', 'CHE'
  name         text not null       -- canonical English display name
);

-- ── country_alias (an accepted spelling → its country) ───────────────────────
-- The alias is the lookup key (matched, lower-cased, against a typed segment); it is unique so one
-- spelling maps to at most one country. The canonical name is matched too (seeded here as an alias
-- of itself), so the create-new parser has a single table to consult.
create table country_alias (
  country_alias_id bigint generated always as identity primary key,
  country_code     text not null references country(country_code),
  alias            text not null,
  unique (alias)
);

create index country_alias_country_code_idx on country_alias (country_code);

-- ── payee gains its optional address (register §3.4) ─────────────────────────
-- A payee is Name + optional City + optional Country. City is free text; country references the
-- seeded list (nullable — many payees have neither).
alter table payee
  add column city         text,
  add column country_code text references country(country_code);

-- ── seed: the countries in use, each with its English name plus German and ISO-code aliases ──
insert into country (country_code, name) values
  ('DEU', 'Germany'),
  ('CHE', 'Switzerland'),
  ('AUT', 'Austria'),
  ('FRA', 'France'),
  ('ITA', 'Italy'),
  ('NLD', 'Netherlands'),
  ('BEL', 'Belgium'),
  ('ESP', 'Spain'),
  ('POL', 'Poland'),
  ('CZE', 'Czech Republic'),
  ('GBR', 'United Kingdom'),
  ('USA', 'United States'),
  ('JPN', 'Japan');

-- Every country matches on its canonical name, its ISO codes (both the alpha-3 natural key and the
-- alpha-2 people still often type), and its common German exonym. Seeded lower-cased because the
-- parser lower-cases the typed segment before the lookup (case-insensitive match, no functional
-- index).
insert into country_alias (country_code, alias) values
  ('DEU', 'germany'),        ('DEU', 'deu'), ('DEU', 'de'), ('DEU', 'deutschland'),
  ('CHE', 'switzerland'),    ('CHE', 'che'), ('CHE', 'ch'), ('CHE', 'schweiz'), ('CHE', 'suisse'),
  ('AUT', 'austria'),        ('AUT', 'aut'), ('AUT', 'at'), ('AUT', 'österreich'), ('AUT', 'oesterreich'),
  ('FRA', 'france'),         ('FRA', 'fra'), ('FRA', 'fr'), ('FRA', 'frankreich'),
  ('ITA', 'italy'),          ('ITA', 'ita'), ('ITA', 'it'), ('ITA', 'italien'), ('ITA', 'italia'),
  ('NLD', 'netherlands'),    ('NLD', 'nld'), ('NLD', 'nl'), ('NLD', 'niederlande'), ('NLD', 'holland'),
  ('BEL', 'belgium'),        ('BEL', 'bel'), ('BEL', 'be'), ('BEL', 'belgien'), ('BEL', 'belgique'),
  ('ESP', 'spain'),          ('ESP', 'esp'), ('ESP', 'es'), ('ESP', 'spanien'), ('ESP', 'españa'),
  ('POL', 'poland'),         ('POL', 'pol'), ('POL', 'pl'), ('POL', 'polen'), ('POL', 'polska'),
  ('CZE', 'czech republic'), ('CZE', 'cze'), ('CZE', 'cz'), ('CZE', 'tschechien'), ('CZE', 'czechia'),
  ('GBR', 'united kingdom'), ('GBR', 'gbr'), ('GBR', 'gb'), ('GBR', 'uk'), ('GBR', 'großbritannien'),
  ('USA', 'united states'),  ('USA', 'usa'), ('USA', 'us'), ('USA', 'vereinigte staaten'),
  ('JPN', 'japan'),          ('JPN', 'jpn'), ('JPN', 'jp');
