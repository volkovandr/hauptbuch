package volkovandr.hauptbuch.statements;

import java.util.OptionalLong;

/**
 * What the confirm step of a PDF upload shows: that a text layer was read, and the account whose
 * identifier appears in it.
 *
 * @param characters the length of the extracted text
 * @param proposedAccountId the account whose detection labels name an IBAN or account number in
 *     the text
 */
public record PdfUploadPreview(int characters, OptionalLong proposedAccountId) {}
