package volkovandr.hauptbuch.statements;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.web.multipart.MultipartFile;

/** Reads the bytes of an uploaded file for the statement controllers. */
final class UploadedFiles {

  private UploadedFiles() {}

  /** The file's bytes; a missing file reads as empty, which {@link StatementStorage} rejects. */
  static byte[] bytesOf(MultipartFile file) {
    if (file == null) {
      return new byte[0];
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded file", e);
    }
  }
}
