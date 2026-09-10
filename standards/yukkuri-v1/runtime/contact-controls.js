function initContactControls() {
  if(!contact)return;
  const panel=document.createElement('div');panel.className='controls';panel.id='contact-controls';
  panel.innerHTML='<label>显现部位 <select id="contact-part"></select></label><label><input type="checkbox" id="contact-mode">接触模式</label><button id="contact-hold" style="touch-action:none;user-select:none">按住接触</button><button id="contact-front">查看脸下</button><button id="contact-rear">查看油菊下方</button><span id="contact-status" role="status">已隐藏</span><span class="note">同一个角色具有两种部件，同时只显现所选一种；切换时先收回。接触显现，松开恢复。</span>';
  document.querySelector('.stage').after(panel);
  const part=document.getElementById('contact-part');
  for(const p of DATA.contactReveal.parts)part.add(new Option(p.label,p.id));
  part.value=contact.selectedPart;
  part.onchange=()=>{contact.selectPart(part.value);dirty=true;};
  const mode=document.getElementById('contact-mode'),hold=document.getElementById('contact-hold'),sources=new Set();
  function setSource(key,active){if(active)sources.add(key);else sources.delete(key);contact.setContact(sources.size>0);dirty=true;}
  function release(){sources.clear();contact.setContact(false);dirty=true;}
  function hit(e){const r=canvas.getBoundingClientRect(),x=Math.floor((e.clientX-r.left)*canvas.width/r.width),y=Math.floor((e.clientY-r.top)*canvas.height/r.height);return ready&&x>=0&&y>=0&&x<canvas.width&&y<canvas.height&&ctx.getImageData(x,y,1,1).data[3]>0;}
  const contactPointers=new Set();
  canvas.addEventListener('pointerdown',e=>{if(!mode.checked)return;e.preventDefault();e.stopImmediatePropagation();contactPointers.add(e.pointerId);canvas.setPointerCapture(e.pointerId);setSource('canvas'+e.pointerId,hit(e));},true);
  canvas.addEventListener('pointermove',e=>{if(!contactPointers.has(e.pointerId))return;e.stopImmediatePropagation();setSource('canvas'+e.pointerId,hit(e));},true);
  for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,e=>{contactPointers.delete(e.pointerId);setSource('canvas'+e.pointerId,false);});
  canvas.addEventListener('pointerleave',e=>setSource('canvas'+e.pointerId,false));
  hold.addEventListener('pointerdown',e=>{e.preventDefault();hold.setPointerCapture(e.pointerId);setSource('button'+e.pointerId,true);});
  for(const event of ['pointerup','pointercancel','lostpointercapture'])hold.addEventListener(event,e=>setSource('button'+e.pointerId,false));
  hold.addEventListener('pointermove',e=>{if(!hold.hasPointerCapture(e.pointerId))return;const r=hold.getBoundingClientRect();setSource('button'+e.pointerId,e.clientX>=r.left&&e.clientX<=r.right&&e.clientY>=r.top&&e.clientY<=r.bottom);});
  hold.addEventListener('keydown',e=>{if(e.code==='Space'||e.code==='Enter'){e.preventDefault();setSource('keyboard',true);}});
  hold.addEventListener('keyup',e=>{if(e.code==='Space'||e.code==='Enter'){e.preventDefault();setSource('keyboard',false);}});
  hold.addEventListener('blur',()=>setSource('keyboard',false));
  mode.onchange=()=>{release();contactPointers.clear();pointers.clear();canvas.classList.remove('dragging');};
  window.addEventListener('blur',release);
  document.addEventListener('visibilitychange',()=>{if(document.hidden)release();});
  document.getElementById('contact-front').onclick=()=>{az=0;el=.04;span=11;centerY=6.5;dirty=true;};
  document.getElementById('contact-rear').onclick=()=>{az=Math.PI;el=.04;span=11;centerY=6.5;dirty=true;};
  window.contactDebug={state:()=>({amount:contact.amount,touching:contact.touching,part:contact.activePart,selectedPart:contact.selectedPart}),setContact(value){setSource('debug',value);},selectPart(id){contact.selectPart(id);part.value=id;dirty=true;}};
}
