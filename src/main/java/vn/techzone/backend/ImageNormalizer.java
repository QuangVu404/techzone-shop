package vn.techzone.backend;

import vn.techzone.infrastructure.PythonModels;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Converts supported image formats to an orientation-correct RGB PNG. */
final class ImageNormalizer {
    private ImageNormalizer() { }

    static BufferedImage normalize(byte[] bytes) throws IOException, InterruptedException {
        Path input = Files.createTempFile("techzone-photo-input-", ".bin");
        Path output = null;
        try {
            output = Files.createTempFile("techzone-photo-normalized-", ".png");
            Files.write(input, bytes);
            PythonModels.run("normalize_image.py", 90, input.toString(), output.toString());
            BufferedImage image = ImageIO.read(output.toFile());
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                    || image.getWidth() > 800 || image.getHeight() > 800)
                throw new IOException("Không thể xử lý ảnh này.");
            return image;
        } finally {
            if (output != null) Files.deleteIfExists(output);
            Files.deleteIfExists(input);
        }
    }
}
