"""Rank product photos using SigLIP vision embeddings, with a ResNet-18 fallback."""
import json
import os
import sys
import tempfile
import zipfile
from pathlib import Path
import numpy as np
import torch
from PIL import Image, ImageOps
from torchvision.models import resnet18, ResNet18_Weights
from transformers import SiglipVisionModel, SiglipImageProcessor

ROOT = Path(__file__).resolve().parent.parent
WEIGHTS = ROOT / 'models' / 'resnet18-f37072fd.pth'
SIGLIP = ROOT / 'models' / 'siglip-base'
REVISION = '7fd15f0689c79d79e38b1c2e2e2370a7bf2761ed'

def main():
    if len(sys.argv) not in (3, 4):
        raise ValueError('Expected image file, catalogue directory and optional eligible IDs')
    query = Path(sys.argv[1])
    directory = Path(sys.argv[2])
    eligible = set(Path(sys.argv[3]).read_text(encoding='utf-8').splitlines()) if len(sys.argv) == 4 else None
    marker = SIGLIP / '.techzone-ready'
    strong = marker.is_file() and marker.read_text(encoding='ascii').strip() == REVISION and (SIGLIP / 'model.safetensors').is_file()
    if not strong and not WEIGHTS.is_file():
        raise FileNotFoundError('Missing image model; run setup-ai.bat')
    torch.set_num_threads(min(4, os.cpu_count() or 1))
    if strong:
        model = SiglipVisionModel.from_pretrained(SIGLIP, local_files_only=True).eval()
        processor = SiglipImageProcessor.from_pretrained(SIGLIP, local_files_only=True)
    else:
        model = resnet18(weights=None)
        model.load_state_dict(torch.load(WEIGHTS, map_location='cpu', weights_only=True))
        model.fc = torch.nn.Identity()
        model.eval()
        transform = ResNet18_Weights.IMAGENET1K_V1.transforms()
    def embedding(path):
        with Image.open(path) as image:
            image = ImageOps.exif_transpose(image).convert('RGB')
            inputs = processor(images=image, return_tensors='pt') if strong else transform(image).unsqueeze(0)
        with torch.inference_mode():
            result = model(**inputs).pooler_output.squeeze(0) if strong else model(inputs).squeeze(0)
            return torch.nn.functional.normalize(result, dim=0).numpy()
    print('#model\t' + ('SigLIP' if strong else 'ResNet-18'))
    query_vector = embedding(query)
    model_version = 'siglip-' + REVISION if strong else 'resnet18-f37072fd'
    cache_file = directory / '.techzone-image-embeddings.npz'
    cached = {}
    try:
        with np.load(cache_file, allow_pickle=False) as data:
            if str(data['model'].item()) == model_version:
                cached = {str(key): (int(size), int(mtime), vector) for key, size, mtime, vector
                          in zip(data['ids'], data['sizes'], data['mtimes'], data['vectors'])}
    except (OSError, ValueError, KeyError, TypeError, EOFError, zipfile.BadZipFile):
        pass
    results = []
    changed = False
    for path in sorted(directory.glob('*.png')):
        if not path.stem.replace('-', '').replace('_', '').isalnum() or (eligible is not None and path.stem not in eligible):
            continue
        try:
            stat = path.stat()
            entry = cached.get(path.stem)
            if entry is None or entry[0] != stat.st_size or entry[1] != stat.st_mtime_ns or entry[2].shape != query_vector.shape:
                entry = (stat.st_size, stat.st_mtime_ns, embedding(path))
                cached[path.stem] = entry
                changed = True
            similarity = float(np.dot(query_vector, entry[2]))
            if np.isfinite(similarity):
                results.append((path.stem, similarity))
        except (OSError, ValueError):
            continue
    if changed and cached:
        # Write atomically so a failed or interrupted request cannot corrupt the cache.
        try:
            with tempfile.NamedTemporaryFile(dir=directory, suffix='.npz', delete=False) as file:
                temporary = Path(file.name)
                np.savez_compressed(file, model=model_version, ids=np.array(list(cached)),
                                    sizes=np.array([x[0] for x in cached.values()], dtype=np.int64),
                                    mtimes=np.array([x[1] for x in cached.values()], dtype=np.int64),
                                    vectors=np.stack([x[2] for x in cached.values()]))
            os.replace(temporary, cache_file)
        except (OSError, ValueError):
            if 'temporary' in locals(): temporary.unlink(missing_ok=True)
    for product_id, similarity in sorted(results, key=lambda item: (-item[1], item[0])):
        print(f'{product_id}\t{similarity:.6f}')

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
