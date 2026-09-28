# Recurring occurrences are booked as real transactions, driven by a per-template cursor

A recurring template (a subscription, a standing order, pocket money) has to turn into rows in the
register even though the app runs on a Pi that may be off when a payment falls due. We considered
three shapes: computing occurrences on the fly and only storing one when the operator acts on it;
pre-registering every occurrence inside a fixed horizon; and a timer that books "today's" payments at
midnight. The first leaves the register empty of the things the owner most wants to see coming. The
third loses every payment that falls due while the app is down. We chose to **book each occurrence
as an ordinary transaction**, `lead time` days before its date, confirmed or `pending_review` per
template. A **per-template booked-through date** drives the booking: each run books every occurrence
after that date up to today + lead time, then advances the date in the same DB transaction. Downtime
therefore means a longer catch-up the next time the app runs. A crash cannot book an occurrence
twice, and a voided occurrence is never seen again. An occurrence is never stored as a record of its
own. The booked transaction carries its template and occurrence date, and that stamp is the only
dedup key.

## Consequences

- **A template save deletes its own future pending rows outright**, then rebooks them under the new
  settings. This is the only hard delete of a transaction in the app. It is safe because an untouched
  pending row is a forecast, not the operator's data: a Save in the dock would already have confirmed
  it. It is also necessary. If the wipe voided rows instead, the operator's own voids (a skipped
  occurrence) and the wipe's voids would look the same. Either a deliberate skip would come back
  after an edit, or an edit reverted to its old value would never rebook.
- **Rebooking skips an occurrence date that already has a confirmed or voided transaction from the
  template.** Confirmed rows are the operator's facts, and voided rows are the operator's skips.
  Everything else is regenerated.
- **Saving a template never books the past retroactively.** Saving pulls the booked-through date
  back to no later than yesterday. A changed schedule or lead time therefore takes effect at once. A
  start date moved into the past books nothing, and a template that was failing still books what it
  owed. Only a brand-new template with a past start date asks whether to book its past occurrences.
- **Confirmed future-dated rows are accepted.** An `auto` template with a long lead time puts
  confirmed rows ahead of today into the ledger. That is the operator's choice; a lead time of 0
  avoids it.
