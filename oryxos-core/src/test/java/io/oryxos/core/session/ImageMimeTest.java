package io.oryxos.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageMimeTest {

  @TempDir Path dir;

  @Test
  @DisplayName("路径后缀推断 MIME")
  void fromPathByExtension() {
    assertEquals(ImageMime.IMAGE_PNG, ImageMime.fromPath("a/b/c.PNG"));
    assertEquals(ImageMime.IMAGE_JPEG, ImageMime.fromPath("https://x/img.jpg?token=1"));
    assertEquals(ImageMime.IMAGE_WEBP, ImageMime.fromPath("x.webp"));
    assertEquals(ImageMime.IMAGE_JPEG, ImageMime.fromPath("noext"));
  }

  @Test
  @DisplayName("魔数优先于错误后缀")
  void probeFilePrefersMagic() throws Exception {
    Path fakeBin = dir.resolve("shot.bin");
    // PNG signature
    Files.write(
        fakeBin,
        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00});
    assertEquals(ImageMime.IMAGE_PNG, ImageMime.probeFile(fakeBin));
    assertEquals(".png", ImageMime.extensionFor(ImageMime.IMAGE_PNG));
  }
}
