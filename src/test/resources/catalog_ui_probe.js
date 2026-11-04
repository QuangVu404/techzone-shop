/* Loaded only by the isolated browser regression test. */
if (location.search === '?catalog-probe=1') {
  const originalFetch = window.fetch;
  let delaySearch = false;
  window.fetch = async (input, options) => {
    if (delaySearch && String(input).startsWith('/v1/search?')) {
      delaySearch = false;
      await new Promise(resolve => setTimeout(resolve, 1800));
    }
    return originalFetch(input, options);
  };
  setTimeout(async () => {
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    try {
      const initial = document.querySelectorAll('.product-grid .product').length;
      document.querySelector('[data-category="CPU"]').click();
      await sleep(2500);
      const filtered = document.querySelectorAll('.product-grid .product').length;
      document.querySelector('[data-category=""]').click();
      document.querySelector('[data-category=""]').click();
      const restored = document.querySelectorAll('.product-grid .product').length;
      document.querySelector('#search input[name=query]').value = 'nội dung cũ';
      delaySearch = true;
      document.querySelector('[data-category="CPU"]').click();
      document.querySelector('[data-category=""]').click();
      await sleep(3000);
      const afterDelayedResponse = document.querySelectorAll('.product-grid .product').length;
      const card = document.querySelector('[data-detail="PCP-Zxw7YJ"]').closest('.product');
      const cardText = card.textContent;
      card.querySelector('[data-detail]').click();
      for (let i = 0; i < 30 && !document.querySelector('#dialog[open] .detail-info'); i++) await sleep(200);
      const detail = document.querySelector('#dialog[open] .detail-info');
      const source = detail?.querySelector('a[href^="https://fr.pcpartpicker.com/product/"]');
      document.body.dataset.catalogProbe = JSON.stringify({initial, filtered, restored,
        afterDelayedResponse, cardText, detailText: detail?.textContent || '', source: source?.href || '',
        query: document.querySelector('#search input[name=query]').value,
        allSelected: document.querySelector('[data-category=""]').getAttribute('aria-pressed')});
    } catch (error) {
      document.body.dataset.catalogProbe = JSON.stringify({error:String(error)});
    }
  }, 1000);
}

if (location.search === '?cart-probe=1') {
  const realFetch = window.fetch;
  let failRecommendations = false;
  window.fetch = (input, options) => {
    if (failRecommendations && String(input).includes('/recommendations')) return Promise.reject(new Error('offline'));
    return realFetch(input, options);
  };
  setTimeout(async () => {
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    const waitFor = async predicate => { for (let i=0;i<60;i++) { if(predicate()) return; await sleep(200); } throw Error('timeout'); };
    try {
      await action({action:'add',id:'CPU-14600K',quantity:1});
      await action({action:'add',id:'GPU-4060',quantity:1});
      await waitFor(() => document.querySelector('#cart-recommendations [data-add]'));
      const first = [...document.querySelectorAll('#cart-recommendations [data-add]')].map(b=>b.dataset.add);
      const choice=document.querySelector('#cart-recommendation-primary');
      choice.value='GPU-4060'; choice.dispatchEvent(new Event('change',{bubbles:true}));
      await waitFor(() => document.querySelector('#cart-recommendations [data-add="PSU-550"]'));
      const second=[...document.querySelectorAll('#cart-recommendations [data-add]')].map(b=>b.dataset.add);
      failRecommendations=true;
      const secondChoice=document.querySelector('#cart-recommendation-primary');
      secondChoice.value='CPU-14600K'; secondChoice.dispatchEvent(new Event('change',{bubbles:true}));
      await waitFor(() => document.querySelector('#cart-recommendations .cart-recommendation-results')?.textContent.includes('tạm thời'));
      document.body.dataset.cartProbe=JSON.stringify({first,second,checkoutEnabled:!document.querySelector('.checkout-button').disabled});
    } catch(error) { document.body.dataset.cartProbe=JSON.stringify({error:String(error)}); }
  },1000);
}

