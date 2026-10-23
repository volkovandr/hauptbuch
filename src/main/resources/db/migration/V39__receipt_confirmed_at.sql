-- When a receipt was last confirmed (receipt issue 39), so the receipt list can show and sort by it.
-- NULL until the first Confirm. A receipt confirmed before this column existed is backfilled from
-- the creation time of the transaction it booked, which is when that Confirm happened.
alter table receipt add column confirmed_at timestamptz;

update receipt r
set confirmed_at = t.created_at
from transaction t
where r.transaction_id = t.transaction_id;
