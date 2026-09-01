package volkovandr.hauptbuch.importer;

import java.io.Serializable;
import java.util.Base64;

/**
 * One QIF file uploaded but <em>not</em> yet staged — held in the HTTP session between the upload
 * and the "Confirm" that writes it to the staging tables (import.md §2; plan b2 stages nothing, b3
 * confirms). The preview is recomputed from {@link #content()} on every render, so nothing crosses
 * into a staging table until b3; a discarded or committed campaign just drops the session.
 *
 * <p>The bytes ride as Base64 so this stays an immutable value (the {@link
 * volkovandr.hauptbuch.receipts.ReceiptBatchItem} precedent). A single-account export is small and
 * the preview is looked at only a handful of times before b3 stages the file and drops it from the
 * session, so re-decoding and re-parsing per render is cheap enough here — it is not the
 * whole-campaign volume. {@code charsetChoice} / {@code dateOrderChoice} are the owner's override
 * from the preview screen (import.md §4.3/§4.4 — detection is a proposal, confirmed or overridden
 * before anything stages); null ⇒ follow the detected value. {@link #withChoice} returns a copy
 * with the choices applied.
 *
 * <p>{@code moneyAccountName} is which Money account the file is for (§4.1). The file has no field
 * for it, but usually names it in the opening-balance self-transfer (§5.1) — {@link
 * #withDeducedAccountName} records that reading at upload, and {@code accountNameDeduced} marks it
 * as the file's own answer (so the preview can say so). The owner corrects it, or supplies it for
 * the rare file with no opening-balance record, on the preview screen; null ⇒ not yet known, and
 * staging is refused until it is.
 *
 * @param token the opaque handle the preview URL is keyed on
 * @param sourceFilename the uploaded filename — reference only, it carries no identity (§2)
 * @param moneyAccountName the Money account the file is for (§4.1), or null when not yet known
 * @param accountNameDeduced true when {@code moneyAccountName} was read from the file's own
 *     opening-balance record rather than stated by the owner (§5.1)
 * @param contentBase64 the uploaded bytes, Base64-encoded
 * @param charsetChoice {@code utf_8} / {@code windows_1252} override, or null to follow detection
 * @param dateOrderChoice {@code day_month} / {@code month_day} override, or null to follow
 *     detection
 */
record PendingImportUpload(
    String token,
    String sourceFilename,
    String moneyAccountName,
    boolean accountNameDeduced,
    String contentBase64,
    String charsetChoice,
    String dateOrderChoice)
    implements Serializable {

  static PendingImportUpload of(String token, String sourceFilename, byte[] content) {
    return new PendingImportUpload(
        token,
        sourceFilename,
        null,
        false,
        Base64.getEncoder().encodeToString(content),
        null,
        null);
  }

  /** A copy naming the account read back from the file's own opening-balance record (§5.1). */
  PendingImportUpload withDeducedAccountName(String moneyAccountName) {
    return new PendingImportUpload(
        token,
        sourceFilename,
        moneyAccountName,
        true,
        contentBase64,
        charsetChoice,
        dateOrderChoice);
  }

  /**
   * A copy with the owner's preview-screen confirmation applied: a non-blank {@code accountName} is
   * their explicit choice (no longer the file's deduced answer); a blank one keeps whatever name is
   * already held.
   */
  PendingImportUpload withChoice(String charsetChoice, String dateOrderChoice, String accountName) {
    boolean stated = accountName != null && !accountName.isBlank();
    return new PendingImportUpload(
        token,
        sourceFilename,
        stated ? accountName.strip() : moneyAccountName,
        !stated && accountNameDeduced,
        contentBase64,
        charsetChoice,
        dateOrderChoice);
  }

  /** The uploaded bytes, decoded fresh so no caller can mutate the held copy. */
  byte[] content() {
    return Base64.getDecoder().decode(contentBase64);
  }
}
