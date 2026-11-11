'use strict';
(() => {
  const panel=document.querySelector('#advisor-panel'), toggle=document.querySelector('#advisor-toggle');
  const messages=document.querySelector('#advisor-messages'), input=document.querySelector('#advisor-input');
  const form=document.querySelector('#advisor-form'), send=document.querySelector('#advisor-send');
  const status=document.querySelector('#advisor-status');
  let turns=[],dialogTurns=[],busy=false,generation=0;
  const scroll=()=>{messages.scrollTop=messages.scrollHeight};
  function bubble(text,kind){const node=document.createElement('div');node.className='advisor-bubble '+kind;node.textContent=text;messages.append(node);scroll();return node}
  let statusPending=false;
  async function refreshStatus(){
    if(panel.hidden||document.hidden||statusPending)return;
    statusPending=true;
    try{const data=await api('/v1/web/advisor-status').then(r=>r.json());
      if(!panel.hidden)status.textContent=data.model.startsWith('Qwen')?'Trợ lý AI cục bộ sẵn sàng · '+data.model:data.model;
    }catch(error){if(!panel.hidden)status.textContent='Tư vấn dựa trên danh mục hiện có'}finally{statusPending=false}
  }
  function show(){
    panel.hidden=false;toggle.setAttribute('aria-expanded','true');input.focus();
    refreshStatus();
  }
  setInterval(refreshStatus,5000);
  function hide(){panel.hidden=true;toggle.setAttribute('aria-expanded','false');toggle.focus()}
  toggle.addEventListener('click',()=>panel.hidden?show():hide());
  document.querySelector('#advisor-close').addEventListener('click',hide);
  document.querySelector('#advisor-clear').addEventListener('click',()=>{
    generation++; turns=[];dialogTurns=[];messages.replaceChildren();
    bubble('Bạn cần máy để làm gì và ngân sách khoảng bao nhiêu?','assistant');
    busy=false;send.disabled=false;input.disabled=false;input.value='';input.focus();
  });
  document.querySelectorAll('[data-advisor-prompt]').forEach(button=>button.addEventListener('click',()=>{
    input.value=button.dataset.advisorPrompt;form.requestSubmit();
  }));
  input.addEventListener('keydown',event=>{
    if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();form.requestSubmit()}
  });
  form.addEventListener('submit',async event=>{
    event.preventDefault();event.stopPropagation();
    const message=input.value.trim();if(!message||busy)return;
    const current=++generation;busy=true;send.disabled=true;input.disabled=true;input.value='';
    bubble(message,'user');const thinking=bubble('Đang xem nhu cầu và danh mục…','assistant thinking');
    try{
      for(let attempt=0;attempt<30;attempt++){
        const model=await api('/v1/web/advisor-status').then(r=>r.json());
        if(current!==generation)return;
        if(model.model!=='Đang khởi động mô hình hội thoại')break;
        thinking.textContent='Đang khởi động mô hình hội thoại, vui lòng chờ…';
        await new Promise(resolve=>setTimeout(resolve,2000));
      }
      thinking.textContent='Đang phân tích câu hỏi và kiểm tra danh mục…';
      const response=await api('/v1/web/advisor',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:new URLSearchParams({message,context:turns.slice(-3).join('\n').slice(-1000),conversation:dialogTurns.slice(-4).join('\n').slice(-1200)})});
      const answer=await response.json();
      if(current!==generation)return;
      thinking.remove();bubble(answer.reply,'assistant');
      const mode=document.createElement('div');mode.className='advisor-mode';
      mode.textContent=answer.model.startsWith('Qwen')?'Câu trả lời có dùng mô hình hội thoại '+answer.model:'Câu trả lời theo danh mục và quy tắc';
      messages.append(mode);
      if(answer.rankingMethod==='semantic'||answer.rankingMethod==='price'){
        const explanation=document.createElement('details');explanation.className='advisor-products';
        const title=document.createElement('summary');title.textContent='Cấu hình này được chọn như thế nào?';explanation.append(title);
        const detail=document.createElement('p');
        detail.textContent=answer.rankingMethod==='semantic'
          ? `Hệ thống chỉ xét các cặp đã duyệt, sau đó kiểm tra ngân sách và tồn kho hiện tại. ${answer.rankingModel} xếp hạng theo điểm ngữ nghĩa trung bình. Điểm không đo hiệu năng; thông số vỏ, cáp và BIOS vẫn cần kiểm tra.`
          : 'Mô hình ngữ nghĩa chưa trả kết quả kịp; hệ thống chọn theo ngân sách trong số các cặp đã duyệt. Giá cao hơn không đồng nghĩa hiệu năng tốt hơn.';
        explanation.append(detail);messages.append(explanation);
      }
      turns.push(message);turns=turns.slice(-3);
      const shownProducts=answer.products.map(product=>product.name).join('; ');
      dialogTurns.push('Khách: '+message,'Trợ lý: '+answer.reply+(shownProducts?'\nSản phẩm hệ thống đã hiển thị: '+shownProducts:''));dialogTurns=dialogTurns.slice(-4);
      if(answer.products.length){
        const section=document.createElement('div');section.className='advisor-products';
        const heading=document.createElement('strong');heading.textContent=answer.configuration?'Linh kiện được đề xuất':'Sản phẩm tham khảo';section.append(heading);
        for(const product of answer.products){
          const row=document.createElement('div');row.className='advisor-product';
          const score=answer.semanticScores?.[product.id];
          const external=product.specs?.sourceProvider==='GEARVN';
          const source=product.specs?.sourceUrl||'';
          const safeSource=/^https:\/\/gearvn\.com\/products\/[a-z0-9-]+$/.test(source)?source:'';
          row.innerHTML=`<img src="${h(productImageUrl(product))}" alt=""><div><button type="button" class="advisor-product-name" data-advisor-detail="${h(product.id)}">${h(product.name)}</button><span>Giá: ${money(product.effectivePrice)} · ${external?'Nguồn GearVN':`Tồn kho: ${product.available}`}</span>${Number.isFinite(score)?`<span>Điểm gần ngữ nghĩa: ${h(score.toFixed(3))}</span>`:''}${marketOffers.has(product.id)?marketOffer(product):''}</div>${external?(safeSource?`<a href="${h(safeSource)}" target="_blank" rel="noopener noreferrer">Xem tại GearVN</a>`:''):`<button type="button" data-advisor-add="${h(product.id)}" aria-label="Thêm ${h(product.name)} vào giỏ" ${product.available?'':'disabled'}>＋</button>`}`;
          section.append(row);
        }
        if(answer.configuration && answer.total){const total=document.createElement('p');total.className='advisor-total';const estimate=answer.total+Math.floor(answer.total/10)+(answer.total<2000000?35000:0);total.textContent='Tổng cấu hình: '+money(answer.total)+' · Dự kiến cả thuế, giao hàng: '+money(estimate);section.append(total)}
        messages.append(section);scroll();
      }
      scroll();
    }catch(error){if(current===generation){thinking.remove();bubble('Mình chưa thể xử lý yêu cầu. Vui lòng thử lại sau.','assistant')}}
    finally{if(current===generation){busy=false;send.disabled=false;input.disabled=false;input.focus()}}
  });
  messages.addEventListener('click',async event=>{
    const detailButton=event.target.closest('[data-advisor-detail]');
    if(detailButton){detailButton.disabled=true;try{await detail(detailButton.dataset.advisorDetail)}catch(error){msg(error.message,true)}finally{detailButton.disabled=false}return}
    const button=event.target.closest('[data-advisor-add]');if(!button)return;
    button.disabled=true;
    try{await action({action:'add',id:button.dataset.advisorAdd,quantity:1})}
    catch(error){msg(error.message,true)}finally{button.disabled=false}
  });
})();
