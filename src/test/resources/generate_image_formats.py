"""Create tiny, non-sensitive image fixtures for backend format tests."""
from pathlib import Path
import shutil
import struct
import sys
import zlib
from PIL import Image, ImageDraw
import pillow_heif

pillow_heif.register_heif_opener()
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
base = Image.new('RGB', (96, 64), 'white')
ImageDraw.Draw(base).ellipse((24, 12, 72, 55), fill='red')
for name, fmt in [('sample.png', 'PNG'), ('sample.jpg', 'JPEG'), ('sample.bmp', 'BMP'),
                  ('sample.webp', 'WEBP'), ('sample.tiff', 'TIFF'), ('sample.avif', 'AVIF'),
                  ('sample.heic', 'HEIF'), ('sample.ppm', 'PPM')]:
    base.save(out / name, format=fmt)
base.save(out / 'sample.gif', format='GIF', save_all=True,
          append_images=[Image.new('RGB', base.size, 'blue')], duration=100, loop=0)
shutil.copyfile(out / 'sample.heic', out / 'no-extension')
transparent = Image.new('RGBA', (96, 64), (0, 0, 0, 0))
ImageDraw.Draw(transparent).ellipse((24, 12, 72, 55), fill=(255, 0, 0, 255))
transparent.save(out / 'transparent.png')
exif = Image.Exif()
exif[274] = 6
base.save(out / 'oriented.jpg', exif=exif)

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
header = struct.pack('>IIBBBBB', 8001, 8001, 8, 2, 0, 0, 0)
(out / 'too-many-pixels.png').write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(b'\0')) + chunk(b'IEND', b''))
