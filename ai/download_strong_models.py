"""Install pinned Hugging Face checkpoints and verify every weight shard."""
import hashlib
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / 'models'
os.environ.setdefault('HF_HOME', str(MODELS / '.hf-cache'))
os.environ.setdefault('HF_HUB_DISABLE_XET', '1')
os.environ.setdefault('HF_HUB_DOWNLOAD_TIMEOUT', '60')
from huggingface_hub import snapshot_download
CHECKPOINTS = {
    'qwen3-chat': ('Qwen/Qwen3-4B-Instruct-2507', 'cdbee75f17c01a7cc42f958dc650907174af0554',
        ['config.json','generation_config.json','tokenizer.json','tokenizer_config.json','vocab.json','merges.txt','model.safetensors.index.json'], {
            'model-00001-of-00003.safetensors':'75311d91bb08cf0b882913da464a1e722a31fb44db35208663487efb7a3d8ed6',
            'model-00002-of-00003.safetensors':'0b48adbb1f60e901153d91907ba11ce63bd4b8b584482e730f48808d055dfba1',
            'model-00003-of-00003.safetensors':'7dd39ccca5e4de123c74c14af44c9bf2eb75df33b4614382af0134528e060d5d'}),
    'siglip2-base': ('google/siglip2-base-patch16-224', '75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2',
        ['config.json','preprocessor_config.json'], {
            'model.safetensors':'612923381c76ec5a9bed335d1c48827e3f2e506ac31b044b63b2031fadee6a0b'}),
    'whisper-medium': ('openai/whisper-medium', 'abdf7c39ab9d0397620ccaea8974cc764cd0953e',
        ['config.json','generation_config.json','preprocessor_config.json','tokenizer.json','tokenizer_config.json','vocab.json','merges.txt','normalizer.json','special_tokens_map.json','added_tokens.json'], {
            'model.safetensors':'62f73550fa6db24b0c6f6c5962bd0dae80fa644e93cde9cd9c3792971b47fd28'}),
}

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def main():
    selected = os.environ.get('TECHZONE_STRONG_MODEL', '').strip()
    if selected and selected not in CHECKPOINTS:
        raise ValueError('Unknown TECHZONE_STRONG_MODEL: ' + selected)
    for folder, (repo, revision, metadata, weights) in CHECKPOINTS.items():
        if selected and selected != folder:
            continue
        target = MODELS / folder
        target.mkdir(parents=True, exist_ok=True)
        marker = target / '.techzone-ready'
        if marker.is_file() and marker.read_text(encoding='ascii').strip() == revision and all((target/f).is_file() for f in metadata+list(weights)):
            print(f'{repo}: already installed', flush=True)
            continue
        marker.unlink(missing_ok=True)
        print(f'Downloading {repo} at {revision}; this can take a while.', flush=True)
        snapshot_download(repo_id=repo, revision=revision, local_dir=str(target), allow_patterns=metadata+list(weights))
        for filename in metadata:
            if not (target/filename).is_file():
                raise RuntimeError(f'{repo}: missing {filename}')
        for filename, expected in weights.items():
            if not (target/filename).is_file() or digest(target/filename) != expected:
                raise RuntimeError(f'{repo}: SHA-256 mismatch for {filename}')
        marker.write_text(revision+'\n', encoding='ascii')
        print(f'{repo}: verified and ready', flush=True)

if __name__ == '__main__':
    main()
