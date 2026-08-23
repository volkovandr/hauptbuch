# Erasing regions of a receipt image — out of scope

Rejected 2026-08-23. Full reasoning in
`.scratch/receipt-processing/issues/21-erase-regions-in-receipt-image.md`.

**The request:** paint out or cut out parts of a receipt scan before analysis — a long receipt's
marketing block (to save tokens) and a QR code (token waste, possible security risk).

**Why it was rejected, in one line each:**

- Image tokens are `⌈w/28⌉ × ⌈h/28⌉` — **area, not content**. Whiting out a region saves nothing at
  all, and cutting a band from a tall receipt makes it ~35% *more* expensive, because the gentler
  downscale lets more pixels survive in the dimension that was not cut.
- Claude very likely cannot decode a QR code's payload anyway; the parse call carries **no tools**,
  so a URL it read could not be visited; ARCH-08 means there are no ledger contents in the request
  to exfiltrate; and the operator reviews every draft before Confirm books anything.
- The whole cost at stake is about **$0.002 per receipt** — against ~150 lines of untestable
  pointer-handling canvas JS, in the one layer of this codebase with no test tier.

**What replaced it:** `receipt-processing/24` — tune `LONG_EDGE_CAP`, which is the actual dial on
image cost and quality, and whose in-code comment is stale now that the app calls a
high-resolution-tier model.

**Revival conditions** are listed at the end of issue 21.
