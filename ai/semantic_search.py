"""Local multilingual sentence-embedding worker; stdin/stdout are an ASCII line protocol."""
import base64
import hashlib
import os
import sys
from collections import OrderedDict
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch
from transformers import AutoModel, AutoTokenizer

ROOT = Path(__file__).resolve().parent.parent
STRONG_MODEL = ROOT / "models" / "bge-m3"
STRONG_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
def strong_ready():
    marker = STRONG_MODEL / ".techzone-ready"
    return (marker.is_file() and marker.read_text(encoding="ascii").strip() == STRONG_REVISION
            and (STRONG_MODEL / "pytorch_model.bin").is_file())
USE_BGE = strong_ready()
MODEL = STRONG_MODEL if USE_BGE else ROOT / "models" / "semantic-mini"
MODEL_NAME = "BGE-M3" if USE_BGE else "MiniLM"
torch.set_num_threads(min(4, os.cpu_count() or 1))
tokenizer = AutoTokenizer.from_pretrained(MODEL, local_files_only=True)
model = AutoModel.from_pretrained(MODEL, local_files_only=True).eval()


def encode(texts):
    batches = []
    for pos in range(0, len(texts), 8):
        tokens = tokenizer(texts[pos:pos + 8], padding=True, truncation=True, max_length=128, return_tensors="pt")
        with torch.inference_mode():
            output = model(**tokens).last_hidden_state
            if USE_BGE:
                # BGE-M3 dense retrieval uses the first token, not mean pooling.
                pooled = output[:, 0]
            else:
                mask = tokens["attention_mask"].unsqueeze(-1).expand(output.size()).float()
                pooled = (output * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
            batches.append(torch.nn.functional.normalize(pooled, p=2, dim=1))
    return torch.cat(batches, dim=0)


def unpack(value):
    return base64.b64decode(value, validate=True).decode("utf-8")


print("READY\t" + MODEL_NAME, flush=True)
cached_vectors = OrderedDict()
for raw in sys.stdin:
    try:
        fields = raw.rstrip("\r\n").split("\t")
        if len(fields) < 3 or len(fields) % 2 != 1:
            raise ValueError("invalid request")
        query = unpack(fields[0])
        ids = [unpack(v) for v in fields[1::2]]
        texts = [unpack(v) for v in fields[2::2]]
        keys = [hashlib.sha256((identifier + '\0' + text).encode('utf-8')).digest()
                for identifier, text in zip(ids, texts)]
        missing = OrderedDict((key, text) for key, text in zip(keys, texts) if key not in cached_vectors)
        if missing:
            vectors = encode(list(missing.values()))
            for key, vector in zip(missing, vectors): cached_vectors[key] = vector
        for key in keys: cached_vectors.move_to_end(key)
        document_vectors = torch.stack([cached_vectors[key] for key in keys])
        while len(cached_vectors) > max(3000, len(keys)): cached_vectors.popitem(last=False)
        scores = torch.mv(document_vectors, encode([query])[0]).tolist()
        output = ["OK"]
        for product_id, score in zip(ids, scores):
            output.extend((base64.b64encode(product_id.encode("utf-8")).decode("ascii"), format(score, ".8f")))
        print("\t".join(output), flush=True)
    except Exception as exc:
        print("ERROR", flush=True)
        print(type(exc).__name__, file=sys.stderr, flush=True)
