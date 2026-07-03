-- V3 — the stored two-tone hue on account (register §2.8, plan stage 6a).
--
-- Colour encodes the account in the register: each account carries two shades of the SAME stored
-- hue, alternated as a zebra. The hue is a property of the account, assigned once at creation —
-- never picked dynamically from whatever is on screen — so Giro stays the same blue whether or not
-- Visa is visible. Stored as a degree on the HSL colour wheel; the two shades are derived in CSS.
--
-- Nullable: system accounts and category-backing accounts are never register threads and carry no
-- hue. The accounts UI assigns one to every account it creates.
alter table account
  add column hue smallint check (hue between 0 and 359);
