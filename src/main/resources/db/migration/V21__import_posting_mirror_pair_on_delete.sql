-- V21 — let a staged posting's mirror-pair link clear itself when its partner is deleted
-- (import.md §6.1; plan e1).
--
-- e1 is the first slice to populate `import_posting.mirror_pair_id` — the per-posting-pair link
-- between the two sightings of a transfer (§6.1). The two sightings live in different `import_file`
-- rows, so removing one staged file (plan b3) would leave the surviving sighting pointing at a
-- posting that no longer exists. The V19 inline FK defaulted to NO ACTION, which would block that
-- removal outright. `on delete set null` is the right behaviour: the link is a pairing hint, not a
-- structural parent, and `ImportMirrorMatchingService` re-runs after any file removal to reset the
-- now-unpaired sighting's state and re-match against the current account map.
alter table import_posting
  drop constraint import_posting_mirror_pair_id_fkey;

alter table import_posting
  add constraint import_posting_mirror_pair_id_fkey
  foreign key (mirror_pair_id) references import_posting(import_posting_id) on delete set null;
