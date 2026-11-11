import os, subprocess, tempfile, time, urllib.request, urllib.parse, urllib.error, json, shutil, re, sqlite3
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path
root=Path.cwd()
with tempfile.TemporaryDirectory(dir=root/'target') as tmp:
 test_classes=Path(tmp)/'classes'
 shutil.copytree(root/'target/classes',test_classes)
 with (test_classes/'web/extra.js').open('a',encoding='utf8') as f:
  f.write('\n'+(root/'src/test/resources/catalog_ui_probe.js').read_text(encoding='utf8'))
 cp=str(test_classes)+';'+str(root/'lib/*')
 command=['java','-Dfile.encoding=UTF-8','-cp',cp,'vn.techzone.backend.BackendMain']
 session=Path(tmp)/'data/backend-session.properties'
 def start_backend():
  process=subprocess.Popen(command,cwd=tmp,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  for _ in range(300):
   if session.exists():return process
   if process.poll() is not None:raise RuntimeError(process.stderr.read().decode('utf8','replace'))
   time.sleep(.1)
  process.terminate();process.wait(timeout=10)
  raise RuntimeError('Backend did not start')
 # Import the real GearVN snapshot first, then reactivate the isolated sample
 # fixtures needed to exercise checkout, compatibility, delivery and warranty.
 p=start_backend()
 p.terminate();p.wait(timeout=10)
 session.unlink(missing_ok=True)
 with closing(sqlite3.connect(Path(tmp)/'data/techzone.sqlite')) as db:
  db.execute("UPDATE products SET is_active=1 WHERE product_id NOT LIKE 'GVN-%'")
  db.execute("UPDATE product_variants SET is_active=1 WHERE variant_id NOT LIKE 'GVN-%'")
  db.commit()
 p=start_backend()
 try:
  props=dict(line.split('=',1) for line in session.read_text().splitlines() if '=' in line and not line.startswith('#'))
  base=props['url'].replace('\\:',':'); token=props['token']
  def call(path,data=None,headers=None):
   h={'X-TechZone-Token':token};h.update(headers or {})
   try:return urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=h),timeout=30)
   except urllib.error.HTTPError as error:
    raise AssertionError(f'{path}: HTTP {error.code}: {error.read().decode("utf8","replace")}') from error
  html=call('/').read().decode('utf8');assert token in html
  script_response=call('/app.js')
  assert script_response.headers.get_content_charset()=='utf-8'
  script=script_response.read().decode('utf8')
  for text in ('Crawl dữ liệu GearVN', 'Kiểm tra vỏ máy, kích thước, cáp và BIOS', 'Chưa xác minh'):
   assert text in script, ('Vietnamese text lost',text)
  for broken in ('ch?a c? gi?', 'C?p nh?t gi?', 'Ki?m tra v? m?y', 'Gi? ??t h?ng'):
   assert broken not in script, ('Broken encoding',broken)
  status=json.load(call('/v1/web/ai-status')); assert 'Chưa cài môi trường AI' in status['searchModel']
  assert '/vendor/bootstrap.min.css' in html and '/vendor/bootstrap.bundle.min.js' in html
  css=call('/vendor/bootstrap.min.css'); assert css.headers.get_content_type()=='text/css' and len(css.read())>100000
  js=call('/vendor/bootstrap.bundle.min.js'); assert js.headers.get_content_type()=='text/javascript' and b'Bootstrap v6.0.0-alpha.1' in js.read()

  chrome=Path(r'C:\Program Files\Google\Chrome\Application\chrome.exe')
  if chrome.exists() and os.environ.get('TECHZONE_SKIP_BROWSER')!='1':
   pages={'catalog':'Tìm đúng sản phẩm bạn cần','cart':'Thông tin thanh toán','orders':'Đơn hàng','inventory':'Thiết lập ngưỡng tồn','warranty':'Yêu cầu xác minh bảo hành','compatibility':'Rà soát cặp tương thích','reports':'Theo nhóm sản phẩm','admin':'Cấu hình AI','account':'Quyền riêng tư'}
   for page,marker in pages.items():
    result=subprocess.run([str(chrome),'--headless','--disable-gpu','--no-sandbox','--disable-extensions','--user-data-dir='+str(Path(tmp)/'browser'),'--virtual-time-budget=5000','--dump-dom',base+'/#'+page],capture_output=True,timeout=35)
    dom=result.stdout.decode('utf8','replace')
    assert result.returncode==0 and marker in dom and 'Không kết nối được backend' not in dom and (page!='catalog' or 'btn-solid' in dom),('browser',page,result.returncode,result.stderr.decode('utf8','replace')[-500:])
   result=subprocess.run([str(chrome),'--headless','--disable-gpu','--no-sandbox','--disable-extensions','--user-data-dir='+str(Path(tmp)/'browser-probe'),'--virtual-time-budget=12000','--dump-dom',base+'/?catalog-probe=1#catalog'],capture_output=True,timeout=35)
   dom=result.stdout.decode('utf8','replace')
   match=re.search(r'data-catalog-probe="([^"]*)"',dom)
   assert result.returncode==0 and match,('catalog probe did not finish',result.stderr.decode('utf8','replace')[-500:])
   probe=json.loads(base64.b64decode(match.group(1)).decode('utf8'))
   assert probe.get('initial',0)>0 and 0<probe.get('filtered',0)<probe['initial'],probe
   assert probe['restored']==probe['initial']==probe['afterDelayedResponse'] and not probe['query'] and probe['allSelected']=='true',probe
   assert 'Giá tham khảo (demo)' not in probe['cardText'] and 'Chưa có thông tin bảo hành' not in probe['cardText'] and 'chưa có giá được xác minh' not in probe['cardText'],probe
   assert 'Giá tham khảo (demo)' not in probe['detailText'] and 'Chưa có thông tin bảo hành' not in probe['detailText'] and 'chưa có giá được xác minh' not in probe['detailText'],probe
   assert probe['source'].startswith('https://gearvn.com/products/'),probe
   result=subprocess.run([str(chrome),'--headless','--disable-gpu','--no-sandbox','--disable-extensions','--user-data-dir='+str(Path(tmp)/'browser-cart-probe'),'--virtual-time-budget=25000','--dump-dom',base+'/?cart-probe=1#cart'],capture_output=True,timeout=60)
   match=re.search(r'data-cart-probe="([^"]*)"',result.stdout.decode('utf8','replace'))
   assert result.returncode==0 and match,('cart recommendation probe did not finish',result.stderr.decode('utf8','replace')[-500:])
   cart_probe=json.loads(base64.b64decode(match.group(1)).decode('utf8'))
   assert 'MB-B760-D5' in cart_probe.get('first',[]) and 'PSU-550' in cart_probe.get('second',[]) and cart_probe.get('checkoutEnabled'),cart_probe
   for item in json.load(call('/v1/web/state'))['cart']:
    call('/v1/web/action',urllib.parse.urlencode({'action':'quantity','id':item['id'],'quantity':'0'}).encode()).read()
   result=subprocess.run([str(chrome),'--headless','--disable-gpu','--no-sandbox','--disable-extensions','--user-data-dir='+str(Path(tmp)/'browser-rec-warm-probe'),'--virtual-time-budget=20000','--dump-dom',base+'/?rec-warm-probe=1#catalog'],capture_output=True,timeout=60)
   match=re.search(r'data-rec-warm-probe="([^"]*)"',result.stdout.decode('utf8','replace'))
   assert result.returncode==0 and match,('recommendation warmup probe did not finish',result.stderr.decode('utf8','replace')[-500:])
   warm_probe=json.loads(base64.b64decode(match.group(1)).decode('utf8'))
   assert warm_probe.get('calls',0)>=2 and warm_probe.get('value')=='Giữ nội dung' and warm_probe.get('open'),warm_probe
   result=subprocess.run([str(chrome),'--headless','--disable-gpu','--no-sandbox','--disable-extensions','--user-data-dir='+str(Path(tmp)/'browser-advisor-probe'),'--virtual-time-budget=25000','--dump-dom',base+'/?advisor-probe=1#catalog'],capture_output=True,timeout=60)
   match=re.search(r'data-advisor-probe="([^"]*)"',result.stdout.decode('utf8','replace'))
   assert result.returncode==0 and match,('advisor browser probe did not finish',result.stderr.decode('utf8','replace')[-500:])
   advisor_probe=json.loads(base64.b64decode(match.group(1)).decode('utf8'))
   assert 'bao nhiêu' in advisor_probe.get('clarification','') and len(advisor_probe.get('products',[]))==7 and advisor_probe.get('added') and not advisor_probe.get('overflow'),advisor_probe
   result=subprocess.run([str(chrome),'--headless','--disable-gpu','--no-sandbox','--disable-extensions','--user-data-dir='+str(Path(tmp)/'browser-compare-probe'),'--virtual-time-budget=25000','--dump-dom',base+'/?comparison-probe=1#catalog'],capture_output=True,timeout=60)
   match=re.search(r'data-comparison-probe="([^"]*)"',result.stdout.decode('utf8','replace'))
   assert result.returncode==0 and match,('comparison browser probe did not finish',result.stderr.decode('utf8','replace')[-500:])
   compare_probe=json.loads(base64.b64decode(match.group(1)).decode('utf8'))
   assert compare_probe.get('hasPair') and compare_probe.get('hasM2'),compare_probe
   for item in json.load(call('/v1/web/state'))['cart']:
    call('/v1/web/action',urllib.parse.urlencode({'action':'quantity','id':item['id'],'quantity':'0'}).encode()).read()
  catalog=json.load(call('/v1/products')); assert len(catalog)>=220
  comparison_result=json.load(call('/v1/web/compare-check?ids=MB-B760-D4%2CSSD-990'))
  assert comparison_result['pairs'] and comparison_result['pairs'][0]['status']=='MATCH' and comparison_result['pairs'][0]['approved'],comparison_result
  unknown_result=json.load(call('/v1/web/compare-check?ids=MB-B760-D5%2CSSD-990'))
  assert unknown_result['pairs'][0]['status']=='UNKNOWN' and not unknown_result['pairs'][0]['approved'],unknown_result
  advice=json.load(call('/v1/web/advisor',urllib.parse.urlencode({'message':'Tư vấn PC chơi game 35 triệu','context':''}).encode()))
  assert advice['configuration'] and len(advice['products'])==7 and advice['total']<=35000000,advice
  assert 'Chưa xác minh' in advice['reply'],advice
  assert 'chưa gồm' in advice['reply'].lower(),advice
  real=[p for p in catalog if p['id'].startswith('GVN-')]
  assert len(real)==200 and len({p['name'].casefold() for p in real})==200
  assert all(p['imageAvailable'] and p['specs'].get('sourceUrl','').startswith('https://gearvn.com/products/') and p['available']==0 for p in real)
  sourced_recs=json.load(call('/v1/products/'+real[0]['id']+'/recommendations'))
  assert not sourced_recs['items'],sourced_recs
  all_ids=call('/v1/search?query=&category=&semantic=true').read().decode('utf8').splitlines()
  assert set(all_ids)=={p['id'] for p in catalog if p['active'] and (p['available']>0 or p['specs'].get('sourceProvider')=='GEARVN')},'blank category must include all discoverable products'
  constrained=call('/v1/search?query=&category=&socket=LGA1700&memory=DDR5').read().decode('utf8').splitlines()
  assert constrained and all(next(p for p in catalog if p['id']==item)['specs'].get('socket')=='LGA1700' for item in constrained)
  assert call('/v1/products/'+catalog[0]['id']+'/image?token='+token).status==200
  def action(d):return json.load(call('/v1/web/action',urllib.parse.urlencode(d).encode()))
  action({'action':'add','id':'MB-B760-D5','quantity':1})
  action({'action':'add','id':'SSD-990','quantity':1})
  warned=json.load(call('/v1/web/state'))
  assert warned['cartValidation'] and 'Chưa được duyệt' in warned['cartCompatibilityWarning'],warned
  before_count=len(warned['orders'])
  blocked=action({'action':'checkout','address':'42 Nguyen Hue','payment':'Ví điện tử','promotion':'','verdict':'AUTHORIZED','idempotency':'blocked-unknown-pair'})
  after_block=json.load(call('/v1/web/state'))
  assert len(after_block['orders'])==before_count and len(after_block['cart'])==2 and 'chưa xác minh' in blocked['message'].lower(),blocked
  action({'action':'quantity','id':'MB-B760-D5','quantity':0})
  action({'action':'quantity','id':'SSD-990','quantity':0})
  action({'action':'consent','enabled':'true'})
  action({'action':'track_search','query':'cpu','filters':'{}','ids':catalog[0]['id'],'strategy':'test'})
  action({'action':'track_recs','id':catalog[0]['id']})
  state=json.load(call('/v1/web/state')); assert state['consent'] and state['queries']>=1
  action({'action':'limit','quantity':'4'})
  assert json.load(call('/v1/web/state'))['recommendationLimit']==4
  with ThreadPoolExecutor(max_workers=2) as pool:
   pending=[pool.submit(action,{'action':'threshold','id':catalog[i]['id'],'quantity':str(3+i)}) for i in range(2)]
   for task in pending: task.result()
  inventory={row['productId']:row for row in json.load(call('/v1/inventory'))}
  assert inventory[catalog[0]['id']]['threshold']==3 and inventory[catalog[1]['id']]['threshold']==4

  action({'action':'restock_request','id':catalog[0]['id'],'quantity':'2'})
  state=json.load(call('/v1/web/state')); assert state['requests']
  created=next(r for r in state['requests'] if r['product']==catalog[0]['id'] and r['status']=='Chờ nhà cung cấp xác nhận')
  action({'action':'receive','id':created['id']})
  action({'action':'add','id':catalog[0]['id'],'quantity':1})
  state=json.load(call('/v1/web/state'));assert state['cart']
  assert json.load(call('/v1/products/'+catalog[0]['id']+'/recommendations'))['items'] is not None
  out=action({'action':'checkout','address':'42 Nguyen Hue, TP HCM','payment':'Ví điện tử','promotion':'','verdict':'AUTHORIZED','idempotency':'web-smoke-unique'})
  state=json.load(call('/v1/web/state')); assert not state['cart']; assert state['orders'],out
  order=state['orders'][0]; assert order['rawStatus']=='CONFIRMED',(order,out)
  line=order['lines'][0]; assert line['reservedSample']
  action({'action':'allocate','order':order['id'],'id':line['id'],'serial':line['reservedSample']})
  state=json.load(call('/v1/web/state')); assert state['orders'][0]['lines'][0]['allocated']==1
  action({'action':'ship','order':order['id'],'carrier':'Demo','weight':'1000','dimensions':'30x20x10'})
  state=json.load(call('/v1/web/state')); assert state['orders'][0]['rawStatus']=='SHIPPED'
  for milestone in ['Đang trung chuyển','Đang giao tới bạn','Đã giao thành công']:
   action({'action':'milestone','order':order['id'],'milestone':milestone,'location':'TP HCM'})
  state=json.load(call('/v1/web/state')); assert state['orders'][0]['rawStatus']=='DELIVERED'; assert state['warranties']
  action({'action':'rma','serial':state['warranties'][0]['serial'],'problem':'Thiết bị không hoạt động'})
  state=json.load(call('/v1/web/state')); assert state['rmas']
  action({'action':'restock_request','id':real[0]['id'],'quantity':'1'})
  request=next(r for r in json.load(call('/v1/web/state'))['requests'] if r['product']==real[0]['id'])
  action({'action':'receive','id':request['id']})
  action({'action':'add','id':real[0]['id'],'quantity':1})
  action({'action':'checkout','address':'42 Nguyen Hue, TP HCM','payment':'Ví điện tử','promotion':'','verdict':'AUTHORIZED','idempotency':'web-smoke-sourced'})
  sourced=json.load(call('/v1/web/state'))['orders'][0]
  sourced_line=sourced['lines'][0]; serial=sourced_line['reservedSample']
  action({'action':'allocate','order':sourced['id'],'id':sourced_line['id'],'serial':serial})
  action({'action':'ship','order':sourced['id'],'carrier':'Demo','weight':'1000','dimensions':'30x20x10'})
  for milestone in ['Đang trung chuyển','Đang giao tới bạn','Đã giao thành công']:
   delivery=action({'action':'milestone','order':sourced['id'],'milestone':milestone,'location':'TP HCM'})
  state=json.load(call('/v1/web/state'))
  assert 'chưa có thời hạn bảo hành' in delivery['message'] and not any(w['serial']==serial for w in state['warranties']),delivery
  review=action({'action':'review','id':real[0]['id'],'text':'Kiểm tra đánh giá sản phẩm','stars':'5'})
  assert 'Đã' in review['message'],review
  action({'action':'verification','serial':serial,'problem':'Cần xác minh bảo hành'})
  verification=next(v for v in json.load(call('/v1/web/state'))['verification'] if v['serial']==serial)
  pending=action({'action':'verification_review','id':verification['id']})
  assert 'Chưa có thông tin thời hạn' in pending['message']
  action({'action':'warranty_duration','id':real[0]['id'],'quantity':'24','reason':'Thông tin thử nghiệm từ nhà cung cấp'})
  assert json.load(call('/v1/products/'+real[0]['id']))['warrantyMonths']==24
  reviewed=action({'action':'verification_review','id':verification['id']})
  assert 'tạo RMA' in reviewed['message'] and any(w['serial']==serial for w in json.load(call('/v1/web/state'))['warranties']),reviewed
  print('Web smoke passed:',len(catalog),'products; cart, checkout, allocation, delivery, warranty, RMA, recommendation')
 finally:
  p.terminate();p.wait(timeout=10)
