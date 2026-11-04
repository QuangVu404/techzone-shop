"""Local Qwen dialogue. Catalogue facts and compatibility are controlled by Java."""
import base64
import os
import re
import sys
from pathlib import Path
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

ROOT=Path(__file__).resolve().parent.parent
MODEL=ROOT/'models'/'qwen-chat'
REVISION='989aa7980e4cf806f80c7fef2b1adb7bc71aa306'
if not (MODEL/'.techzone-ready').is_file() or (MODEL/'.techzone-ready').read_text(encoding='ascii').strip()!=REVISION:
    raise RuntimeError('Model missing: run setup-chat-ai.bat')
torch.set_num_threads(min(8,os.cpu_count() or 1))
tokenizer=AutoTokenizer.from_pretrained(MODEL,local_files_only=True)
model=AutoModelForCausalLM.from_pretrained(MODEL,local_files_only=True,torch_dtype=torch.float32).eval()
print('READY\tQwen2.5-1.5B-Instruct',flush=True)

SHOP_SYSTEM=('You are a friendly Vietnamese technology shopping assistant. Answer in natural Vietnamese, '
             'one or two short sentences. The application will show verified catalogue results separately. '
             'Do not name products or brands, prices, quantities or technical specifications. '
             'Never claim an order or cart was changed. Do not claim compatibility. '
             'Avoid numbers. When the application has already found products, do not ask for more details. '
             'When information is missing, ask a clarifying question.')
QUESTION_SYSTEM=('You are a Vietnamese technology assistant. Answer the user in natural Vietnamese, '
                 'in one or two short sentences. State the most useful point first. Never use a numbered list or introduction. '
                 'If unsure, say you are unsure. '
                 'Do not invent this shop\'s prices, stock, warranty, order status or compatibility. '
                 'Do not claim that any transaction was performed.')

for line in sys.stdin:
    try:
        fields=line.rstrip('\r\n').split('\t')
        if len(fields)!=2 or fields[0] not in ('shopping','question'): raise ValueError('Invalid request')
        question=base64.b64decode(fields[1],validate=True).decode('utf-8')[:2500]
        if fields[0]=='question': question+='\nTrả lời trực tiếp, ngắn gọn trong một hoặc hai câu, không liệt kê.'
        messages=[{'role':'system','content':QUESTION_SYSTEM if fields[0]=='question' else SHOP_SYSTEM},
                  {'role':'user','content':question}]
        text=tokenizer.apply_chat_template(messages,tokenize=False,add_generation_prompt=True)
        inputs=tokenizer(text,return_tensors='pt',truncation=True,max_length=1536)
        with torch.inference_mode():
            result=model.generate(**inputs,max_new_tokens=100,do_sample=False,pad_token_id=tokenizer.eos_token_id)
        generated=result[0,inputs['input_ids'].shape[1]:]
        answer=tokenizer.decode(generated,skip_special_tokens=True).strip()
        clipped=len(generated)>=100 and not re.search(r'[.!?…。]\s*$',answer)
        if clipped:
            endings=list(re.finditer(r'(?<=[^\d\s])[.!?。](?=\s|$)',answer))
            answer=answer[:endings[-1].end()] if endings else answer+'…'
            if fields[0]=='question':
                bullets=re.findall(r'(?m)^\s*(?:[-*•]|\d+[.)])\s+(.+)$',answer)
                if bullets: answer=bullets[0].strip()
        print('OK\t'+base64.b64encode(answer.encode('utf-8')).decode('ascii'),flush=True)
    except Exception as exc:
        print('ERROR\t'+str(exc).replace('\n',' ')[:200],flush=True)
