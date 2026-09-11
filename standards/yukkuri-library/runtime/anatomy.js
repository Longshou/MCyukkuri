// Independent of locomotion and expressions. The same state drives both standard characters.
class AnatomyController {
  constructor(config) {
    this.config=config;this.amount=0;this.targetExtended=false;
    this.selectedPart=config.default_part;this.activePart=this.selectedPart;
  }
  selectPart(id) {
    if(!this.config.parts.some(p=>p.id===id))throw new RangeError('Unknown anatomy part: '+id);
    this.selectedPart=id;
  }
  extend(id=this.selectedPart) { this.selectPart(id);this.targetExtended=true; }
  retract() { this.targetExtended=false; }
  update(dt) {
    const oldAmount=this.amount,oldPart=this.activePart;
    let remaining=Number.isFinite(dt)?Math.max(0,dt):0;
    if(this.activePart!==this.selectedPart){
      const spent=Math.min(remaining,this.amount*this.config.retract_seconds);
      this.amount=Math.max(0,this.amount-spent/this.config.retract_seconds);remaining-=spent;
      if(this.amount<1e-10){this.amount=0;this.activePart=this.selectedPart;}
    }
    if(this.activePart===this.selectedPart){
      const extending=this.targetExtended;
      const delta=remaining/this.config[extending?'extend_seconds':'retract_seconds'];
      this.amount=extending?Math.min(1,this.amount+delta):Math.max(0,this.amount-delta);
    }
    return oldAmount!==this.amount||oldPart!==this.activePart;
  }
  overrides() {
    const eased=this.amount*this.amount*(3-2*this.amount);
    return Object.fromEntries(this.config.parts.map(part=>{
      const weight=part.id===this.activePart?eased:0;
      return [part.bone,{position:part.offset.map(v=>v*weight),...(part.rotation?{rotation:part.rotation.map(v=>v*weight)}:{})}];
    }));
  }
  state() {
    const wanted=this.targetExtended;
    const phase=this.activePart!==this.selectedPart?'switching':this.amount===0&&!wanted?'retracted':this.amount===1&&wanted?'extended':wanted?'extending':'retracting';
    return {part:this.activePart,selectedPart:this.selectedPart,amount:this.amount,targetExtended:this.targetExtended,phase};
  }
}
if(typeof module!=='undefined')module.exports={AnatomyController};
