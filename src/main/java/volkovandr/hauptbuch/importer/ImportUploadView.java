package volkovandr.hauptbuch.importer;

/**
 * A pending upload as the {@code /import} screen needs it (plan b2) — identity and labels only, no
 * file bytes. Keeps {@link PendingImportUpload} (which holds the raw bytes in the HTTP session) out
 * of the view layer.
 *
 * @param token the opaque handle the preview URL is keyed on
 * @param sourceFilename the uploaded filename — reference only, it carries no identity (import.md
 *     §2)
 * @param moneyAccountName the Money account the owner stated this file is for (§4.1)
 */
public record ImportUploadView(String token, String sourceFilename, String moneyAccountName) {

  static ImportUploadView of(PendingImportUpload upload) {
    return new ImportUploadView(upload.token(), upload.sourceFilename(), upload.moneyAccountName());
  }
}
