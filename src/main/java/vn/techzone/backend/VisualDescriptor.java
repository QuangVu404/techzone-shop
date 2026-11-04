package vn.techzone.backend;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;

/** Validates and normalizes images before local model inference. */
final class VisualDescriptor {
    static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IOException("Ảnh không hợp lệ.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Ảnh đã lưu không hợp lệ.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg") && !format.equals("bmp")) throw new IOException("Định dạng ảnh không được hỗ trợ.");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long) w * h > 24_000_000L) throw new IOException("Kích thước ảnh quá lớn.");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
}
