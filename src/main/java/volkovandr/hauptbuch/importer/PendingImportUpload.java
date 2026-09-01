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
 * with the choice applied.
 *
 * @param token the opaque handle the preview URL is keyed on
 * @param sourceFilename the uploaded filename — reference only, it carries no identity (§2)
 * @param moneyAccountName the Money account the owner stated this file is for (§4.1)
 * @param contentBase64 the uploaded bytes, Base64-encoded
 * @param charsetChoice {@code utf_8} / {@code windows_1252} override, or null to follow detection
 * @param dateOrderChoice {@code day_month} / {@code month_day} override, or null to follow
 *     detection
 */
record PendingImportUpload(
    String token,
    String sourceFilename,
    String moneyAccountName,
    String contentBase64,
    String charsetChoice,
    String dateOrderChoice)
    implements Serializable {

  static PendingImportUpload of(
      String token, String sourceFilename, String moneyAccountName, byte[] content) {
    return new PendingImportUpload(
        token,
        sourceFilename,
        moneyAccountName,
        Base64.getEncoder().encodeToString(content),
        null,
        null);
  }

  PendingImportUpload withChoice(String charsetChoice, String dateOrderChoice) {
    return new PendingImportUpload(
        token, sourceFilename, moneyAccountName, contentBase64, charsetChoice, dateOrderChoice);
  }

  /** The uploaded bytes, decoded fresh so no caller can mutate the held copy. */
  byte[] content() {
    return Base64.getDecoder().decode(contentBase64);
  }
}
