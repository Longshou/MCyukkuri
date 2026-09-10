for(const action of PROCEDURAL_ACTIONS){
  const card=document.createElement('article');card.className='card';
  const label=document.createElement('strong');label.textContent=action.label;
  const tag=document.createElement('div');tag.className='tag';tag.textContent='已确认 · 多角色复合动作 · '+action.duration+' 秒';
  const description=document.createElement('p');description.textContent=action.description;
  const links=document.createElement('p'),archive=document.createElement('a'),source=document.createElement('a');
  archive.href=action.archive;archive.textContent='下载复合动作包';archive.download=action.download_name||action.archive.split('/').pop();
  source.href='motions/procedural/index.json';source.textContent='动作登记';
  links.append(archive,document.createTextNode(' · '),source);card.append(label,tag,description,links);
  document.getElementById('compositeGrid').append(card);
}
document.getElementById('stats').append(document.createTextNode(' · '+PROCEDURAL_ACTIONS.length+' 个多角色复合动作包'));
