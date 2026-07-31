package volkovandr.hauptbuch.receipts;

/**
 * The receipt file tree's path scheme (ARCH-07): the three parallel trees the database references
 * only by root-relative path — {@code originals/} (the immutable raw scan), {@code edited/} (the
 * pre-processed derivative the AI receives), and {@code thumbs/} (previews). All three share the
 * capture-timestamp stem, so a receipt's files line up by name. Pure string derivation, kept apart
 * from {@link ReceiptStorage} so the scheme is one place, not tangled with the file IO.
 */
final class ReceiptPaths {

  static final String ORIGINALS = "originals";
  static final String EDITED = "edited";
  static final String THUMBS = "thumbs";

  /** Thumbnails and edited bakes are both JPEG, so a derived file is always {@code .jpg}. */
  private static final String DERIVED_EXT = "jpg";

  private ReceiptPaths() {}

  /**
   * An original's path {@code originals/<yyyy/MM>/<stem>.<ext>}; a {@code suffix} of 2 or more adds
   * the {@code -N} collision marker (the first free name is picked by {@link ReceiptStorage}).
   */
  static String originalRel(String yearMonth, String stem, int suffix, String ext) {
    String tail = suffix < 2 ? stem : stem + "-" + suffix;
    return ORIGINALS + "/" + yearMonth + "/" + tail + "." + ext;
  }

  /**
   * The thumbnail path for an original: the {@code thumbs/} tree, same stem, always {@code .jpg}.
   */
  static String thumbRelFor(String originalRelPath) {
    return derivedRelFor(originalRelPath, THUMBS);
  }

  /**
   * The edited-derivative path for an original: the {@code edited/} tree, same stem, always {@code
   * .jpg}. Overwriting this path in place is a re-edit Save (receipt doc §6.1).
   */
  static String editedRelFor(String originalRelPath) {
    return derivedRelFor(originalRelPath, EDITED);
  }

  /** A derived path under {@code tree/}, reusing the original's stem with the JPEG extension. */
  private static String derivedRelFor(String originalRelPath, String tree) {
    String tail = stripFirstSegment(originalRelPath);
    int dot = tail.lastIndexOf('.');
    String withoutExt = dot < 0 ? tail : tail.substring(0, dot);
    return tree + "/" + withoutExt + "." + DERIVED_EXT;
  }

  /**
   * Everything after the first path segment (the {@code originals/} / {@code edited/} tree name).
   */
  private static String stripFirstSegment(String relPath) {
    int slash = relPath.indexOf('/');
    return slash < 0 ? relPath : relPath.substring(slash + 1);
  }
}
