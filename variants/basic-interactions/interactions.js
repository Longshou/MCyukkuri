/* Deterministic choreography. Time is seconds, distances are model units.
 * Native action names are references; timing and 3D contact are this adaptation.
 */
(function (scope) {
  const actions = {
    rub: {name:'擦擦脸 · 蹭蹭', native:'すりすり動作 / すりすり顔', duration:7, pair:false, detail:'贴近脸颊，闭眼轻蹭，然后回正。'},
    nuzzle: {name:'互相蹭蹭', native:'相互すりすり', duration:9, pair:true, detail:'两只靠近，交替蹭脸，满足地分开。'},
    lick: {name:'互相舔舔', native:'相互ぺろぺろ / 餓死ぺろ顔', duration:10, pair:true, detail:'靠近同伴，轮流伸舌舔脸，收舌后退开。'},
    accessory: {name:'舔舔饰品', native:'お飾りぺろぺろ', duration:8, pair:false, detail:'照料放在面前的饰品，舔完重新戴好。'},
    talk: {name:'温柔地说话', native:'優しい言葉動作', duration:6, pair:false, detail:'听见招呼，抬脸回应，露出放松的笑容。'},
    hold: {name:'轻轻抱起', native:'持ち上げ動作', duration:7, pair:false, detail:'从底部托起，平稳停留，再轻轻放回地面。'}
  };
  const clamp = x=>Math.max(0,Math.min(1,x));
  const smooth = x=>{x=clamp(x);return x*x*(3-2*x);};
  const ramp = (t,a,b)=>smooth((t-a)/(b-a));
  const envelope = (t,a,b,c,d)=>ramp(t,a,b)*(1-ramp(t,c,d));
  function actor(x=0) {return {x,y:0,z:0,yaw:0,lean:0,tilt:0,face:'rest',tongue:0,tongueAngle:0,hat:0,hatX:0,hatY:0,hatZ:0};}
  function sample(id, time) {
    if (!actions[id]) throw new Error('Unknown action: '+id);
    const a=actions[id],t=Math.max(0,Math.min(a.duration,time));
    const pair=a.pair,A=actor(pair?-15:0),B=actor(15),e=envelope(t,.5,1.5,a.duration-1.6,a.duration-.4);
    const s={id,time:t,duration:a.duration,actors:[A,B],pair,phase:'准备',speech:'ゆ～',hand:0,heart:0,active:0};
    if(t>=a.duration-.4){s.phase='完成 · 已恢复';s.speech='悠闲一下吧～';return s;}
    if(id==='rub') {
      const w=Math.sin((t-1.5)*Math.PI*2/1.16);
      A.lean=e*(.2+.42*w);A.tilt=e*.035*w;A.face=e>.2?'smile':'rest';
      s.hand=e;s.handMode='cheek';s.heart=envelope(t,4.8,5.4,5.8,6.5);
      s.phase=t<1.5?'贴近脸颊':t<5.4?'左右轻蹭':'满足 · 回正';s.speech=t<1.5?'来擦擦脸吧。':t<5.4?'蹭蹭～好舒服呀！':'ゆゆ～';
    } else if(pair) {
      const move=envelope(t,0,1.8,a.duration-2,a.duration-.4);
      // Forward is -Z. An oblique cheek contact exposes both faces to the camera.
      const yaw=id==='nuzzle'?.62:1.18;
      const near=id==='nuzzle'?7.8:7;
      A.x=-15+(15-near)*move;B.x=15-(15-near)*move;
      A.yaw=-yaw*move;B.yaw=yaw*move;
      const contact=envelope(t,1.8,2.3,a.duration-2.4,a.duration-2);
      A.face=contact>.1?'smile':'rest';B.face=A.face;
      if(id==='nuzzle') {
        const w=Math.sin((t-2.3)*Math.PI*2/1.16);
        A.lean=.34*contact*w;B.lean=-.34*contact*w;
        A.tilt=.025*contact*w;B.tilt=-.025*contact*w;
        s.phase=t<1.8?'互相靠近':t<a.duration-2?'贴脸 · 交替蹭蹭':'满足 · 分开';
        s.speech=t<1.8?'一起悠闲吧！':t<a.duration-2?'蹭蹭～ゆゆ～':'真悠闲呢！';
      } else {
        const active=t<5?0:1,local=t<5?t-2.3:t-5;
        const licking=envelope(t,2,2.4,7.2,7.7);
        const pulse=Math.pow(Math.max(0,Math.sin(local*Math.PI*2/1.05)),.7)*licking;
        s.active=active;s.actors[active].tongue=pulse;s.actors[active].tongueAngle=Math.sin(local*6)*.24;
        s.actors[active].face=licking>.1?'lick':'rest';
        // Move the active muzzle forward only within the contact window.
        s.actors[active].z=-.2*licking;
        s.phase=t<1.8?'互相靠近':t<5?'第一只 · 舔舔脸':t<7.7?'交换 · 回舔':'收舌 · 分开';
        s.speech=t<1.8?'来帮你舔舔脸。':t<5?'舔舔～':t<7.7?'这次换我啦～':'ゆ～谢谢你！';
      }
      s.heart=envelope(t,a.duration-2.4,a.duration-1.9,a.duration-1.1,a.duration-.5);
      const walking=Math.sin(t*14)*.07*(1-contact)*move;
      A.y=Math.abs(walking);B.y=Math.abs(walking);
    } else if(id==='accessory') {
      const off=envelope(t,0,1.8,6.1,7.6);
      A.hat=off;A.yaw=-.85*off;A.x=-5*off;
      const licking=envelope(t,1.8,2.3,5.8,6.1);
      A.face=licking>.1?'lick':'rest';A.tongue=Math.pow(Math.max(0,Math.sin((t-2.3)*6)),.7)*licking;
      A.tongueAngle=Math.sin(t*6)*.24;A.lean=.08*licking*Math.sin(t*6);
      s.phase=t<1.8?'把饰品放到面前':t<6.1?'舔舔 · 照料饰品':'收舌 · 戴回饰品';
      s.speech=t<1.8?'是重要的饰品呢。':t<6.1?'舔舔～要好好爱护！':'又整整齐齐啦～';s.heart=envelope(t,6.1,6.5,7,7.5);
    } else if(id==='talk') {
      const reply=envelope(t,1.3,1.8,4.6,5.5);
      A.face=reply>.1?'smile':'rest';A.tilt=.025*Math.sin(t*4)*reply;
      A.lean=.15*Math.sin(t*3)*reply;s.heart=envelope(t,3.4,4,4.7,5.4);
      s.phase=t<1.3?'听见招呼':t<4.6?'抬脸 · 回应':'放松 · 回正';
      s.speech=t<1.3?'今天也要悠闲哦。':t<4.6?'ゆゆ！你也要悠闲哦～':'ゆ～';
    } else if(id==='hold') {
      const lift=envelope(t,.7,2,4.7,6.2);
      A.y=5*lift;A.face=t>2&&t<4.7?'smile':'rest';s.hand=e;s.handMode='support';
      s.phase=t<.7?'双手托住':t<2?'缓缓抱起':t<4.7?'平稳停留':t<6.2?'轻轻放下':'落地 · 回正';
      s.speech=t<2?'轻轻抱起来。':t<4.7?'ゆ～看得更远啦！':'慢慢放好～';
    }
    return s;
  }
  class Player {
    constructor(){this.id='rub';this.time=0;this.playing=true;this.loop=false;this.speed=1;}
    select(id){if(!actions[id])throw new Error('Unknown action');this.id=id;this.time=0;this.playing=true;}
    seek(t){this.time=Math.max(0,Math.min(actions[this.id].duration,t));this.playing=false;}
    update(dt){if(!this.playing||!Number.isFinite(dt)||dt<0)return;this.time+=dt*this.speed;const d=actions[this.id].duration;if(this.time>=d){if(this.loop)this.time%=d;else{this.time=d;this.playing=false;}}}
    snapshot(){return sample(this.id,this.time);}
  }
  scope.Interactions={actions,sample,Player};
  if(typeof module!=='undefined')module.exports=scope.Interactions;
})(typeof window==='undefined'?globalThis:window);
