import importlib.util
from contextlib import closing
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('gearvn', Path('ai/gearvn_offers.py'))
gearvn = importlib.util.module_from_spec(spec); spec.loader.exec_module(gearvn)

def page(sku, url, price=5790000, availability='https://schema.org/InStock'):
    data={'@type':'Product','sku':sku,'offers':{'url':url,'price':price,'priceCurrency':'VND','availability':availability}}
    return '<script type="application/ld+json">'+json.dumps(data)+'</script>'

class Offers(unittest.TestCase):
    def test_valid_and_reject_wrong_variant(self):
        url=gearvn.BASE+'/products/amd-ryzen-5-7600'; sku='CPU-AMD-RYZ-5-7600'
        self.assertEqual(gearvn.parse_offer(page(sku,url),url,sku),(5790000,'IN_STOCK'))
        with self.assertRaises(ValueError): gearvn.parse_offer(page(sku+'X',url),url,sku)
        with self.assertRaises(ValueError): gearvn.parse_offer(page(sku,url,False),url,sku)
        self.assertEqual(gearvn.parse_offer(page(sku,url,1,'https://schema.org/PreOrder'),url,sku)[1],'UNKNOWN')

    def test_partial_failure_preserves_cached_quote_and_stock(self):
        with tempfile.TemporaryDirectory() as directory:
            path=str(Path(directory)/'test.sqlite')
            with closing(sqlite3.connect(path)) as db:
                db.executescript('CREATE TABLE products(id TEXT PRIMARY KEY,name TEXT,active INTEGER); CREATE TABLE inventory(product_id TEXT,available INTEGER); CREATE TABLE market_offers(product_id TEXT PRIMARY KEY,source_sku TEXT,source_url TEXT,price INTEGER,availability TEXT,fetched_at TEXT);')
                for product,slug,sku,name in gearvn.MATCHES: db.execute('INSERT INTO products VALUES(?,?,1)',(product,name))
                db.execute('INSERT INTO inventory VALUES(?,?)',('PCP-yXmmP6',17))
                db.execute('INSERT INTO market_offers VALUES(?,?,?,?,?,?)',('PCP-66C48d','old','old',123,'UNKNOWN','2020')); db.commit()
            def fetch(url):
                if url.endswith('/robots.txt'): return 'User-agent: *\nAllow: /\n'
                if url.endswith('amd-ryzen-5-7600'): return page('CPU-AMD-RYZ-5-7600',url)
                raise OSError('offline')
            with patch.object(gearvn,'read_url',side_effect=fetch),patch.object(gearvn.time,'sleep'):
                result=gearvn.sync(path)
            self.assertEqual(result['updated'],1); self.assertEqual(len(result['errors']),3)
            with closing(sqlite3.connect(path)) as db:
                self.assertEqual(db.execute('SELECT available FROM inventory').fetchone()[0],17)
                self.assertEqual(db.execute("SELECT price FROM market_offers WHERE product_id='PCP-66C48d'").fetchone()[0],123)

    def test_normalized_variant_is_matched_without_using_family_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path=str(Path(directory)/'test.sqlite')
            with closing(sqlite3.connect(path)) as db:
                db.executescript('CREATE TABLE products(id TEXT PRIMARY KEY,name TEXT,active INTEGER); CREATE TABLE product_variants(id TEXT PRIMARY KEY,product_id TEXT,variant_name TEXT,active INTEGER); CREATE TABLE market_offers(product_id TEXT PRIMARY KEY,source_sku TEXT,source_url TEXT,price INTEGER,availability TEXT,fetched_at TEXT);')
                db.execute('INSERT INTO products VALUES(?,?,1)',('FAMILY-7600','Ryzen family'))
                db.execute('INSERT INTO product_variants VALUES(?,?,?,1)',('PCP-yXmmP6','FAMILY-7600','AMD Ryzen 5 7600'));db.commit()
            def fetch(url):
                if url.endswith('/robots.txt'): return 'User-agent: *\nAllow: /\n'
                return page('CPU-AMD-RYZ-5-7600',url)
            with patch.object(gearvn,'MATCHES',gearvn.MATCHES[:1]),patch.object(gearvn,'read_url',side_effect=fetch),patch.object(gearvn.time,'sleep'):
                result=gearvn.sync(path)
            self.assertEqual(result['updated'],1)
            with closing(sqlite3.connect(path)) as db:
                self.assertEqual(db.execute('SELECT product_id FROM market_offers').fetchone()[0],'PCP-yXmmP6')

if __name__=='__main__': unittest.main()
