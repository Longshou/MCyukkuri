// Original pixel-face prototype inspired by the separation observed in Diary.
// No APK pixels, mesh vertices, or animation curves are used.
// One implementation runs in Node exports and the interactive browser preview.
(function(root){
const SKIN=[255,231,185,255];
const presets={
  lively:{aperture:.88,gazeX:0,gazeY:-.30,mouth:.67,brow:.1},
  relaxed:{aperture:.58,gazeX:0,gazeY:0,mouth:.20,brow:0},
  curious:{aperture:.94,gazeX:-.32,gazeY:-.12,mouth:.42,brow:.35}
};
function faceAtlas(params={},base){
  const p={...presets.lively,...params};
  const a=base?new Uint8ClampedArray(base):new Uint8ClampedArray(128*128*4);
  function pixel(x,y,c){x=Math.round(x);y=Math.round(y);if(x<0||x>=96||y<0||y>=64)return;const i=(y*128+x)*4;for(let k=0;k<4;k++)a[i+k]=c[k]??255;}
  function ellipse(cx,cy,rx,ry,col){for(let y=Math.floor(cy-ry);y<=Math.ceil(cy+ry);y++)for(let x=Math.floor(cx-rx);x<=Math.ceil(cx+rx);x++)if(((x-cx)/rx)**2+((y-cy)/ry)**2<=1)pixel(x,y,col);}
  function line(x0,y0,x1,y1,c,width=1){const steps=Math.max(Math.abs(x1-x0),Math.abs(y1-y0))*2;for(let t=0;t<=Math.max(1,steps);t++){const s=t/Math.max(1,steps),x=x0+(x1-x0)*s,y=y0+(y1-y0)*s;for(let dy=0;dy<width;dy++)pixel(x,y+dy,c);}}
  for(let y=0;y<64;y++)for(let x=0;x<96;x++)pixel(x,y,SKIN);
  // Cheeks: low-contrast colour, no glitter and no harsh hatch marks.
  for(const cx of [12,83]){
    ellipse(cx,42,5.3,2.5,[248,199,168]);ellipse(cx,42,3.5,1.5,[241,184,156]);
  }
  // Eye whites, pupils and upper lids are separate compositing stages.
  for(const [cx,side] of [[27,-1],[69,1]]){
    const open=Math.max(0,Math.min(1,p.aperture));
    const top=21+(1-open)*15,bottom=40-(1-open)*2.8;
    const px=cx+p.gazeX*3,py=31+p.gazeY*4;
    if(open>.06){
      for(let y=20;y<=41;y++)for(let x=cx-11;x<=cx+11;x++){
        const dx=(x-cx)/11,dy=(y-31)/10;
        if(dx*dx+dy*dy>1||y<top+2.4*dx*dx||y>bottom)continue;
        let col=[255,244,215];
        const pupil=((x-px)/8.8)**2+((y-py)/10.5)**2;
        if(pupil<=1){
          col=[49,38,29];
          if(y>py+6)col=[86,63,41];
          // Exactly one small upper highlight, clipped to the pupil and eyelid.
          if(((x-(px-1))/2.4)**2+((y-(py-4))/1.5)**2<=1)col=[255,248,230];
        }
        pixel(x,y,col);
      }
      const ink=[74,53,36];
      // Only the upper line is strong. Avoid a closed dark/white eye ring.
      let last=null;
      for(let x=-9;x<=9;x++){
        const y=top+2.4*(x/11)**2;
        if(last)line(last[0],last[1],cx+x,y,ink,1);
        last=[cx+x,y];
      }
      line(cx+side*9,top+2,cx+side*10,top+4,ink,1);
    }else{
      line(cx-8,34,cx-3,36,[83,57,37]);line(cx-3,36,cx+3,36,[83,57,37]);line(cx+3,36,cx+8,34,[83,57,37]);
    }
    const by=17+(1-open)*3-(side<0?p.brow*2:0);
    line(cx-6,by+1,cx-1,by,[164,125,72]);line(cx-1,by,cx+6,by+1,[164,125,72]);
  }
  // A smile with a wide top and a rounded lower edge, coordinated with openness.
  const mouth=Math.max(0,Math.min(1,p.mouth)),cx=48,top=40;
  if(mouth<.08){
    line(43,41,45,43,[122,77,50]);line(45,43,48,41,[122,77,50]);line(48,41,51,43,[122,77,50]);line(51,43,53,41,[122,77,50]);
  }else{
    const width=5.2+mouth*2.3,h=2+mouth*8;
    for(let y=top;y<=top+h;y++)for(let x=cx-width;x<=cx+width;x++){
      const nx=(x-cx)/width,ny=(y-top)/h;
      if(nx*nx+ny*ny>1)continue;
      const edge=nx*nx+ny*ny>.75||y===top;
      const col=edge?[143,62,42]:[208,65,44];
      pixel(x,y,col);
    }
  }
  return a;
}
const api={presets,faceAtlas};root.FaceLayers=api;
if(typeof module!=='undefined')module.exports=api;
if(typeof require!=='undefined'&&require.main===module){const fs=require('node:fs');fs.writeFileSync(process.argv[3],faceAtlas(JSON.parse(process.argv[2])));}
})(typeof globalThis!=='undefined'?globalThis:this);
