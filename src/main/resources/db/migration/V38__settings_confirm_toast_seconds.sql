-- How long a "Confirmed!" toast stays visible after a receipt is confirmed (receipt issue 38).
-- 0 switches the toasts off; the upper bound only keeps a typo from pinning toasts on screen.
alter table settings
  add column confirm_toast_seconds smallint not null default 3
  check (confirm_toast_seconds between 0 and 60);
