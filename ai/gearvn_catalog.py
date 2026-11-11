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
          "Acer", "Dell", "BenQ", "HP", "Apple", "Xiaomi", "Antec")


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
    rules = (
        ("CPU", ("cpu", "bộ vi xử lý", "processor", "ryzen", "core ultra")),
        ("Mainboard", ("mainboard", "bo mạch chủ", "motherboard")),
        ("RAM", ("ram", "bộ nhớ")),
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
    return "Linh kiện"


def brand(name):
    folded = name.casefold()
    for value in BRANDS:
        if value.casefold() in folded:
            return value.upper() if value == "GIGABYTE" else value
    return "Khác"


def crawl(limit, delay):
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
    rows = []
    for item in list(found.values())[:limit]:
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
        values = [product_id, safe_name, category(safe_name), brand(safe_name),
                  item["summary"] or safe_name, str(regular), str(sale),
                  item["slug"], item["availability"], item["url"], item["image"], stamp]
        for key, value in specs.items():
            values.extend((key, value))
        rows.append("\t".join(base64.b64encode(v.encode("utf-8")).decode("ascii") for v in values))
    if len(rows) < min(limit, 50):
        raise ValueError(f"Only {len(rows)} priced product records passed validation; keeping the existing catalogue.")
    return rows, stamp


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--output", required=True)
    cli.add_argument("--limit", type=int, default=200)
    cli.add_argument("--delay", type=float, default=1.0)
    args = cli.parse_args()
    if not 50 <= args.limit <= MAX_PRODUCTS:
        raise ValueError(f"limit must be between 50 and {MAX_PRODUCTS}.")
    if not 0.5 <= args.delay <= 10:
        raise ValueError("delay must be between 0.5 and 10 seconds.")
    rows, stamp = crawl(args.limit, args.delay)
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
