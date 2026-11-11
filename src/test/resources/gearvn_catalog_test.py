import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('gearvn_catalog', Path('ai/gearvn_catalog.py'))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class Robots:
    def can_fetch(self, agent, url):
        return True


class CatalogueCrawler(unittest.TestCase):
    def test_product_metadata_and_canonical(self):
        parser = catalog.ProductDetails()
        parser.feed('<meta property="og:description" content="Chi tiết &amp; thông số">'
                    '<link rel="canonical" href="https://gearvn.com/products/test-1">')
        self.assertEqual(parser.description, 'Chi tiết & thông số')
        self.assertEqual(parser.canonical, 'https://gearvn.com/products/test-1')

    def test_structured_specs_preserve_source_values(self):
        flight = json.dumps([1, json.dumps({'specs_table': [{'title': 'CPU', 'rows': [
            {'label': 'Socket', 'value': 'LGA 1700'}, {'label': 'Bộ nhớ', 'value': 'DDR4, DDR5'}]}]}, ensure_ascii=False)], ensure_ascii=False)
        ld = json.dumps({'@type': 'Product', 'additionalProperty': [
            {'@type': 'PropertyValue', 'name': 'Socket', 'value': 'LGA 1700'}]}, ensure_ascii=False)
        parser = catalog.ProductDetails()
        parser.feed('<script type="application/ld+json">'+ld+'</script>'
                    '<script>self.__next_f.push('+flight+')</script>')
        parser.close()
        self.assertEqual(parser.specifications['Thông số · Socket'], 'LGA 1700')
        self.assertEqual(parser.specifications['Thông số · Bộ nhớ'], 'DDR4, DDR5')
        self.assertEqual(len(parser.specifications), 2)

    def test_product_headings_are_preserved_separately(self):
        parser = catalog.ProductDetails()
        parser.feed('<h2 id="section-1"><strong>1000W công suất liên tục</strong></h2>')
        parser.close()
        self.assertEqual(parser.features, ['1000W công suất liên tục'])
        self.assertEqual(parser.specifications, {})

    def test_category_brand_and_source_constraints(self):
        self.assertEqual(catalog.category('Tản nhiệt AIO TRYX Panorama 360'), 'Tản nhiệt')
        self.assertEqual(catalog.category('Vỏ máy tính Corsair FRAME 4000D'), 'Case')
        self.assertEqual(catalog.category('Nguồn ASUS ROG THOR 3000T3'), 'PSU')
        self.assertEqual(catalog.brand('Vỏ máy tính Gamdias ATLAS M4'), 'Gamdias')
        self.assertEqual(catalog.brand('Ram Apacer Panther 16GB'), 'Apacer')
        self.assertEqual(catalog.brand('Nguồn máy tính FSP VIC WD 650W'), 'FSP')
        specs={'Thông số · Socket':'LGA 1700','Thông số · Kiểu RAM hỗ trợ':'DDR5'}
        catalog.normalize_source_facts('Mainboard test DDR5','Mainboard',specs)
        self.assertEqual((specs['socket'],specs['memoryType']),('LGA1700','DDR5'))
        cpu={'Thông số · Hỗ trợ loại RAM':'DDR4, DDR5'}
        catalog.normalize_source_facts('CPU test','CPU',cpu)
        self.assertNotIn('memoryType',cpu)
        bad={'Thông số · Công suất':'1200 w','Thông số · Chuẩn chứng nhận':'80 Plus Platinum'}
        catalog.normalize_source_facts('Nguồn test 850W 80 Plus Gold','PSU',bad)
        self.assertEqual(bad['sourceQualityStatus'],'REVIEW')
        self.assertIn('không khớp',bad['sourceQualityWarnings'])
        self.assertNotIn('Thông số · Công suất',bad)
        self.assertEqual(bad['sourceConflicting.Thông số · Công suất'],'1200 w')
        catalog.normalize_source_facts('Nguồn test 850W 80 Plus Gold','PSU',bad)
        self.assertEqual(bad['sourceConflicting.Thông số · Công suất'],'1200 w')

    def test_incomplete_listing_is_rejected(self):
        html = ('<a class="product-card" href="/products/test-1">'
                '<img src="https://cdn.hstatic.net/test.jpg">'
                '<span class="product-card-name">CPU Test</span>'
                '<span class="product-card-price-sale">1.000.000 đ</span></a>')
        with patch.object(catalog, 'read_robots', return_value=Robots()), \
             patch.object(catalog, 'fetch', return_value=html), \
             patch.object(catalog.time, 'sleep'):
            with self.assertRaisesRegex(ValueError, 'unique products'):
                catalog.crawl(50, 0.5)


if __name__ == '__main__':
    unittest.main()
