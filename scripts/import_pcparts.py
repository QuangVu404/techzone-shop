"""Build a pinned, offline demo catalogue from the public PCPartPicker dataset.

Run after downloading the pinned JSONL into target/pcparts-source.jsonl.
The export price is a fixed demo conversion, not a Vietnamese retail quote.
"""
import base64
import concurrent.futures
import io
import json
import re
import urllib.request
from collections import Counter
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'target/pcparts-source.jsonl'
OUT = ROOT / 'src/main/resources/vn/techzone/catalog'
CATEGORIES = {'cpu': 'CPU', 'motherboard': 'Mainboard', 'memory': 'RAM',
              'video-card': 'GPU', 'power-supply': 'PSU', 'internal-hard-drive': 'SSD',
              'cpu-cooler': 'Tản nhiệt', 'case': 'Thùng máy'}
ORIGINAL_NAMES = ['Intel Core i5-14600K', 'AMD Ryzen 7 7800X3D', 'Intel Core i7-14700K',
                  'Gigabyte B760M AORUS ELITE AX DDR5', 'MSI PRO B760M-P DDR4',
                  'MSI MAG B650M MORTAR WIFI', 'Kingston Fury Beast 32GB DDR5 6000',
                  'Corsair Vengeance LPX 16GB DDR4 3200', 'ASUS Dual GeForce RTX 4070 SUPER 12GB',
                  'MSI GeForce RTX 4060 VENTUS 2X 8GB', 'Corsair RM750e 750W 80+ Gold',
                  'Cooler Master MWE 550 Bronze V2', 'Samsung 990 EVO Plus 1TB NVMe']

def norm(value):
    return re.sub(r'[^a-z0-9]', '', value.lower())

def photo(item):
    try:
        destination = OUT / ('PCP-' + item['product_tag'] + '.png')
        if destination.is_file():
            with Image.open(destination) as existing:
                existing.verify()
            return item
        req = urllib.request.Request(item['image_url'], headers={'User-Agent': 'TechZone-demo/1.0'})
        with urllib.request.urlopen(req, timeout=25) as response:
            content = response.read(4_000_001)
        if len(content) > 4_000_000:
            return None
        image = Image.open(io.BytesIO(content))
        image.load()
        image = image.convert('RGB')
        image.thumbnail((512, 512))
        image.save(destination, optimize=True)
        return item
    except Exception:
        return None

def encoded(value):
    return base64.b64encode(str(value).encode('utf-8')).decode('ascii')

OUT.mkdir(parents=True, exist_ok=True)
rows = [json.loads(line) for line in SOURCE.open(encoding='utf-8')]
rows.sort(key=lambda x: (-int(x.get('rating_count') or 0), str(x.get('source_id', ''))))
seen, chosen, counts = {norm(x) for x in ORIGINAL_NAMES}, [], Counter()
for row in rows:
    cat, name = row.get('category'), row.get('name', '').strip()
    tag = row.get('product_tag', '')
    if (cat not in CATEGORIES or counts[cat] >= 30 or not name or not row.get('brand')
            or not row.get('specs') or not isinstance(row.get('price_eur'), (int, float))
            or row['price_eur'] <= 0 or not re.fullmatch(r'[A-Za-z0-9]+', tag)
            or not row.get('image_url', '').startswith('https://cdna.pcpartpicker.com/')
            or not row.get('url', '').startswith('https://fr.pcpartpicker.com/product/')
            or norm(name) in seen):
        continue
    if cat == 'internal-hard-drive' and 'ssd' not in name.lower() and 'ssd' not in str(row['specs']).lower():
        continue
    seen.add(norm(name)); counts[cat] += 1; chosen.append(row)

with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    available = list(pool.map(photo, chosen))
counts = Counter()
with (OUT / 'real-products.tsv').open('w', encoding='utf-8', newline='\n') as out:
    for row in available:
        if not row or counts[row['category']] >= 25:
            continue
        counts[row['category']] += 1
        pairs = dict(row['specs'])
        # Only map compatibility fields that are explicitly supplied by the source.
        if row['category'] == 'motherboard' and pairs.get('Socket / CPU'):
            pairs['socket'] = pairs['Socket / CPU']
        if row['category'] == 'memory':
            match = re.search(r'DDR[345]', pairs.get('Speed', ''), re.I)
            if match:
                pairs['memoryType'] = match.group().upper()
        if row['category'] == 'power-supply' and pairs.get('Wattage'):
            pairs['wattage'] = pairs['Wattage']
        pairs.update({'Nguồn sản phẩm': row['url'], 'Ảnh nguồn': row['image_url'],
                      'Giá nguồn (EUR)': str(row['price_eur']),
                      'Giá bán': 'Ước tính demo, quy đổi 30.000 VNĐ/EUR'})
        fields = ['PCP-' + row['product_tag'], row['name'], CATEGORIES[row['category']],
                  row['brand'], round(row['price_eur'] * 30000),
                  'Thông số và hình ảnh theo danh mục PCPartPicker; giá và tồn kho chỉ để demo.']
        for key, value in pairs.items():
            if value is not None and isinstance(value, (str, int, float)):
                fields.extend([key, value])
        out.write('\t'.join(map(encoded, fields)) + '\n')
print('Imported', sum(counts.values()), 'products:', dict(counts))
if sum(counts.values()) < 200:
    raise SystemExit('Not enough valid products with images')
