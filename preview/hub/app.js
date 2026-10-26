'use strict';
const $=id=>document.getElementById(id), R=window.PreviewRoute;
const labels={all:'全部',models:'角色',motions:'动作',items:'物品',audio:'声音',scenes:'演示',guide:'试玩指引'};
const kinds={model:'三维角色',motion:'动态动作',item:'物品',audio:'声音',scene:'演示',scenario:'试玩指引',reference:'预览说明'};
let library,data,state=R.parse(location.href), timer;
const pageSize=36;
let disposeScene;
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function link(text,next,cls){const a=el('a',text,cls);a.href=R.href(next);return a;}
function browseState(patch={}){return {...state,entry:'',step:0,player:'',playerTab:'',...patch};}
function go(next,replace=false){history[replace?'replaceState':'pushState'](null,'',R.href(next));state=next;render();}
function stopMedia(){disposeScene?.();disposeScene=null;for(const a of document.querySelectorAll('audio,video'))a.pause();$('content').replaceChildren();$('steps').replaceChildren();}
function picture(src,title,cls){const img=el('img',undefined,cls);img.src=src;img.alt=title;img.loading='lazy';img.onerror=()=>{img.replaceWith(el('p','此预览暂不可用。','media-missing'));};return img;}
function viewerURL(entry){const p=new URLSearchParams({embed:'1'});if(entry.model)p.set('model',entry.model);if(entry.action){p.set('action',entry.action);p.set('autoplay','1');}if(entry.species)p.set('species',entry.species);if(state.player)for(const [k,v] of new URLSearchParams(state.player))if(['model','action','species','event','audioId','age','q','autoplay'].includes(k))p.set(k,v);return 'models/index.html?'+p+(state.playerTab?'#'+state.playerTab:entry.kind==='audio'?'#audio':'#models');}
function renderNav(){
 $('sections').replaceChildren(...Object.entries(labels).map(([id,label])=>{const a=link(label,browseState({section:id,page:1}),id===state.section?'active':'');if(id===state.section)a.setAttribute('aria-current','page');return a;}));
}
function renderList(){
 const available=library.entries.filter(e=>R.matches(e,{...state,category:'all'}));
 const categories=[{id:'all',label:'全部分类'},...library.categories];
 $('categories').replaceChildren(...categories.map(c=>{const count=available.filter(e=>c.id==='all'||e.category===c.id).length;const a=link('',browseState({category:c.id,page:1}),state.category===c.id?'active':'');a.append(el('span',c.label),el('small',String(count)));if(state.category===c.id)a.setAttribute('aria-current','page');return a;}));
 const category=categories.find(c=>c.id===state.category);
 $('category-title').textContent=category?.label||'全部分类';
 const entries=library.entries.filter(e=>R.matches(e,state));
 const pages=Math.max(1,Math.ceil(entries.length/pageSize));
 if(state.page>pages){state.page=pages;history.replaceState(null,'',R.href(state));}
 $('result-count').textContent=entries.length+' 项';$('empty').hidden=entries.length>0;
 $('search').value=state.q;$('clear').hidden=!state.q&&state.category==='all';
 $('entries').replaceChildren(...entries.slice((state.page-1)*pageSize,state.page*pageSize).map(entry=>{
   const a=link('',{...state,entry:entry.id,step:0,player:'',playerTab:''},'entry');
   if(entry.image)a.append(picture(entry.image,'','thumbnail'+(entry.visual==='物品图标'?' pixel':'')));
   else a.append(el('div',{motion:'播放动作',model:'查看模型',audio:'试听',scenario:'游戏内查看',reference:'说明'}[entry.kind]||'暂无图片','placeholder'));
   const copy=el('div',undefined,'entry-copy');copy.append(el('small',kinds[entry.kind]),el('h3',entry.title));a.append(copy);return a;
 }));
 const controls=[];
 if(state.page>1)controls.push(link('← 上一页',browseState({page:state.page-1})));
 if(pages>1)controls.push(el('span',state.page+' / '+pages));
 if(state.page<pages)controls.push(link('下一页 →',browseState({page:state.page+1})));
 $('pagination').replaceChildren(...controls);
}
function renderDetail(){
 const entry=library.entries.find(e=>e.id===state.entry);
 $('back').href=R.href(browseState());$('standalone').hidden=true;
 if(!entry){$('detail-title').textContent='这项预览不存在';$('detail-description').textContent='内容可能已更新，请返回列表重新选择。';$('detail-category').textContent='';return;}
 $('detail-title').textContent=entry.title;$('detail-description').textContent=entry.summary;
 $('detail-category').textContent=library.categories.find(c=>c.id===entry.category)?.label||'';
 if(['model','motion','audio'].includes(entry.kind)){
   const src=viewerURL(entry),frame=el('iframe');frame.src=src;frame.title=entry.title;frame.className='viewer';frame.allow='autoplay';$('content').append(frame);
   $('standalone').href=src.replace('embed=1&','').replace('embed=1','');$('standalone').hidden=false;
 }else if(entry.kind==='scene'){
   const chapter=data.chapters.find(c=>c.id===entry.chapter);
   const steps=chapter?.steps||[];
   if(!steps.length){$('content').append(el('p','当前没有可用画面。'));return;}
   state.step=Math.min(state.step,steps.length-1);history.replaceState(null,'',R.href(state));
   const item=steps[state.step];
   if(item.playback)disposeScene=window.ScenePlayer.mount($('content'),item);
   else if(item.animation||item.image)$('content').append(picture(item.animation||item.image,item.title,'scene-image'));
   if(item.audioSample&&data.audioSamples?.[item.audioSample]){const sample=data.audioSamples[item.audioSample],audio=el('audio');audio.src=sample.url;audio.controls=true;audio.preload='none';$('content').append(el('p',sample.zh),el('p',sample.ja),audio);}
   if(item.kind==='subtitles')for(const sample of data.subtitleSamples||[])$('content').append(el('h2',sample.species==='remilia'?'蕾米莉亚':'芙兰'),el('p',sample.zh),el('p',sample.ja));
   if(item.kind==='logic')for(const line of item.states||[])$('content').append(el('p',line));
   if(item.video){const video=el('video');video.src=item.video;video.controls=true;video.loop=true;video.className='scene-image';$('content').append(video);}
   $('content').append(el('p',item.body||item.title,'caption'));
   if(item.action&&chapter.model){const a=el('a','三维播放此动作 ↗');a.href=viewerURL({model:item.model||chapter.model,action:item.action}).replace('embed=1&','');a.target='_blank';$('content').append(a);}
   $('steps').replaceChildren(...steps.map((s,i)=>link(s.title,{...state,step:i},i===state.step?'active':'')));
 }else{
   if(entry.image)$('content').append(picture(entry.image,entry.title,'scene-image'+(entry.visual==='物品图标'?' pixel':'')));
   if(entry.kind==='scenario'){
     $('content').append(el('p','运行「启动动作博物馆.cmd」，到「'+entry.field+'」选择「'+entry.title+'」。','instruction'));
     const dl=el('dl');for(const [k,v] of [['观察时间',entry.wait],['重开方式',entry.retry],['说明',entry.note]])if(v)dl.append(el('dt',k),el('dd',v));$('content').append(dl);
   }else if(!entry.image)$('content').append(el('p','暂无独立外观预览，可到游戏里查看。','instruction'));
 }
}
function render(){
 if(!library)return;
 stopMedia();renderNav();$('browse').hidden=!!state.entry;$('detail').hidden=!state.entry;
 document.title=(state.entry?library.entries.find(e=>e.id===state.entry)?.title||'预览':labels[state.section])+' · MCyukkuri';
 if(state.entry)renderDetail();else renderList();
}
document.addEventListener('click',event=>{
 const a=event.target.closest('a');if(!a||a.target||event.button||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey)return;
 const url=new URL(a.href);if(url.origin!==location.origin||url.pathname!==location.pathname)return;
 event.preventDefault();clearTimeout(timer);go(R.parse(url.href));window.scrollTo(0,0);
});
window.addEventListener('message',event=>{
 const frame=$('content').querySelector('iframe');
 if(event.origin!==location.origin||event.source!==frame?.contentWindow||event.data?.type!=='preview-selection'||typeof event.data.query!=='string')return;
 const p=new URLSearchParams();for(const [k,v] of new URLSearchParams(event.data.query))if(['model','action','species','event','audioId','age','q','autoplay'].includes(k))p.set(k,v);
 state.player=p.toString();state.playerTab=event.data.tab==='audio'?'audio':'models';
 const previous=library.entries.find(e=>e.id===state.entry);
 const id=state.playerTab==='audio'?'audio:'+p.get('species'):previous?.kind==='motion'?'motion:'+p.get('action'):'model:'+p.get('model');
 const next=library.entries.find(e=>e.id===id);
 if(next){state.entry=next.id;$('detail-title').textContent=next.title;$('detail-description').textContent=next.summary;$('detail-category').textContent=library.categories.find(c=>c.id===next.category)?.label||'';document.title=next.title+' · MCyukkuri';}
 history.replaceState(null,'',R.href(state));
 $('standalone').href='models/index.html?'+p+(event.data.tab==='audio'?'#audio':'#models');
});
window.addEventListener('popstate',()=>{clearTimeout(timer);state=R.parse(location.href);render();});
$('search-form').onsubmit=e=>{e.preventDefault();clearTimeout(timer);go(browseState({q:$('search').value.trim(),page:1}));};
$('search').oninput=()=>{clearTimeout(timer);const q=$('search').value;timer=setTimeout(()=>go(browseState({q,page:1}),true),220);};
$('clear').onclick=()=>go(browseState({q:'',category:'all',page:1}));
Promise.all(['library.json','recent.json'].map(url=>fetch(url).then(r=>{if(!r.ok)throw Error('预览目录加载失败，请刷新重试。');return r.json();}))).then(([index,manifest])=>{
 library=index;data=manifest;
 if(!library.categories.some(c=>c.id===state.category))state.category='all';
 if(state.topic){state.entry=(library.entries.find(e=>e.chapter===state.topic)||library.entries.find(e=>e.id==='reference:'+state.topic))?.id||'scene:'+state.topic;state.topic='';}
 $('version').textContent='资源 '+(data.sourceRevision||'').slice(0,8);
 history.replaceState(null,'',R.href(state));render();
}).catch(error=>{$('error').hidden=false;$('error').textContent=error.message;});
