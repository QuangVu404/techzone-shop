"""Install pinned public pretrained checkpoints; inference itself remains offline."""
import hashlib
import os
import urllib.request
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / 'models'
MODELS.mkdir(exist_ok=True)
os.environ.setdefault('HF_HOME', str(MODELS / '.hf-cache'))
from huggingface_hub import snapshot_download

CHECKPOINTS = [
    ('BAAI/bge-m3', '5617a9f61b028005a4858fdac845db406aefb181', 'bge-m3',
     ['config.json','tokenizer.json','tokenizer_config.json','special_tokens_map.json','sentencepiece.bpe.model'],
     'pytorch_model.bin', 'b5e0ce3470abf5ef3831aa1bd5553b486803e83251590ab7ff35a117cf6aad38'),
    ('openai/whisper-small', '973afd24965f72e36ca33b3055d56a652f456b4d', 'whisper-small',
     ['config.json','generation_config.json','preprocessor_config.json','tokenizer.json','tokenizer_config.json','vocab.json','merges.txt','normalizer.json','special_tokens_map.json'],
     'model.safetensors', '1d7734884874f1a1513ed9aa760a4f8e97aaa02fd6d93a3a85d27b2ae9ca596b'),
    ('google/siglip-base-patch16-224', '7fd15f0689c79d79e38b1c2e2e2370a7bf2761ed', 'siglip-base',
     ['config.json','preprocessor_config.json'],
     'model.safetensors', '2c63cb7d1f2e95ba501893cbb8faeb4ea9a3af295498d35097126228659c2af8'),
    ('Qwen/Qwen2.5-1.5B-Instruct', '989aa7980e4cf806f80c7fef2b1adb7bc71aa306', 'qwen-chat',
     ['config.json','generation_config.json','tokenizer.json','tokenizer_config.json','vocab.json','merges.txt'],
     'model.safetensors', 'dd924a11b4c220f385b51ffa522daea7c9f3d850e31b162bb5661df483c6d3ee'),
]

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def download_resumable(url, target, size):
    """Resume a streaming transfer; retain complete ranges from an earlier interrupted attempt."""
    parts_dir = target.parent / '.chat-download-parts'
    parts = sorted(parts_dir.glob('*.part')) if parts_dir.is_dir() else []
    chunk = 8 * 1024 * 1024
    if not target.exists() and parts:
        with target.open('wb') as output:
            for start in range(0, size, chunk):
                part = parts_dir / f'{start:012d}.part'
                expected = min(chunk, size-start)
                if not part.is_file() or part.stat().st_size != expected:
                    break
                with part.open('rb') as source:
                    for block in iter(lambda: source.read(1024*1024),b''):
                        output.write(block)
        print(f'  Resuming from {target.stat().st_size // (1024*1024)} MB',flush=True)
    failures = 0
    while not target.exists() or target.stat().st_size < size:
        start=target.stat().st_size if target.exists() else 0
        headers={'User-Agent':'TechZone-AI-Setup/1.0'}
        if start: headers['Range']=f'bytes={start}-'
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=120) as response:
                if start and (response.status!=206 or not response.headers.get('Content-Range','').startswith(f'bytes {start}-')):
                    raise RuntimeError('Server did not honor the resume offset')
                with target.open('ab' if start else 'wb') as output:
                    next_report=((start // (128*1024*1024))+1)*(128*1024*1024)
                    while block:=response.read(256*1024):
                        output.write(block)
                        if output.tell()>=next_report:
                            print(f'  {output.tell() // (1024*1024)} MB downloaded',flush=True)
                            next_report+=128*1024*1024
            current=target.stat().st_size
            if current>size: raise RuntimeError('Server returned too many bytes')
            if current<size: raise RuntimeError(f'Connection ended early at {current} of {size} bytes')
        except Exception as ex:
            failures+=1
            if failures>=6: raise RuntimeError(f'Unable to finish the download: {ex}') from ex
            print(f'  Connection interrupted; retrying from {target.stat().st_size if target.exists() else 0} bytes',flush=True)
            time.sleep(min(2**failures,10))
    return parts

for repo, revision, folder, metadata, weight, sha256 in CHECKPOINTS:
    selected = os.environ.get('TECHZONE_MODEL', '').strip()
    if selected and folder != selected:
        continue
    target = MODELS / folder
    target.mkdir(exist_ok=True)
    marker, weights = target / '.techzone-ready', target / weight
    if marker.is_file() and marker.read_text(encoding='ascii').strip() == revision and all((target / f).is_file() for f in metadata) and weights.is_file():
        print(f'{repo} is already installed.', flush=True)
        continue
    marker.unlink(missing_ok=True)
    print(f'Downloading metadata: {repo} ({revision[:8]})...', flush=True)
    snapshot_download(repo_id=repo, revision=revision, local_dir=str(target), allow_patterns=metadata)
    missing = [name for name in metadata if not (target / name).is_file()]
    if missing:
        raise RuntimeError(f'{repo}: missing required files: {missing}')
    if not weights.is_file() or digest(weights) != sha256:
        temporary = target / (weight + '.download')
        print(f'Downloading weights: {repo}; this may take several minutes...', flush=True)
        parts = []
        try:
            url = f'https://huggingface.co/{repo}/resolve/{revision}/{weight}'
            if folder == 'qwen-chat':
                parts = download_resumable(url, temporary, 3087467144)
            else:
                request = urllib.request.Request(url, headers={'User-Agent':'TechZone-AI-Setup/1.0'})
                with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
                    downloaded = 0
                    next_report = 128 * 1024 * 1024
                    while block := response.read(1024 * 1024):
                        output.write(block)
                        downloaded += len(block)
                        if downloaded >= next_report:
                            print(f'  {downloaded // (1024 * 1024)} MB downloaded', flush=True)
                            next_report += 128 * 1024 * 1024
            if digest(temporary) != sha256:
                for part in parts: part.unlink(missing_ok=True)
                raise RuntimeError(f'{repo}: SHA-256 mismatch')
            temporary.replace(weights)
            for part in parts: part.unlink(missing_ok=True)
            if folder == 'qwen-chat':
                parts_dir=target / '.chat-download-parts'
                for leftover in parts_dir.glob('*.tmp'): leftover.unlink(missing_ok=True)
                try: parts_dir.rmdir()
                except OSError: pass
        finally:
            if folder != 'qwen-chat' or (temporary.is_file() and temporary.stat().st_size >= 3087467144):
                temporary.unlink(missing_ok=True)
    marker.write_text(revision + '\n', encoding='ascii')
    print(f'{repo} installed and verified.', flush=True)
print('Model setup complete.', flush=True)
