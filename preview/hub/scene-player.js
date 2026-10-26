/* Playback of offline scene frames. It does not run game logic. */
'use strict';
window.ScenePlayer={mount(container,item){
 const p=item.playback,root=document.createElement('section'),canvas=document.createElement('canvas');
 root.className='scene-player';canvas.width=p.width;canvas.height=p.height;canvas.setAttribute('role','img');canvas.setAttribute('aria-label',item.title);
 const controls=document.createElement('div');controls.className='scene-controls';
 const button=document.createElement('button');button.type='button';button.textContent='暂停';button.disabled=true;
 const restart=document.createElement('button');restart.type='button';restart.textContent='重播';restart.disabled=true;
 const seek=document.createElement('input');seek.type='range';seek.min=0;seek.max=p.duration;seek.step=p.duration/(p.frames-1);seek.value=0;seek.setAttribute('aria-label','场景播放进度');seek.disabled=true;
 const clock=document.createElement('span'),caption=document.createElement('p');caption.className='scene-beat';caption.textContent='正在加载场景…';
 const speed=document.createElement('select');speed.setAttribute('aria-label','播放速度');
 for(const rate of [.5,1,1.5]){const option=document.createElement('option');option.value=rate;option.textContent=rate+'×';option.selected=rate===1;speed.append(option);}
 controls.append(button,restart,seek,clock,speed);root.append(canvas,caption,controls);
 if(item.animation){const download=document.createElement('a');download.href=item.animation;download.download='';download.textContent='下载 GIF';download.className='scene-download';root.append(download);}
 container.append(root);
 const image=new Image(),ctx=canvas.getContext('2d');let disposed=false,playing=!matchMedia('(prefers-reduced-motion: reduce)').matches,time=0,previous=0,raf=0;
 function draw(){
  const frame=Math.min(p.frames-1,Math.floor(time/p.duration*(p.frames-1)));
  ctx.clearRect(0,0,p.width,p.height);ctx.drawImage(image,(frame%p.columns)*p.width,Math.floor(frame/p.columns)*p.height,p.width,p.height,0,0,p.width,p.height);
  seek.value=time;clock.textContent=time.toFixed(1)+' / '+p.duration+' 秒';
  const beat=(item.beats||[]).filter(b=>b.start<=time).at(-1);caption.textContent=beat?.title||item.title;
  button.textContent=playing?'暂停':'播放';
 }
 function tick(now){if(disposed)return;if(playing&&!document.hidden){time+=(now-previous)/1000*Number(speed.value);if(time>p.duration)time%=p.duration;draw();}previous=now;raf=requestAnimationFrame(tick);}
 button.onclick=()=>{playing=!playing;draw();};restart.onclick=()=>{time=0;playing=true;draw();};seek.oninput=()=>{time=Number(seek.value);draw();};
 const onVisibility=()=>{previous=performance.now();};document.addEventListener('visibilitychange',onVisibility);
 image.onload=()=>{if(disposed)return;if(image.width<p.columns*p.width||image.height<Math.ceil(p.frames/p.columns)*p.height){image.onerror();return;}button.disabled=restart.disabled=seek.disabled=false;draw();previous=performance.now();raf=requestAnimationFrame(tick);};
 image.onerror=()=>{if(disposed)return;caption.textContent='场景帧未能加载，可下载 GIF 查看。';};image.src=p.image;
 return ()=>{disposed=true;cancelAnimationFrame(raf);document.removeEventListener('visibilitychange',onVisibility);image.onload=image.onerror=null;};
}};
