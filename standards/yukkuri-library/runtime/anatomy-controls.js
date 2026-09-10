function mountAnatomyControls({controller,canvas,changed,view}) {
  if(!controller)return null;
  const panel=document.createElement('div');panel.className='controls';panel.id='anatomy-controls';
  panel.innerHTML='<label>伸缩部位 <select id="anatomy-part"></select></label><button id="anatomy-extend">伸出</button><button id="anatomy-retract">缩回</button><button id="anatomy-front">查看脸下</button><button id="anatomy-rear">查看油菊下方</button><span id="anatomy-status" role="status"></span><span class="note">两种部件只显现一种，切换时先缩回。</span>';
  canvas.closest('.stage').after(panel);
  const get=id=>panel.querySelector('#'+id),select=get('anatomy-part');
  for(const p of controller.config.parts)select.add(new Option(p.label,p.id));select.value=controller.selectedPart;
  const api={
    extend(id=controller.selectedPart){controller.extend(id);select.value=id;changed();},
    retract(){controller.retract();changed();},
    selectPart(id){controller.selectPart(id);select.value=id;changed();},
    state:()=>controller.state()
  };
  select.onchange=()=>api.selectPart(select.value);
  get('anatomy-extend').onclick=()=>api.extend();get('anatomy-retract').onclick=()=>api.retract();
  get('anatomy-front').onclick=()=>view(0);get('anatomy-rear').onclick=()=>view(Math.PI);
  const labels={retracted:'已缩回',extending:'伸出中',extended:'已伸出',retracting:'缩回中',switching:'切换 · 先缩回'};
  return {api,sync(){const s=controller.state();get('anatomy-status').textContent=labels[s.phase]+' · '+Math.round(s.amount*100)+'%';}};
}
