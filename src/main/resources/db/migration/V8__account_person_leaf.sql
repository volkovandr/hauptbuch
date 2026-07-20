-- ── Per-person debt leaf marker (data-model §7, plan stage 8b.1) ─────────────────────────────
-- Distinguishes a leaf PersonProvisioningService auto-provisions for a person (one per currency
-- they are owed in or owe) from a real, user-created asset account. A person is reached through
-- the `for`/`by` sigils in the entry dock's pickers, never by the leaf's own cosmetic name
-- (`personal.<CUR>`), so the leaf is hidden from the dock's Account picker, the accounts
-- management screen, and transfer-target resolution — the flag, not the name, is what marks it.
--
-- The flag mirrors `currency_leaf` (V5) rather than being derived from the `account_owner` link
-- because `debts` already depends on `accounts`; the reverse edge would close a module cycle.
alter table account add column person_leaf boolean not null default false;

-- Backfill: every account already linked to a person is such a leaf by construction (stage 8a's
-- provisioning is the only thing that writes account_owner).
update account
set person_leaf = true
where account_id in (select account_id from account_owner);
