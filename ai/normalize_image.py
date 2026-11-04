"""Decode common raster formats, respect EXIF rotation and save a safe RGB PNG."""
import sys
import warnings
from pathlib import Path
from PIL import Image, ImageOps, UnidentifiedImageError

try:
    import pillow_heif
    pillow_heif.register_heif_opener()
    HAVE_HEIF = True
except ImportError:
    HAVE_HEIF = False

MAX_PIXELS = 64_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
FORMATS = ['PNG', 'JPEG', 'BMP', 'GIF', 'WEBP', 'TIFF', 'HEIF', 'AVIF',
           'ICO', 'JPEG2000', 'MPO', 'TGA', 'PPM', 'PSD', 'PCX', 'DDS', 'SGI']
Image.init()
FORMATS = [name for name in FORMATS if name in Image.OPEN]

def main():
    if len(sys.argv) != 3:
        raise ValueError('INVALID_IMAGE')
    source, destination = Path(sys.argv[1]), Path(sys.argv[2])
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(source, formats=FORMATS) as original:
                if original.width < 1 or original.height < 1 or original.width * original.height > MAX_PIXELS:
                    raise ValueError('IMAGE_TOO_LARGE')
                original.seek(0)  # Animated images use the first frame.
                image = ImageOps.exif_transpose(original)
                image.thumbnail((800, 800), Image.Resampling.LANCZOS)
                if image.mode in ('RGBA', 'LA') or 'transparency' in image.info:
                    rgba = image.convert('RGBA')
                    background = Image.new('RGBA', rgba.size, (255, 255, 255, 255))
                    background.alpha_composite(rgba)
                    image = background.convert('RGB')
                else:
                    image = image.convert('RGB')
                image.save(destination, format='PNG')
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ValueError('IMAGE_TOO_LARGE')
    except UnidentifiedImageError:
        with source.open('rb') as f:
            header = f.read(16)
        if not HAVE_HEIF and len(header) >= 12 and header[4:8] == b'ftyp':
            raise ValueError('MISSING_HEIF')
        raise ValueError('INVALID_IMAGE')
    except (OSError, SyntaxError):
        raise ValueError('INVALID_IMAGE')
    print('OK')

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(str(exc) or 'INVALID_IMAGE', file=sys.stderr)
        sys.exit(1)
