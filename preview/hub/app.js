'use strict';
const $=id=>document.getElementById(id);
let data,current,step=0,animated=true;
function el(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;}
function mode(name,url){
 const recent=name==='recent';$('recent').hidden=!recent;$('viewer').hidden=recent;$('sample-player').pause();
 document.querySelectorAll('[data-mode]').forEach(b=>b.classList.toggle('selected',b.dataset.mode===name));
 if(!recent){const src=url||('models/index.html'+(name==='audio'?'#audio':''));$('viewer-frame').src=src;$('viewer-open').href=src;}
 else $('viewer-frame').src='about:blank';
 location.hash=recent?'recent':name;
}
function renderMedia(){
 const item=current.steps[step],isLogic=item.kind==='logic',isSubtitles=item.kind==='subtitles',isAudio=item.kind==='audio';
 $('main-image').hidden=isLogic||isSubtitles||isAudio;$('logic-visual').hidden=!isLogic;$('voice-visual').hidden=!(isSubtitles||isAudio);
 $('media-frame').classList.remove('portrait');$('media-frame').style.background=item.color||'';
 $('season-name').textContent='';$('sample-player').pause();$('sample-audio').hidden=!item.audioSample;
 $('animate').hidden=!item.animation;$('animate').textContent=animated?'看静帧':'播放动作';
 if(isLogic){
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
 [...$('chapter-list').children].forEach(b=>{const selected=b.dataset.id===current.id;b.classList.toggle('active',selected);b.setAttribute('aria-selected',selected);});
 $('tag').textContent=current.tag;$('title').textContent=current.title;$('summary').textContent=current.summary;
 $('points').replaceChildren(...current.points.map(p=>el('li',p)));$('source-link').href='/'+current.source;
 $('steps').replaceChildren(...current.steps.map((item,i)=>{const b=el('button');b.append(el('small',item.animation||item.demo&&!item.still?'动作回放':item.kind==='expression'?'表情对照':item.kind==='logic'?'状态图':item.kind==='audio'?'音频':item.kind==='subtitles'?'字幕':'静态分镜'),el('strong',item.title));b.onclick=()=>showStep(i);return b;}));
 $('gallery').replaceChildren(...(current.gallery||[]).map(item=>{const f=el('figure'),a=el('a'),img=el('img');a.href=item.src;a.target='_blank';img.src=item.src;img.alt=item.label;img.loading='lazy';a.append(img);f.append(a,el('figcaption',item.label));return f;}));showStep(0);
}
$('animate').onclick=()=>{animated=!animated;renderMedia();};
$('model-link').onclick=()=>{const item=current.steps[step];if(item.action)mode('models','models/index.html?model='+encodeURIComponent(item.model||current.model)+'&action='+encodeURIComponent(item.action)+'&autoplay=1');};
$('back').onclick=()=>mode('recent');document.querySelector('.brand').onclick=e=>{e.preventDefault();mode('recent');};document.querySelectorAll('[data-mode]').forEach(b=>b.onclick=()=>mode(b.dataset.mode));
$('main-image').onerror=()=>{$('media-caption').textContent='此动作文件尚未生成，请重新运行预览生成脚本。';};
fetch('recent.json').then(r=>{if(!r.ok)throw Error('预览索引读取失败');return r.json();}).then(value=>{
 data=value;$('chapter-list').replaceChildren(...data.chapters.map((c,i)=>{const b=el('button');b.dataset.id=c.id;b.setAttribute('role','tab');b.append(el('small',String(i+1).padStart(2,'0')),document.createTextNode(c.label));b.onclick=()=>chapter(c.id);return b;}));
 $('period').textContent='2026.10.15 20:00 → '+data.sourceRevision.slice(0,8);
 $('version').textContent='更新范围：北京时间 2026 年 10 月 15 日 20:00 起，共 '+data.commitCount+' 个提交（含合并与修复）。内容版本 '+data.sourceRevision.slice(0,8)+'；页面按最终有效行为整理。';
 $('generated').textContent='生成于 '+data.generatedAt.replace('T',' ').replace('+08:00','')+'（北京时间）';
 chapter(new URLSearchParams(location.search).get('topic')||data.chapters[0].id);if(location.hash==='#audio')mode('audio');else if(location.hash==='#models')mode('models');
}).catch(error=>{$('error').hidden=false;$('error').textContent=error.message+'。请通过本地预览服务打开。';});
