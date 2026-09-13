// Executes the same pixel renderer embedded in preview.html. No mocked drawing API.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {renderModel}=require('./software-viewer.js');
const html=fs.readFileSync(path.join(__dirname,'preview.html'),'utf8');
const data=JSON.parse(html.match(/const DATA=(.*);/)[1]);
const dir=process.argv[2];if(!dir)throw Error('Pass the E-drive test directory containing texture.rgba');
const texture=new Uint8Array(fs.readFileSync(path.join(dir,'texture.rgba')));
assert.equal(texture.length,128*128*4);
for(const [name,az,el] of [['front',0,0],['quarter',-.55,.2],['back',Math.PI,.2],['side',Math.PI/2,.2]]){
 const pixels=renderModel(data.vertices,texture,600,600,az,el,13.5);
 let count=0;for(let i=3;i<pixels.length;i+=4){assert(pixels[i]===0||pixels[i]===255);if(pixels[i])count++;}
 assert(count>70000&&count<230000,`${name} surface coverage ${count}`);
 // Across the head center there must be no holes in the front / quarter views.
 if(name==='front'||name==='quarter')for(let y=340;y<400;y++)for(let x=240;x<360;x++)assert.equal(pixels[(y*600+x)*4+3],255,`${name} crack ${x},${y}`);
 fs.writeFileSync(path.join(dir,name+'.rgba'),pixels);
 console.log('PASS actual raster',name,'opaque pixels',count);
}
