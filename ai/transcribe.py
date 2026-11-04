"""Transcribe a 16 kHz mono PCM WAV with local multilingual Whisper Small with a Tiny fallback."""
import os
import sys
import wave
from pathlib import Path
import numpy as np
import torch
from transformers import WhisperProcessor, WhisperForConditionalGeneration

ROOT = Path(__file__).resolve().parent.parent
STRONG = ROOT / 'models' / 'whisper-small'
REVISION = '973afd24965f72e36ca33b3055d56a652f456b4d'
marker = STRONG / '.techzone-ready'
USE_SMALL = marker.is_file() and marker.read_text(encoding='ascii').strip() == REVISION and (STRONG / 'model.safetensors').is_file()
MODEL = STRONG if USE_SMALL else ROOT / 'models' / 'whisper-tiny'

def main():
    if len(sys.argv) != 2:
        raise ValueError('Expected WAV file')
    if not (MODEL / 'model.safetensors').is_file():
        raise FileNotFoundError('Missing Whisper model; run setup-ai.bat')
    with wave.open(sys.argv[1], 'rb') as audio:
        if audio.getnchannels() != 1 or audio.getsampwidth() != 2 or audio.getframerate() != 16000:
            raise ValueError('Audio must be 16 kHz mono PCM')
        samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32) / 32768.0
    if len(samples) == 0 or np.sqrt(np.mean(samples ** 2)) < .002:
        raise ValueError('No speech detected')
    torch.set_num_threads(min(4, os.cpu_count() or 1))
    processor = WhisperProcessor.from_pretrained(MODEL, local_files_only=True)
    model = WhisperForConditionalGeneration.from_pretrained(MODEL, local_files_only=True)
    model.eval()
    inputs = processor(samples, sampling_rate=16000, return_tensors='pt', return_attention_mask=True)
    with torch.inference_mode():
        tokens = model.generate(input_features=inputs.input_features, attention_mask=inputs.attention_mask,
                                language='vi', task='transcribe', max_new_tokens=128)
    text = processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()
    if not text:
        raise ValueError('No speech detected')
    print('vi\t' + ' '.join(text.split()))

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
