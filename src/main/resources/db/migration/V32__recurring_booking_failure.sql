-- V32 — a recurring template's booking failure (data-model §14.3, recurring sub-plan slice f).
--
-- A template whose occurrence cannot book rolls back alone and keeps its cursor; the run retries
-- every time. The latest reason, and when the template first started failing, are kept on the
-- template so the main page and the recurring page can name it. The first run that completes
-- clears both.
alter table recurring_template add column booking_failure text;
alter table recurring_template add column booking_failed_since timestamptz;
alter table recurring_template add constraint recurring_template_booking_failure
  check ((booking_failure is null) = (booking_failed_since is null));
