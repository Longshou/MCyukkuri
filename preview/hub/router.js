/* Shared URL state for the preview library; pure functions also run in Node. */
(function (root) {
  'use strict';
  const sections = ['all', 'models', 'motions', 'items', 'audio', 'scenes', 'guide'];
  const kinds = {models:['model'], motions:['motion'], items:['item'], audio:['audio'], scenes:['scene'], guide:['scenario','reference']};
  function parse(value) {
    const url = new URL(value, 'http://localhost');
    const p = url.searchParams;
    let section = p.get('view') || ({'#models':'models','#audio':'audio'}[url.hash]) || 'all';
    const legacy = {model:'models',motion:'motions',item:'items',scene:'scenes',audio:'audio',scenario:'guide',reference:'guide'};
    if (!p.has('view') && legacy[p.get('kind')]) section = legacy[p.get('kind')];
    return {section:sections.includes(section)?section:'all', category:p.get('category')||'all', q:p.get('q')||'', entry:p.get('entry')||'', topic:p.get('topic')||'', page:Math.min(10000,Math.max(1,Number.parseInt(p.get('page'),10)||1)), step:Math.max(0,Number.parseInt(p.get('step'),10)||0), player:p.get('player')||'', playerTab:p.get('playerTab')==='audio'?'audio':p.get('playerTab')==='models'?'models':''};
  }
  function href(state) {
    const p = new URLSearchParams();
    p.set('view', sections.includes(state.section)?state.section:'all');
    if(state.category && state.category!=='all') p.set('category',state.category);
    if(state.q) p.set('q',state.q);
    if(state.page>1) p.set('page',state.page);
    if(state.entry) p.set('entry',state.entry);
    if(state.step>0) p.set('step',state.step);
    if(state.entry && state.player) p.set('player',state.player);
    if(state.entry && state.playerTab) p.set('playerTab',state.playerTab);
    return '?'+p;
  }
  function matches(entry,state) {
    const query=state.q.trim().toLocaleLowerCase();
    return (!kinds[state.section] || kinds[state.section].includes(entry.kind)) &&
      (state.category==='all'||entry.category===state.category) &&
      (!query || [entry.title,entry.summary,entry.id,entry.model,entry.action].filter(Boolean).join(' ').toLocaleLowerCase().includes(query));
  }
  const api={parse,href,matches,sections};
  if(typeof module!=='undefined'&&module.exports) module.exports=api;
  else root.PreviewRoute=api;
})(typeof window==='undefined'?globalThis:window);
