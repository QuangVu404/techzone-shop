"""Fetch public GearVN product offers, never modify TechZone prices or inventory."""
import datetime
from contextlib import closing
import json
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
import urllib.robotparser
from html.parser import HTMLParser

BASE = 'https://gearvn.com'
AGENT = 'TechZone-Demo/1.0'
# Explicit matches only. The catalogue's other variants are intentionally not guessed.
MATCHES = (
    ('PCP-yXmmP6', 'amd-ryzen-5-7600', 'CPU-AMD-RYZ-5-7600', 'AMD Ryzen 5 7600'),
    ('PCP-66C48d', 'amd-ryzen-5-7600x', 'CPU-AMD-RYZ-5-7600X', 'AMD Ryzen 5 7600X'),
    ('CPU-14600K', 'intel-core-i5-14600k', 'CPU-INT-I5-14600K', 'Intel Core i5-14600K'),
    ('SSD-990', 'o-cung-ssd-samsung-990-evo-plus-mz-v9s1t0bw', 'SSD-SAM-990-EVO-PLUS-M2-NVME-1TB', 'Samsung 990 EVO Plus 1TB NVMe'),
)

class ProductScripts(HTMLParser):
    def __init__(self):
        super().__init__(); self.capture = False; self.text = ''; self.objects = []
    def handle_starttag(self, tag, attrs):
        if tag == 'script' and dict(attrs).get('type', '').lower() == 'application/ld+json':
            self.capture = True; self.text = ''
    def handle_data(self, data):
        if self.capture: self.text += data
    def handle_endtag(self, tag):
        if tag == 'script' and self.capture:
            self.capture = False
            try: self.objects.append(json.loads(self.text))
            except (ValueError, TypeError): pass

class SameHost(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        if parsed.scheme != 'https' or parsed.hostname != 'gearvn.com':
            raise ValueError('Chuyển hướng ngoài GearVN')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def read_url(url):
    opener = urllib.request.build_opener(SameHost())
    with opener.open(urllib.request.Request(url, headers={'User-Agent': AGENT, 'Accept': 'text/html'}), timeout=12) as r:
        data = r.read(2_000_001)
        if len(data) > 2_000_000: raise ValueError('Trang quá lớn')
        return data.decode('utf-8')

def parse_offer(html, url, sku):
    parser = ProductScripts(); parser.feed(html)
    candidates = [x for x in parser.objects if isinstance(x, dict) and x.get('@type') == 'Product' and x.get('sku') == sku]
    if len(candidates) != 1: raise ValueError('Không xác minh được mã sản phẩm')
    offer = candidates[0].get('offers')
    if not isinstance(offer, dict) or offer.get('url') != url or offer.get('priceCurrency') != 'VND':
        raise ValueError('Không xác minh được báo giá VND')
    raw_price = offer.get('price')
    if isinstance(raw_price, bool): raise ValueError('Giá không hợp lệ')
    try: price = int(str(raw_price))
    except (ValueError, TypeError): raise ValueError('Giá không hợp lệ')
    if price <= 0 or price > 1_000_000_000: raise ValueError('Giá không hợp lệ')
    availability = {'https://schema.org/InStock': 'IN_STOCK', 'https://schema.org/OutOfStock': 'OUT_OF_STOCK'}.get(offer.get('availability'), 'UNKNOWN')
    return price, availability

def sync(path):
    robots = urllib.robotparser.RobotFileParser()
    robots.parse(read_url(BASE + '/robots.txt').splitlines())
    updated, errors = 0, []
    with closing(sqlite3.connect(path, timeout=15)) as db:
        db.execute('PRAGMA busy_timeout=15000')
        for product, slug, sku, expected_name in MATCHES:
            url = BASE + '/products/' + slug
            try:
                if not robots.can_fetch(AGENT, url): raise ValueError('robots.txt không cho phép truy cập')
                row = db.execute('SELECT v.variant_name FROM product_variants v JOIN products p ON p.product_id=v.product_id WHERE v.variant_id=? AND v.is_active=1 AND p.is_active=1', (product,)).fetchone()
                if not row or row[0] != expected_name: raise ValueError('Danh mục nội bộ không khớp')
                price, availability = parse_offer(read_url(url), url, sku)
                now = datetime.datetime.now(datetime.timezone.utc).isoformat()
                db.execute('INSERT INTO market_offers(variant_id,source_sku,source_url,price,availability,fetched_at) VALUES(?,?,?,?,?,?) ON CONFLICT(variant_id) DO UPDATE SET source_sku=excluded.source_sku,source_url=excluded.source_url,price=excluded.price,availability=excluded.availability,fetched_at=excluded.fetched_at', (product,sku,url,price,availability,now))
                db.commit(); updated += 1
            except Exception as ex:
                errors.append(product + ': ' + str(ex)[:180])
            time.sleep(1)
    return {'updated': updated, 'total': len(MATCHES), 'errors': errors}

if __name__ == '__main__':
    try:
        print(json.dumps(sync(sys.argv[1]), ensure_ascii=False))
    except Exception as ex:
        print(json.dumps({'updated': 0, 'total': len(MATCHES), 'errors': [str(ex)[:180]]}, ensure_ascii=False))
