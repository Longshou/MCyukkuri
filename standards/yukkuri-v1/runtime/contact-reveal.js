// Contact drives visibility only; independent of the animation playback clock.
class ContactReveal {
  constructor(config) {
    this.config=config; this.amount=0; this.touching=false;
    this.selectedPart=config.default_part||config.parts[0].id;
    this.activePart=this.selectedPart;
  }
  setContact(touching) { this.touching=!!touching; }
  selectPart(id) {
    if(!this.config.parts.some(p=>p.id===id))throw new RangeError('Unknown contact part: '+id);
    this.selectedPart=id;
  }
  update(dt) {
    const previous=this.amount,previousPart=this.activePart;
    // Fully retract before changing sides. Never crossfade two exposed parts.
    const revealing=this.touching&&this.activePart===this.selectedPart;
    const duration=revealing?this.config.reveal_seconds:this.config.restore_seconds;
    const delta=Math.max(0,Number.isFinite(dt)?dt:0)/duration;
    this.amount=revealing?Math.min(1,this.amount+delta):Math.max(0,this.amount-delta);
    if(this.amount===0)this.activePart=this.selectedPart;
    return previous!==this.amount||previousPart!==this.activePart;
  }
  overrides() {
    const q=this.amount*this.amount*(3-2*this.amount);
    return Object.fromEntries(this.config.parts.map(p=>{
      const weight=p.id===this.activePart?q:0;
      return [p.bone,{position:p.offset.map(v=>v*weight),...(p.rotation?{rotation:p.rotation.map(v=>v*weight)}:{})}];
    }));
  }
}
if(typeof module!=='undefined')module.exports={ContactReveal};
