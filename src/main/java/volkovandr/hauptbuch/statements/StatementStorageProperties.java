package volkovandr.hauptbuch.statements;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where uploaded statement files live on disk (ARCH-07). Like the receipt storage the root is
 * profile-specific and every path stored in the database is root-relative.
 *
 * @param storageRoot the absolute filesystem root under which the files are written
 */
@ConfigurationProperties("hauptbuch.statements")
public record StatementStorageProperties(Path storageRoot) {}
