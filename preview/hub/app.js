'use strict';
const $=id=>document.getElementById(id);
let data,current,step=0,animated=false;
function el(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;}
function mode(name,url){
 const recent=name==='recent';$('recent').hidden=!recent;$('viewer').hidden=recent;
 document.querySelectorAll('[data-mode]').forEach(b=>b.classList.toggle('selected',b.dataset.mode===name));
 if(!recent){const src=url||('models/index.html'+(name==='audio'?'#audio':''));$('viewer-frame').src=src;$('viewer-open').href=src;}
 else $('viewer-frame').src='about:blank';
 location.hash=recent?'recent':name;
}
function showStep(index){
 step=index;const item=current.steps[index];
 [...$('steps').children].forEach((b,i)=>{b.classList.toggle('active',i===index);b.setAttribute('aria-pressed',i===index);});
 $('step-number').textContent=String(index+1).padStart(2,'0');$('step-title').textContent=item.title;$('step-body').textContent=item.body;
 $('progress-dot').style.left=(index/Math.max(1,current.steps.length-1)*100)+'%';
 $('media-frame').style.background=item.color||'';$('season-name').textContent=current.id==='climate'?item.title:'';
}
function chapter(id){
 current=data.chapters.find(c=>c.id===id)||data.chapters[0];animated=false;
 [...$('chapter-list').children].forEach(b=>{const selected=b.dataset.id===current.id;b.classList.toggle('active',selected);b.setAttribute('aria-selected',selected);});
 $('tag').textContent=current.tag;$('title').textContent=current.title;$('summary').textContent=current.summary;
 $('points').replaceChildren(...current.points.map(p=>el('li',p)));
 const portrait=current.image.startsWith('models/');$('media-frame').classList.toggle('portrait',portrait);
 $('main-image').src=current.image;$('main-image').alt=current.tag+'：当前游戏资源的离线预览';
 $('media-caption').textContent=current.mediaPending?'分镜尚未生成 · 先展示当前运行模型':portrait?'当前运行模型 · 下方流程为行为示意':'当前运行资源的离线分镜';
 $('animate').hidden=!current.animation;$('animate').textContent='播放初航分镜';
 $('source-link').href='/'+current.source;
 $('steps').replaceChildren(...current.steps.map((item,i)=>{const b=el('button');b.append(el('small','STEP '+String(i+1).padStart(2,'0')),el('strong',item.title));b.onclick=()=>showStep(i);return b;}));
 $('gallery').replaceChildren(...(current.gallery||[]).map(item=>{const f=el('figure'),a=el('a'),img=el('img');a.href=item.src;a.target='_blank';img.src=item.src;img.alt=item.label;img.loading='lazy';a.append(img);f.append(a,el('figcaption',item.label));return f;}));
 showStep(0);
}
$('animate').onclick=()=>{animated=!animated;$('main-image').src=animated?current.animation:current.image;$('animate').textContent=animated?'返回六格总览':'播放初航分镜';};
$('model-link').onclick=()=>mode('models','models/index.html?model='+encodeURIComponent(current.model)+'&action='+encodeURIComponent(current.action));
$('back').onclick=()=>mode('recent');document.querySelector('.brand').onclick=e=>{e.preventDefault();mode('recent');};document.querySelectorAll('[data-mode]').forEach(b=>b.onclick=()=>mode(b.dataset.mode));
$('main-image').onerror=()=>{$('media-caption').textContent='此图尚未生成，可先查看下方流程与当前模型。';};
fetch('recent.json').then(r=>{if(!r.ok)throw Error('预览索引读取失败');return r.json();}).then(value=>{
 data=value;$('chapter-list').replaceChildren(...data.chapters.map((c,i)=>{const b=el('button');b.dataset.id=c.id;b.setAttribute('role','tab');b.append(el('small',String(i+1).padStart(2,'0')),document.createTextNode(c.label));b.onclick=()=>chapter(c.id);return b;}));
 $('period').textContent='2026.10.15 20:00 → '+data.sourceRevision.slice(0,8);
 $('version').textContent='更新范围：北京时间 2026 年 10 月 15 日 20:00 起，共 '+data.commitCount+' 个提交（含合并与修复）。内容版本 '+data.sourceRevision.slice(0,8)+'；页面按最终有效行为整理。';
 $('generated').textContent='生成于 '+data.generatedAt.replace('T',' ').replace('+08:00','')+'（北京时间）';
 chapter(data.chapters[0].id);if(location.hash==='#audio')mode('audio');else if(location.hash==='#models')mode('models');
}).catch(error=>{$('error').hidden=false;$('error').textContent=error.message+'。请通过本地预览服务打开。';});
