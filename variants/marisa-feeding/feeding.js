/* Pure fixed-step behavior. Food is reserved once at mouth contact, nutrition
   is credited only on swallowing. No renderer or frame-rate dependency. */
(function(scope){
const CAPACITY=8,STEP=1/120,BITE_CONTACT=.55,SWALLOW=2.18;
class Feeding {
 constructor(){this.reset();}
 reset(){this.food=0;this.fullness=0;this.state='waiting';this.time=0;this.z=13;this.mouthful=false;this.bites=0;this.accumulator=0;this.wantEat=false;this.paused=false;this.events=[];}
 fill(){const added=CAPACITY-this.food;this.food=CAPACITY;this.events.push({type:'fill',amount:added});return added;}
 empty(){this.food=0;this.events.push({type:'empty'});}
 start(){if(this.fullness>=CAPACITY)return false;this.wantEat=true;return this.food>0||this.mouthful;}
 transition(state){this.state=state;this.time=0;}
 update(dt){if(this.paused||!Number.isFinite(dt)||dt<=0)return;this.accumulator+=dt;while(this.accumulator>=STEP){this.tick(STEP);this.accumulator-=STEP;}}
 tick(dt){
  this.time+=dt;
  if(this.state==='waiting'){
   if(this.wantEat&&this.food>0&&this.fullness<CAPACITY)this.transition(this.z>-4.99?'approach':'bite');
  }else if(this.state==='approach'){
   if(this.food<=0){this.transition('waiting');return;}
   this.z=Math.max(-5,this.z-dt*4.5);
   if(this.z<=-5)this.transition('bite');
  }else if(this.state==='bite'){
   if(!this.mouthful&&this.time>=BITE_CONTACT&&this.time-dt<BITE_CONTACT){
    if(this.food<=0){this.transition('recover');return;}
    this.food--;this.mouthful=true;this.events.push({type:'take',food:this.food});
   }
   if(this.mouthful&&this.time>=SWALLOW){this.mouthful=false;this.fullness=Math.min(CAPACITY,this.fullness+1);this.bites++;this.events.push({type:'swallow',fullness:this.fullness});}
   if(this.time>=2.7){
    if(this.fullness>=CAPACITY){this.wantEat=false;this.transition('satisfied');}
    else this.transition(this.food>0?'bite':'waiting');
   }
  }else if(this.state==='recover'){
   if(this.time>=.5)this.transition('waiting');
  }else if(this.state==='satisfied'&&this.time>=1.5)this.transition('full');
 }
 snapshot(){return {food:this.food,fullness:this.fullness,state:this.state,time:this.time,z:this.z,mouthful:this.mouthful,bites:this.bites,paused:this.paused};}
}
const api={Feeding,CAPACITY,BITE_CONTACT,SWALLOW};if(typeof module!=='undefined')module.exports=api;scope.FeedingAPI=api;
})(typeof window!=='undefined'?window:globalThis);