if (location.search === '?advisor-probe=1') {
  setTimeout(async () => {
    const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    const waitFor=async predicate=>{for(let i=0;i<80;i++){if(predicate())return;await sleep(200)}throw Error('timeout')};
    try {
      document.querySelector('#advisor-toggle').click();
      const input=document.querySelector('#advisor-input'), form=document.querySelector('#advisor-form');
      input.value='Tư vấn cấu hình PC chơi game';form.requestSubmit();
      await waitFor(()=>document.querySelectorAll('.advisor-bubble.assistant').length>=2 && !document.querySelector('.advisor-bubble.thinking'));
      const clarification=document.querySelector('#advisor-messages').textContent;
      input.value='35 triệu';form.requestSubmit();
      await waitFor(()=>document.querySelectorAll('.advisor-product').length>=7);
      const products=[...document.querySelectorAll('.advisor-product [data-advisor-add]')].map(b=>b.dataset.advisorAdd);
      const total=document.querySelector('.advisor-total')?.textContent||'';
      document.querySelector('.advisor-product [data-advisor-add]').click();
      await waitFor(()=>document.querySelector('.header-cart-count').textContent==='1');
      document.body.dataset.advisorProbe=JSON.stringify({clarification,products,total,added:true,
        overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth});
    }catch(error){document.body.dataset.advisorProbe=JSON.stringify({error:String(error)})}
  },1000);
}

if (location.search === '?comparison-probe=1') {
  setTimeout(async () => {
    const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    const waitFor=async predicate=>{for(let i=0;i<80;i++){if(predicate())return;await sleep(200)}throw Error('timeout')};
    try {
      await waitFor(()=>document.querySelector('[data-compare="MB-B760-D4"]')&&document.querySelector('[data-compare="SSD-990"]'));
      document.querySelector('[data-compare="MB-B760-D4"]').click();
      document.querySelector('[data-compare="SSD-990"]').click();
      document.querySelector('#compare').click();
      await waitFor(()=>document.querySelector('#dialog').open && document.querySelector('#dialog-content').textContent.includes('Đối chiếu tương thích'));
      const content=document.querySelector('#dialog-content').textContent;
      document.body.dataset.comparisonProbe=JSON.stringify({hasPair:content.includes('Cặp đã duyệt'),hasM2:content.includes('2280')});
    }catch(error){document.body.dataset.comparisonProbe=JSON.stringify({error:String(error)})}
  },1000);
}

if (location.search === '?rec-warm-probe=1') {
  const realFetch=window.fetch;let calls=0;
  window.fetch=async (input,options)=>{
    const path=String(input);
    if(path==='/v1/web/ai-status')return new Response(JSON.stringify({searchModel:'BGE-M3'}),{headers:{'Content-Type':'application/json'}});
    const response=await realFetch(input,options);
    if(path.includes('/CPU-14600K/recommendations')){
      const data=await response.json();data.strategy=++calls===1?'Fallback phổ biến':'AI gợi ý · BGE-M3';
      return new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}});
    }
    return response;
  };
  setTimeout(async()=>{
    const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    const waitFor=async predicate=>{for(let i=0;i<60;i++){if(predicate())return;await sleep(200)}throw Error('timeout')};
    try{
      document.querySelector('[data-detail="CPU-14600K"]').click();
      await waitFor(()=>document.querySelector('#dialog[open] [name=text]'));
      const input=document.querySelector('#dialog [name=text]');input.value='Giữ nội dung';
      await waitFor(()=>document.querySelector('#dialog [data-ai-recommendation] .search-badge')?.textContent.includes('AI'));
      document.body.dataset.recWarmProbe=JSON.stringify({calls,value:input.value,open:document.querySelector('#dialog').open});
    }catch(error){document.body.dataset.recWarmProbe=JSON.stringify({error:String(error),calls})}
  },1000);
}
