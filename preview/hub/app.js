'use strict';
const $=id=>document.getElementById(id);
let data,library,current,step=0,animated=true,categoryId='life';
const kindNames={scene:'外观对照',model:'角色模型',motion:'动作片段',item:'物品与设施',scenario:'游戏试玩入口',audio:'声音',reference:'预览缺口'};
function el(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;}
function mode(name,url){
 const recent=name==='recent';$('recent').hidden=!recent;$('viewer').hidden=recent;$('sample-player').pause();
 document.querySelectorAll('[data-mode]').forEach(b=>b.classList.toggle('selected',b.dataset.mode===name));
 if(!recent){$('sequence-player').removeAttribute('src');const src=url||('models/index.html'+(name==='audio'?'#audio':''));$('viewer-frame').src=src;$('viewer-open').href=src;}
 else{$('viewer-frame').src='about:blank';browse();}
 location.hash=recent?'recent':name;
}
function renderMedia(){
 const item=current.steps[step],isPlayback=item.kind==='playback',isLogic=item.kind==='logic',isSubtitles=item.kind==='subtitles',isAudio=item.kind==='audio';
 $('main-image').hidden=isPlayback||isLogic||isSubtitles||isAudio;$('logic-visual').hidden=!isLogic;$('voice-visual').hidden=!(isSubtitles||isAudio);
 $('sequence-player').hidden=!isPlayback;
 if(!isPlayback)$('sequence-player').removeAttribute('src');
 $('media-frame').classList.remove('portrait');$('media-frame').style.background=item.color||'';
 $('season-name').textContent='';$('sample-player').pause();$('sample-audio').hidden=!item.audioSample;
 $('animate').hidden=!item.animation;$('animate').textContent=animated?'看静帧':'播放动作';
 if(isPlayback){
  const url=current.demoPage+(item.scene?'?scene='+encodeURIComponent(item.scene):'');
  if($('sequence-player').getAttribute('src')!==url)$('sequence-player').setAttribute('src',url);
  $('media-caption').textContent='连续动作预览 · 可暂停、慢放、拖动，点开启声音试听';
 }else if(isLogic){
  $('logic-visual').replaceChildren(el('small','规则示意 · 无独立动画'),el('h3',item.title),...item.states.map((text,i)=>{const row=el('div',undefined,'state-row');row.append(el('b',String(i+1).padStart(2,'0')),el('span',text));return row;}));
  $('media-caption').textContent='服务端状态关系；不以无关模型动作代替';
 }else if(isSubtitles){
  $('voice-visual').replaceChildren(el('small','当前对白资源 · 仅字幕'),...data.subtitleSamples.map(s=>{const line=el('div',undefined,'subtitle-sample');line.append(el('strong',s.species==='remilia'?'蕾米莉亚':'芙兰'),el('p',s.zh),el('p',s.ja,'jp'));return line;}));
  $('media-caption').textContent='新增字幕原文与翻译 · 没有独立配音';
 }else if(isAudio){
  const sample=data.audioSamples[item.audioSample];$('voice-visual').replaceChildren(el('small','当前打包音频'),el('h3',sample.zh),el('p',sample.ja,'jp'),el('p','使用下方播放器试听；普通对白没有通用说话口型。'));
  $('media-caption').textContent='真实音频与对应字幕';
 }else{
  $('main-image').src=animated&&item.animation?item.animation:item.image;$('main-image').alt=item.title+'：当前资源离线演示';
  $('media-caption').textContent=item.mediaPending?'动作尚未生成，请重新生成预览':item.kind==='expression'?'当前表情对照 · 没有专属动作':item.demo?'运行资源动作 · 布景与路径为演示安排':item.animation?'当前资源的多角色动作分镜':'动作取样静帧';
 }
 if(item.audioSample){const sample=data.audioSamples[item.audioSample];$('sample-player').src=sample.url;$('sample-caption').textContent=sample.zh+(sample.ja?' / '+sample.ja:'');}
 $('model-link').hidden=!item.action;$('model-link').textContent='在模型页控制此动作 ↗';
}
function showStep(index){
 step=index;animated=true;const item=current.steps[index];
 [...$('steps').children].forEach((b,i)=>{b.classList.toggle('active',i===index);b.setAttribute('aria-pressed',i===index);});
 $('step-number').textContent=String(index+1).padStart(2,'0');$('step-title').textContent=item.title;$('step-body').textContent=item.body;
 $('progress-dot').style.left=(index/Math.max(1,current.steps.length-1)*100)+'%';renderMedia();
}
function chapter(id){
 current=data.chapters.find(c=>c.id===id)||data.chapters[0];
 $('browse').hidden=true;$('detail').hidden=false;$('story-detail').hidden=false;$('entry-detail').hidden=true;
 $('tag').textContent=current.tag;$('title').textContent=current.title;$('summary').textContent=current.summary;
 $('points').replaceChildren(...current.points.map(p=>el('li',p)));$('source-link').hidden=!current.source;if(current.source)$('source-link').href='/'+current.source;
 $('demo-link').hidden=!current.demoPage;if(current.demoPage)$('demo-link').href=current.demoPage;
 $('steps').replaceChildren(...current.steps.map((item,i)=>{const b=el('button');b.append(el('small',item.kind==='playback'?'连续播放':item.animation||item.demo&&!item.still?'动作回放':item.kind==='expression'?'表情对照':item.kind==='logic'?'状态图':item.kind==='audio'?'音频':item.kind==='subtitles'?'字幕':'静态分镜'),el('strong',item.title));b.onclick=()=>showStep(i);return b;}));
 $('gallery').replaceChildren(...(current.gallery||[]).map(item=>{const f=el('figure'),a=el('a'),img=el('img');a.href=item.src;a.target='_blank';img.src=item.src;img.alt=item.label;img.loading='lazy';a.append(img);f.append(a,el('figcaption',item.label));return f;}));showStep(0);
}
$('animate').onclick=()=>{animated=!animated;const item=current.steps[step];$('main-image').src=animated?item.animation:item.image;$('animate').textContent=animated?'看静帧':'播放动作';};
$('model-link').onclick=()=>{const item=current.steps[step];if(item.action)mode('models','models/index.html?model='+encodeURIComponent(item.model||current.model)+'&action='+encodeURIComponent(item.action)+'&autoplay=1');};
$('back').onclick=()=>mode('recent');document.querySelector('.brand').onclick=e=>{e.preventDefault();mode('recent');};document.querySelectorAll('[data-mode]').forEach(b=>b.onclick=()=>mode(b.dataset.mode));
$('main-image').onerror=()=>{$('media-caption').textContent='此动作文件尚未生成，请重新运行预览生成脚本。';};
function route(entry){const query=new URLSearchParams();query.set('category',categoryId);if(entry)query.set('entry',entry.id);if($('search').value)query.set('q',$('search').value);if($('kind-filter').value!=='all')query.set('kind',$('kind-filter').value);history.replaceState(null,'','?'+query+'#recent');}
function browse(){
 $('browse').hidden=false;$('detail').hidden=true;$('sample-player').pause();$('sequence-player').removeAttribute('src');
 if(library){renderLibrary();route();}
}
function showEntry(entry){
 categoryId=entry.category;route(entry);
 if(entry.kind==='model'||entry.kind==='motion'){mode('models','models/index.html?model='+encodeURIComponent(entry.model)+(entry.action?'&action='+encodeURIComponent(entry.action)+'&autoplay=1':''));return;}
 if(entry.kind==='audio'){mode('audio','models/index.html?species='+encodeURIComponent(entry.species)+'#audio');return;}
 if(entry.kind==='scene'){chapter(entry.chapter);return;}
 $('browse').hidden=true;$('detail').hidden=false;$('story-detail').hidden=true;$('entry-detail').hidden=false;
 const panel=$('entry-detail');panel.replaceChildren(el('p',entry.visual||kindNames[entry.kind],'eyebrow'),el('h2',entry.title));
 if(entry.image){const img=el('img');img.src=entry.image;img.alt=entry.title;img.className=entry.visual==='物品图标'||entry.visual==='材质预览'?'asset-image pixel-image':'asset-image';panel.append(img);}
 panel.append(el('p',entry.summary,'summary'));
 if(entry.kind==='reference'||entry.kind==='item'&&!entry.image){
  const button=el('button','查看本分类的游戏试玩入口','source-button');
  button.onclick=()=>{$('kind-filter').value='scenario';$('search').value='';browse();};panel.append(button);
 }
 if(entry.kind==='scenario'){
  panel.append(el('p','打开“启动动作博物馆.cmd”，到「'+entry.field+'」选择「'+entry.title+'」。','play-instruction'));
  const info=el('dl');for(const [label,value] of [['预计观察时间',entry.wait],['重开与重试',entry.retry],['场景说明',entry.note]]){if(value)info.append(el('dt',label),el('dd',value));}panel.append(info);
  panel.append(el('p','场景标识：'+entry.sceneId,'resource-id'));
 }
 if(entry.source){const link=el('a','查看所属专题 ↗','source-button');link.href='/'+entry.source;link.target='_blank';panel.append(link);}
}
function renderLibrary(){
 if(!library)return;
 const query=$('search').value.trim().toLowerCase(),kind=$('kind-filter').value;
 const matches=entry=>(kind==='all'||entry.kind===kind)&&(!query||(entry.title+' '+entry.summary+' '+entry.id).toLowerCase().includes(query));
 const categories=library.categories.filter(c=>library.entries.some(e=>e.category===c.id));
 $('category-list').replaceChildren(...[{id:'all',label:'全部游戏内容'},...categories].map(c=>{
  const count=library.entries.filter(e=>(c.id==='all'||e.category===c.id)&&matches(e)).length;
  const button=el('button');button.className=c.id===categoryId?'active':'';button.setAttribute('aria-pressed',c.id===categoryId);
  button.append(el('span',c.label),el('small',String(count)));button.onclick=()=>{categoryId=c.id;renderLibrary();route();};return button;
 }));
 const category=categories.find(c=>c.id===categoryId);
 $('category-title').textContent=category?.label||'全部游戏内容';$('category-description').textContent=category?.description||'按游戏系统归档的分镜、模型、物品、声音与试玩入口。';
 const entries=library.entries.filter(e=>(categoryId==='all'||e.category===categoryId)&&matches(e));
 $('result-count').textContent=entries.length+' 项';$('empty-results').hidden=entries.length>0;
 $('entry-grid').replaceChildren(...entries.map(entry=>{
  const card=el('button',undefined,'entry-card');
  if(entry.image){const img=el('img');img.src=entry.image;img.alt='';img.loading='lazy';if(entry.visual==='物品图标'||entry.visual==='材质预览')img.className='pixel-image';card.append(img);}
  else card.append(el('div',entry.kind==='scenario'?'进入游戏观察':entry.kind==='audio'?'♪':entry.kind==='reference'?'预览缺口':'行为与状态','card-placeholder'));
  const copy=el('div',undefined,'card-copy');copy.append(el('small',entry.visual||kindNames[entry.kind]),el('h3',entry.title),el('p',entry.summary));card.append(copy);card.onclick=()=>showEntry(entry);return card;
 }));
}
$('detail-back').onclick=browse;
$('search').oninput=()=>{categoryId='all';renderLibrary();route();};$('kind-filter').onchange=()=>{renderLibrary();route();};
Promise.all(['recent.json','library.json'].map(url=>fetch(url).then(r=>{if(!r.ok)throw Error('预览索引读取失败');return r.json();}))).then(([value,index])=>{
 data=value;library=index;
 const params=new URLSearchParams(location.search);categoryId=params.get('category')||'life';
 $('search').value=params.get('q')||'';if(params.get('kind') in kindNames)$('kind-filter').value=params.get('kind');
 if(!library.categories.some(c=>c.id===categoryId)&&categoryId!=='all')categoryId='life';
 const counts=kind=>library.entries.filter(e=>e.kind===kind).length;
 $('library-summary').textContent=library.categories.filter(c=>library.entries.some(e=>e.category===c.id)).length+' 个游戏分类 · '+counts('model')+' 种形态 · '+counts('motion')+' 个动作片段 · '+counts('item')+' 件物品与设施 · '+counts('scenario')+' 个试玩入口 · '+counts('reference')+' 项专项预览缺口';
 $('version').textContent='资源提交 '+data.sourceRevision.slice(0,8)+(data.workingChanges?'，含本地修改':'')+'；离线演示不代表游戏内行为验收。';
 $('generated').textContent='生成于 '+data.generatedAt.replace('T',' ').replace('+08:00','')+'（北京时间）';
 renderLibrary();const entry=library.entries.find(e=>e.id===params.get('entry')||params.get('topic')&&e.chapter===params.get('topic'));
 if(entry)showEntry(entry);else if(location.hash==='#audio')mode('audio');else if(location.hash==='#models')mode('models');
}).catch(error=>{$('error').hidden=false;$('error').textContent=error.message+'。请通过本地预览服务打开。';});
