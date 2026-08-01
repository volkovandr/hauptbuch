-- V11 — the AI Vocabulary: the operator-curated projection of the category taxonomy the receipt
-- parser sees (data-model §13.3, plan stage 9d).
--
-- AI calls may never carry ledger contents (ARCH-08); the one sanctioned exception is this curated
-- projection — per category node an *alias* (the name shown to the AI instead of the real one), a
-- tri-state *visible* flag, and a freetext *AI note* (per-category prompt guidance steering how
-- items under it are categorised/tagged/attributed). It is what makes the parser suggest from *your*
-- taxonomy without ever seeing a balance.
--
-- Owned by the `categories` module (rename/subdivide/delete must keep it consistent, and that module
-- owns keep-the-taxonomy-consistent logic). Config attaches by `account_id`, so a category rename is
-- automatic (the row never moves); subdivision leaves the row on the now-group parent (children
-- inherit); deletion removes the subtree's rows.
--
-- `visible` is deliberately NULLable — the tri-state (data-model §13.3): true = always visible,
-- false = hidden, NULL = inherit (nearest ancestor with a set flag, else the type default: expense
-- visible, income hidden). There are no propagation writes: a group toggle touches no child rows.
-- The absence of a row entirely = inherit everything, no alias, no note. `unique(account_id)`
-- guarantees at most one config row per category node.
create table category_ai_config (
  category_ai_config_id bigint generated always as identity primary key,
  account_id bigint not null references account(account_id),
  visible    boolean,   -- true always / false hidden / null = inherit (ancestor, else type default)
  alias      text,      -- what the AI sees instead of the real name
  ai_note    text,      -- per-category prompt guidance (mirrors receipt.ai_note)
  unique (account_id)
);
