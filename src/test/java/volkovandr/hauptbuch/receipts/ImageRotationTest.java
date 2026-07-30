package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/**
 * Unit tier: {@link ImageRotation} — baking an EXIF orientation into the pixels (§9b
 * thumbnail-orientation fix). Checked by the dimension swap a 90° rotation produces.
 */
class ImageRotationTest {

  @Test
  void orientationOneLeavesTheImageUnchanged() {
    BufferedImage landscape = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
    assertThat(ImageRotation.applyExifOrientation(landscape, 1)).isSameAs(landscape);
  }

  @Test
  void rotationOrientationsSwapWidthAndHeight() {
    BufferedImage landscape = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);

    // 6 = rotate 90° CW: a 4×2 landscape becomes a 2×4 portrait.
    BufferedImage rotatedCw = ImageRotation.applyExifOrientation(landscape, 6);
    assertThat(rotatedCw.getWidth()).isEqualTo(2);
    assertThat(rotatedCw.getHeight()).isEqualTo(4);

    // 3 = 180°: dimensions unchanged.
    BufferedImage flipped = ImageRotation.applyExifOrientation(landscape, 3);
    assertThat(flipped.getWidth()).isEqualTo(4);
    assertThat(flipped.getHeight()).isEqualTo(2);
  }
}
