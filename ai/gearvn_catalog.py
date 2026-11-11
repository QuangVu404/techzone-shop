"""Crawl the public GearVN PC-parts listing pages into a small TSV snapshot.

This deliberately reads storefront HTML, checks robots.txt, stays on gearvn.com,
and waits between category-page requests. It does not call GearVN's /api/ paths.
"""
import argparse
import base64
import datetime as dt
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.parse
import urllib.error
import urllib.request
import urllib.robotparser

BASE = "https://gearvn.com"
COLLECTION = "/collections/linh-kien-may-tinh"
AGENT = "TechZone-Demo/1.0"
PAGE_BYTES_LIMIT = 2_000_000
MAX_PRODUCTS = 500
VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input",
             "link", "meta", "param", "source", "track", "wbr"}
BRANDS = ("AMD", "Intel", "ASUS", "MSI", "Gigabyte", "GIGABYTE", "Corsair",
          "Kingston", "Samsung", "Western Digital", "WD", "Seagate", "NZXT",
          "Lian Li", "Cooler Master", "Thermalright", "SSTC", "Zotac",
          "PowerColor", "Sapphire", "TeamGroup", "Crucial", "Lexar", "FSP",
          "Super Flower", "Logitech", "Razer", "AOC", "LG", "ViewSonic",
          "Acer", "Dell", "BenQ", "HP", "Apple", "Xiaomi", "Antec",
          "Apacer", "Gamdias", "Xigmatek", "Jonsbo", "TRYX", "Deepcool",
          "KingSpec", "Kingmax", "Patriot", "ADATA", "Leadtek", "Jetek", "Klevv", "G.Skill")


class SameHostRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        if parsed.scheme != "https" or parsed.hostname != "gearvn.com":
            raise ValueError("Redirect outside the public GearVN storefront was blocked.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class ProductCards(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.card = None
        self.stack = []
        self.capture = None
        self.spec_depth = 0
        self.results = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = set((attrs.get("class") or "").split())
        if self.card is None:
            href = attrs.get("href", "")
            if tag != "a" or "product-card" not in classes or not href.startswith("/products/"):
                return
            slug = href.removeprefix("/products/").split("?", 1)[0].strip("/")
            if not re.fullmatch(r"[a-z0-9-]{2,180}", slug):
                return
            self.card = {"slug": slug, "url": BASE + "/products/" + slug,
                         "name": "", "image": "", "original": "", "sale": "",
                         "availability": "UNKNOWN", "summary": []}
            self.stack = ["a"]
            return

        if tag == "img":
            image = attrs.get("src") or attrs.get("data-src") or ""
            if image.startswith("https://cdn.hstatic.net/") and not self.card["image"]:
                self.card["image"] = image
        if "product-card-name" in classes:
            self.capture = ("name", tag, [])
        elif "product-card-price-original" in classes:
            self.capture = ("original", tag, [])
        elif "product-card-price-sale" in classes:
            self.capture = ("sale", tag, [])
        if attrs.get("data-slot") == "product-specs":
            self.spec_depth = 1
        elif self.spec_depth:
            self.spec_depth += 1
        if tag not in VOID_TAGS:
            self.stack.append(tag)

    def handle_data(self, data):
        if self.card is None:
            return
        text = " ".join(data.split())
        if not text:
            return
        if self.capture is not None:
            self.capture[2].append(text)
        if self.spec_depth:
            self.card["summary"].append(text)
        folded = text.casefold()
        if "còn hàng" in folded:
            self.card["availability"] = "IN_STOCK"
        elif "hết hàng" in folded or "tạm hết" in folded:
            self.card["availability"] = "OUT_OF_STOCK"

    def handle_endtag(self, tag):
        if self.card is None:
            return
        if self.capture is not None and tag == self.capture[1]:
            self.card[self.capture[0]] = " ".join(self.capture[2]).strip()
            self.capture = None
        if self.spec_depth:
            self.spec_depth -= 1
        if tag in self.stack:
            while self.stack:
                current = self.stack.pop()
                if current == tag:
                    break
        if tag == "a" and not self.stack:
            self._finish_card()

    def close(self):
        super().close()
        if self.card is not None:
            self._finish_card()

    def _finish_card(self):
        self.card["summary"] = " ".join(self.card["summary"])[:1200]
        if self.card["name"]:
            self.results.append(self.card)
        self.card = None
        self.stack = []
        self.capture = None
        self.spec_depth = 0


class ProductDetails(HTMLParser):
    """Extract public page metadata without inferring technical compatibility."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.description = ''
        self.title = ''
        self.canonical = ''
        self.specifications = {}
        self._script = None
        self._script_type = ''
        self._flight = []
        self.features = []
        self._heading = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'script':
            self._script = []
            self._script_type = (attrs.get('type') or '').lower()
        elif tag in ('h2', 'h3') and re.fullmatch(r'section-[1-9]\d*', attrs.get('id') or ''):
            self._heading = (tag, [])
        elif tag == 'meta':
            key = (attrs.get('property') or attrs.get('name') or '').lower()
            value = ' '.join((attrs.get('content') or '').split())
            if key in ('og:description', 'description') and len(value) > len(self.description):
                self.description = value[:4000]
            elif key == 'og:title':
                self.title = value[:300]
        elif tag == 'link' and 'canonical' in (attrs.get('rel') or '').lower().split():
            self.canonical = attrs.get('href') or ''

    def handle_data(self, data):
        if self._script is not None:
            self._script.append(data)
        if self._heading is not None:
            self._heading[1].append(data)

    def _add_spec(self, label, value, group=''):
        if not isinstance(label, str) or not isinstance(value, (str, int, float)):
            return
        label = ' '.join(label.split())[:120]
        value = ' '.join(str(value).split())[:2000]
        if not label or not value:
            return
        key = 'Thông số · ' + label
        if key in self.specifications and self.specifications[key] != value:
            key = 'Thông số · ' + (' '.join(group.split())[:80] or 'Khác') + ' · ' + label
        self.specifications.setdefault(key, value)

    def _properties(self, node):
        if isinstance(node, list):
            for value in node:
                self._properties(value)
        elif isinstance(node, dict):
            if node.get('@type') == 'Product':
                for value in node.get('additionalProperty', []):
                    if isinstance(value, dict):
                        self._add_spec(value.get('name'), value.get('value'))
            for value in node.values():
                if isinstance(value, (list, dict)):
                    self._properties(value)

    def handle_endtag(self, tag):
        if self._heading is not None and tag == self._heading[0]:
            heading = ' '.join(' '.join(self._heading[1]).split())
            if 15 <= len(heading) <= 220 and heading not in self.features and len(self.features) < 10:
                self.features.append(heading)
            self._heading = None
        if tag != 'script' or self._script is None:
            return
        body = ''.join(self._script)
        if self._script_type == 'application/ld+json':
            try:
                self._properties(json.loads(body))
            except (ValueError, TypeError):
                pass
        elif body.startswith('self.__next_f.push(') and body.endswith(')'):
            try:
                payload = json.loads(body[len('self.__next_f.push('):-1])
                if isinstance(payload, list) and len(payload) > 1 and isinstance(payload[1], str):
                    self._flight.append(payload[1])
            except (ValueError, TypeError):
                pass
        self._script = None

    def close(self):
        super().close()
        flight = ''.join(self._flight)
        tables = []
        for match in re.finditer(r'"specs_table"\s*:\s*', flight):
            try:
                groups, _ = json.JSONDecoder().raw_decode(flight[match.end():])
                if isinstance(groups, list):
                    tables.append(groups)
            except (ValueError, TypeError):
                continue
        if tables and all(table == tables[0] for table in tables):
            for group in tables[0]:
                if not isinstance(group, dict):
                    continue
                for row in group.get('rows', []):
                    if isinstance(row, dict):
                        self._add_spec(row.get('label'), row.get('value'), group.get('title') or '')


def fetch(url):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "gearvn.com":
        raise ValueError("Only public GearVN storefront pages may be crawled.")
    opener = urllib.request.build_opener(SameHostRedirect())
    req = urllib.request.Request(url, headers={
        "User-Agent": AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "vi,en;q=0.8",
    })
    with opener.open(req, timeout=20) as response:
        data = response.read(PAGE_BYTES_LIMIT + 1)
        if len(data) > PAGE_BYTES_LIMIT:
            raise ValueError("Storefront page exceeded the 2 MB safety limit.")
        return data.decode("utf-8", "replace")


def read_robots():
    parser = urllib.robotparser.RobotFileParser()
    parser.parse(fetch(BASE + "/robots.txt").splitlines())
    return parser


def money(text):
    digits = re.sub(r"\D", "", text or "")
    if not digits:
        return 0
    value = int(digits)
    return value if 0 < value <= 1_000_000_000 else 0


def category(name):
    n = name.casefold()
    # Prefer the type at the beginning of the listing. Model names such as
    # FRAME and Panorama contain "ram" but are not memory modules.
    if re.match(r'^\s*(?:tản nhiệt|quạt tản nhiệt)\b', n): return 'Tản nhiệt'
    if re.match(r'^\s*(?:vỏ máy tính|vỏ case|case máy tính)\b', n): return 'Case'
    if re.match(r'^\s*(?:nguồn|bộ nguồn)\b', n): return 'PSU'
    rules = (
        ("CPU", ("cpu", "bộ vi xử lý", "processor", "ryzen", "core ultra")),
        ("Mainboard", ("mainboard", "bo mạch chủ", "motherboard")),
        ("RAM", ("bộ nhớ",)),
        ("GPU", ("vga", "card màn hình", "card đồ họa", "geforce", "radeon")),
        ("SSD", ("ssd", "ổ cứng", "nvme", "hdd")),
        ("PSU", ("nguồn máy tính", "bộ nguồn", "power supply", "psu")),
        ("Case", ("vỏ máy", "case máy", "case pc")),
        ("Tản nhiệt", ("tản nhiệt", "cooler", "cooling")),
        ("Màn hình", ("màn hình", "monitor")),
        ("Laptop", ("laptop", "notebook")),
        ("PC", ("pc gvn", "máy bộ", "máy tính để bàn")),
    )
    for label, needles in rules:
        if any(term in n for term in needles):
            return label
    if re.search(r'(?<![\w])ram(?![\w])', n):
        return 'RAM'
    return "Linh kiện"


def brand(name):
    folded = name.casefold()
    candidates=[]
    for value in BRANDS:
        words=re.split(r'[\s.\-]+',value.casefold())
        pattern=r'(?<!\w)'+r'[\s.\-]*'.join(map(re.escape,words))+r'(?!\w)'
        match=re.search(pattern,folded)
        if match:
            candidates.append((match.start(),-len(match.group()),value))
    if candidates:
        value=min(candidates)[2]
        return value.upper() if value == 'GIGABYTE' else value
    if re.search(r'(?<!\w)rog\s+strix(?!\w)',folded): return 'ASUS'
    if re.search(r'(?<!\w)(?:gskill|g-skill)(?!\w)',folded): return 'G.Skill'
    return "Khác"


def normalize_source_facts(name, product_category, specs):
    """Promote only unambiguous source fields and retain contradictory evidence."""
    for key in ('socket', 'memoryType', 'sourceNormalized.socket', 'sourceNormalized.memoryType',
                'sourceQualityWarnings', 'sourceQualityStatus'):
        specs.pop(key, None)
    warnings=[]
    if re.search(r'\(\s*sai\s*\)',name,re.I):
        warnings.append('Tên sản phẩm tại nguồn được đánh dấu sai; cần xác minh mẫu hàng.')
    if product_category in ('CPU','Mainboard'):
        raw=specs.get('Thông số · Socket','')
        sockets={re.sub(r'\s+','',m.upper()) for m in re.findall(r'\b(?:LGA\s*\d{3,5}|AM\s*[345])\b',raw,re.I)}
        if len(sockets)==1:
            specs['socket']=sockets.pop()
            specs['sourceNormalized.socket']='Thông số · Socket'
    memory_field={'RAM':'Thông số · Loại RAM','Mainboard':'Thông số · Kiểu RAM hỗ trợ',
                  'CPU':'Thông số · Hỗ trợ loại RAM'}.get(product_category)
    if memory_field:
        raw=specs.get(memory_field,'')
        values={x.upper() for x in re.findall(r'\bDDR\s*[345]\b',raw,re.I)}
        values={re.sub(r'\s+','',x) for x in values}
        in_name={re.sub(r'\s+','',x.upper()) for x in re.findall(r'\bDDR\s*[345]\b',name,re.I)}
        if in_name and values and not in_name.issubset(values):
            warnings.append('Loại RAM trong tên và thông số nguồn không nhất quán.')
        elif len(values)==1:
            specs['memoryType']=next(iter(values))
            specs['sourceNormalized.memoryType']=memory_field
    if product_category=='PSU':
        for key in ('Thông số · Công suất','Thông số · Công suất tối đa','Thông số · Chuẩn chứng nhận'):
            prior=specs.get('sourceConflicting.'+key)
            if prior and key not in specs:
                specs[key]=prior
            specs.pop('sourceConflicting.'+key,None)
        declared={int(n) for n in re.findall(r'(?<!\d)(\d{3,4})\s*W\b',name,re.I)}
        model_power=re.search(r'\bV\s+Platinum\s+(\d{3,4})\b',name,re.I)
        if model_power and len(declared)==1 and int(model_power.group(1)) not in declared:
            warnings.append('Mã model và công suất ghi trong tên không nhất quán; cần xác minh model.')
        if len(declared)>1:
            warnings.append('Tên nguồn ghi nhiều mức công suất khác nhau; cần xác minh model.')
        for key in ('Thông số · Công suất','Thông số · Công suất tối đa'):
            value=specs.get(key,'')
            found=re.search(r'(?<!\d)(\d{3,4})\s*w\b',value,re.I)
            if len(declared)==1 and found and int(found.group(1)) not in declared:
                warnings.append(f'{key} ({value}) không khớp công suất trong tên sản phẩm.')
                specs['sourceConflicting.'+key]=value
                specs.pop(key,None)
        named=re.search(r'80\s*plus\s*(bronze|silver|gold|platinum|titanium|white)',name,re.I)
        listed=re.search(r'80\s*plus\s*(bronze|silver|gold|platinum|titanium|white)',specs.get('Thông số · Chuẩn chứng nhận',''),re.I)
        if named and listed and named.group(1).casefold()!=listed.group(1).casefold():
            warnings.append('Chứng nhận 80 Plus trong tên và thông số nguồn không nhất quán.')
            key='Thông số · Chuẩn chứng nhận'
            specs['sourceConflicting.'+key]=specs.pop(key)
    specs['sourceQualityStatus']='REVIEW' if warnings else 'NO_CONFLICT_DETECTED'
    if warnings:
        specs['sourceQualityWarnings']=' '.join(warnings)


def apply_verified_facts(url, specs):
    path=Path(__file__).with_name('verified_product_facts.json')
    if not path.is_file():
        return
    entry=json.loads(path.read_text(encoding='utf-8')).get(url)
    if not entry:
        return
    origin=entry.get('source','')
    if not origin.startswith(('https://sfcc.coolermaster.com/','https://estore.asus.com/','https://rog.asus.com/')):
        raise ValueError('Invalid manufacturer verification source')
    for label,value in entry.get('facts',{}).items():
        if isinstance(label,str) and isinstance(value,str) and label and value:
            key='Thông số · '+label
            specs[key]=value
            specs['sourceSpecUrl.'+key]=origin
    specs['sourceManufacturerModel']=entry.get('model','')


def crawl(limit, delay, details=False):
    robots = read_robots()
    stamp = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    found = {}
    pages = (limit + 19) // 20
    for page in range(1, pages + 1):
        url = f"{BASE}{COLLECTION}?page={page}"
        if not robots.can_fetch(AGENT, url):
            raise ValueError("robots.txt does not allow this collection page.")
        parser = ProductCards()
        parser.feed(fetch(url))
        parser.close()
        if not parser.results:
            raise ValueError(f"No public product cards were found on collection page {page}.")
        for item in parser.results:
            found.setdefault(item["slug"], item)
            if len(found) >= limit:
                break
        if len(found) >= limit:
            break
        if page < pages:
            time.sleep(delay)
    if len(found) < limit:
        raise ValueError(f"Only {len(found)} unique products were found; keeping the existing catalogue.")
    rows = []
    for position, item in enumerate(list(found.values())[:limit], 1):
        current = money(item["sale"]) or money(item["original"])
        regular = money(item["original"]) or current
        if not current or not item["image"]:
            continue
        sale = current if regular > current else 0
        product_id = "GVN-" + hashlib.sha256(item["slug"].encode()).hexdigest()[:16]
        safe_name = item["name"].strip()
        specs = {
            "sourceProvider": "GEARVN",
            "sourceUrl": item["url"],
            "sourceImageUrl": item["image"],
            "sourceFetchedAt": stamp,
            "sourceAvailability": item["availability"],
            "sourceCollection": BASE + COLLECTION,
            "sourceSlug": item["slug"],
        }
        if item["summary"]:
            specs["listingSummary"] = item["summary"]
        description = item["summary"] or safe_name
        if details:
            if not robots.can_fetch(AGENT, item["url"]):
                specs['sourceDetailStatus'] = 'ROBOTS_DISALLOWED'
                specs['sourceSpecStatus'] = 'ROBOTS_DISALLOWED'
            else:
                parser = None
                for attempt in range(2):
                    time.sleep(delay)
                    try:
                        parser = ProductDetails()
                        parser.feed(fetch(item["url"]))
                        parser.close()
                        break
                    except (OSError, ValueError, urllib.error.URLError):
                        parser = None
                canonical = parser.canonical.split('?', 1)[0].rstrip('/') if parser else ''
                if parser and (parser.description or parser.specifications) and (not canonical or canonical == item['url'].rstrip('/')):
                    if parser.description:
                        description = parser.description
                        specs['sourceDescription'] = parser.description
                    specs['sourceDetailFetchedAt'] = stamp
                    specs['sourceDetailStatus'] = 'OK'
                    specs.update(parser.specifications)
                    specs['sourceSpecStatus'] = 'OK' if parser.specifications else 'NOT_PUBLISHED'
                    specs['sourceSpecCount'] = str(len(parser.specifications))
                    if not parser.specifications:
                        for i, feature in enumerate(parser.features, 1):
                            specs[f'Đặc điểm · {i}'] = feature
                        specs['sourceFeatureStatus'] = 'OK' if parser.features else 'NOT_PUBLISHED'
                else:
                    specs['sourceDetailStatus'] = 'UNAVAILABLE'
                    specs['sourceSpecStatus'] = 'UNAVAILABLE'
        product_category=category(safe_name)
        normalize_source_facts(safe_name,product_category,specs)
        apply_verified_facts(item['url'],specs)
        source_brand=specs.get('Thông số · Thương hiệu','').strip()
        product_brand=brand(safe_name)
        if product_brand=='Khác' and source_brand and len(source_brand)<=80:
            product_brand=source_brand
        values = [product_id, safe_name, product_category, product_brand,
                  description, str(regular), str(sale),
                  item["slug"], item["availability"], item["url"], item["image"], stamp]
        for key, value in specs.items():
            values.extend((key, value))
        rows.append("\t".join(base64.b64encode(v.encode("utf-8")).decode("ascii") for v in values))
        if details and position % 20 == 0:
            print(f"Product pages processed: {position}/{limit}", file=sys.stderr, flush=True)
    if len(rows) != limit:
        raise ValueError(f"Only {len(rows)} priced product records passed validation; keeping the existing catalogue.")
    return rows, stamp


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--output", required=True)
    cli.add_argument("--limit", type=int, default=200)
    cli.add_argument("--delay", type=float, default=1.0)
    cli.add_argument("--details", action="store_true", help="fetch and validate every public product page")
    args = cli.parse_args()
    if not 50 <= args.limit <= MAX_PRODUCTS:
        raise ValueError(f"limit must be between 50 and {MAX_PRODUCTS}.")
    if not 0.5 <= args.delay <= 10:
        raise ValueError("delay must be between 0.5 and 10 seconds.")
    rows, stamp = crawl(args.limit, args.delay, args.details)
    target = Path(args.output).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix=".gearvn-catalog-", suffix=".tmp", dir=target.parent)
    try:
        with os.fdopen(handle, "w", encoding="ascii", newline="\n") as stream:
            stream.write("\n".join(rows) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(json.dumps({"records": len(rows), "fetchedAt": stamp,
                      "collection": BASE + COLLECTION}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error)[:400], file=sys.stderr)
        raise SystemExit(1)
