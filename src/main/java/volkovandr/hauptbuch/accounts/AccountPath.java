package volkovandr.hauptbuch.accounts;

/**
 * A posting-leaf account paired with its full root-to-leaf display path — every semantic ancestor's
 * name joined by a caller-chosen separator (e.g. {@code Food - Milk}). A display projection for
 * pickers that must show <em>where</em> a leaf sits in the hierarchy when a native control cannot
 * indent (the register's Category datalist, register §3.5) and for name→id resolution against that
 * same path.
 *
 * @param accountId the leaf account a posting may hit
 * @param path its root-to-leaf path, ancestor names joined by the requested separator
 */
public record AccountPath(long accountId, String path) {}
