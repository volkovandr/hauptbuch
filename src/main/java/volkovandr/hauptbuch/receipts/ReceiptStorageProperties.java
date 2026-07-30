package volkovandr.hauptbuch.receipts;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where receipt images live on disk (ARCH-07). The root is profile-specific — the Pi's data
 * directory in production, a throwaway local directory in dev, a temp directory in tests — and is
 * the only thing the storage layer needs from config; every path stored in the database is
 * <em>root-relative</em>, so the tree can be relocated by changing this one value.
 *
 * @param storageRoot the absolute filesystem root under which {@code originals/}, {@code edited/},
 *     and {@code thumbs/} are created
 */
@ConfigurationProperties("hauptbuch.receipts")
public record ReceiptStorageProperties(Path storageRoot) {}
