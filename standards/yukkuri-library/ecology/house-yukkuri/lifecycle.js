// Pure, explicit-time egg/nest state, independent of rendering and frame rate.
// Seconds and batch size are demonstration parameters, not biological rates.
const HouseLifecycle = (() => {
  function create(count=3) {
    if(!Number.isInteger(count)||count<1||count>12)throw Error('Demo egg count must be 1–12');
    return {version:1,time:0,nest:{id:'nest-1',intact:true},
      parents:[{id:'parent-marisa',species:'marisa'},{id:'parent-reimu',species:'reimu'}],
      eggs:Array.from({length:count},(_,i)=>({id:'egg-'+i,nestId:'nest-1',parentIds:['parent-marisa','parent-reimu'],species:i%2===1?'marisa':'reimu',laidAt:1+i*.8,hatchAt:7+i*2,status:'unlaid'})),young:[]};
  }
  function advance(state, time) {
    if(!Number.isFinite(time)||time<state.time)throw Error('Time must advance monotonically');
    for(const egg of state.eggs){
      if(egg.status==='unlaid' && time>=egg.laidAt)egg.status=state.nest.intact?'incubating':'cancelled';
      if(egg.status==='incubating' && time>=egg.hatchAt){
        egg.status='hatched';
        state.young.push({id:'young-'+egg.id,eggId:egg.id,nestId:egg.nestId,parentIds:[...egg.parentIds],species:egg.species,bornAt:egg.hatchAt,status:'alive'});
      }
    }
    state.time=time;return state;
  }
  function removeNest(state){
    state.nest.intact=false;
    for(const egg of state.eggs){
      if(egg.status==='incubating')egg.status='lost';
      else if(egg.status==='unlaid')egg.status='cancelled';
    }
    for(const child of state.young)child.nestId=null;
    return state;
  }
  function snapshot(time, removedAt=null,count=3){
    const state=create(count);
    if(removedAt!==null && time>=removedAt){advance(state,removedAt);removeNest(state);}
    return advance(state,time);
  }
  return {create,advance,removeNest,snapshot};
})();
if(typeof module!=='undefined')module.exports=HouseLifecycle;
