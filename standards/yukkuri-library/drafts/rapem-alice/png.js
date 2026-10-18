'use strict';
const fs=require('fs'), zlib=require('zlib');
const crcTable=(()=>{const t=new Int32Array(256);for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;t[n]=c;}return t;})();
function crc32(buf){let c=-1;for(const b of buf)c=crcTable[(c^b)&255]^(c>>>8);return (c^-1)>>>0;}
function chunk(type,data){const t=Buffer.from(type,'ascii'),len=Buffer.alloc(4);len.writeUInt32BE(data.length);const crc=Buffer.alloc(4);crc.writeUInt32BE(crc32(Buffer.concat([t,data])));return Buffer.concat([len,t,data,crc]);}
function decode(file){
 const buf=fs.readFileSync(file);let off=8,w,h,depth,ctype,idat=[];
 while(off<buf.length){const len=buf.readUInt32BE(off),type=buf.toString('ascii',off+4,off+8),data=buf.subarray(off+8,off+8+len);off+=12+len;
  if(type==='IHDR'){w=data.readUInt32BE(0);h=data.readUInt32BE(4);depth=data[8];ctype=data[9];if(depth!==8||ctype!==6)throw Error('Unsupported PNG '+depth+'/'+ctype);}
  else if(type==='IDAT')idat.push(data);
  else if(type==='IEND')break;}
 const raw=zlib.inflateSync(Buffer.concat(idat));const stride=w*4;const out=Buffer.alloc(w*h*4);let pos=0;
 for(let y=0;y<h;y++){const filter=raw[pos++];const line=raw.subarray(pos,pos+stride);pos+=stride;const row=y*w*4;
  for(let x=0;x<stride;x++){const a=x>=4?out[row+x-4]:0,b=y?out[row-stride+x]:0,c=(x>=4&&y)?out[row-stride+x-4]:0;let v=line[x];
   if(filter===1)v=(v+a)&255;else if(filter===2)v=(v+b)&255;else if(filter===3)v=(v+((a+b)>>1))&255;else if(filter===4){const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);v=(v+(pa<=pb&&pa<=pc?a:pb<=pc?b:c))&255;}
   out[row+x]=v;}}
 return {w,h,data:out};
}
function encode(w,h,rgba,file){
 const raw=Buffer.alloc((w*4+1)*h);for(let y=0;y<h;y++){raw[y*(w*4+1)]=0;rgba.copy(raw,y*(w*4+1)+1,y*w*4,(y+1)*w*4);}
 const sig=Buffer.from([137,80,78,71,13,10,26,10]);
 const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(w,0);ihdr.writeUInt32BE(h,4);ihdr[8]=8;ihdr[9]=6;
 fs.writeFileSync(file,Buffer.concat([sig,chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(raw,{level:9})),chunk('IEND',Buffer.alloc(0))]));
}
module.exports={decode,encode};
