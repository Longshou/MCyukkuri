/* Render puff frames through the production YukkuriModel and compare with pose.js. No UI.

   Needs Java 21 and the mod's runtime classpath from a successful isolated build:
   gradlew --init-script scripts/puff_checks.init.gradle ... build writePuffClasspath
   writes it to .local/puff-v7/classpath.txt (override with PUFF_CLASSPATH).
   The probe calls YukkuriModel.applyFit and applyRevision, the real constructor and render path
   with a recording VertexConsumer; it never starts Minecraft. */
const fs=require('fs'),path=require('path'),cp=require('child_process'),crypto=require('crypto');
const {RuntimePose,puffAmount}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const out=path.join(root,process.env.PUFF_PROBE_DIR||'.local/puff-v7/java-probe');fs.mkdirSync(out,{recursive:true});
const classpath=fs.readFileSync(path.join(root,process.env.PUFF_CLASSPATH||'.local/puff-v7/classpath.txt'),'utf8').trim();
const runtime=process.env.PUFF_RUNTIME?path.resolve(process.env.PUFF_RUNTIME):path.join(assets,'yukkuri_presentation/motion_corrections.json');
const fitRuntime=process.env.PLACEMENT_FIT?path.resolve(process.env.PLACEMENT_FIT):path.join(assets,'yukkuri_presentation/placement_fit.json'),fits=JSON.parse(fs.readFileSync(fitRuntime)).models;
const java=`package io.github.longshou.mcyukkuri.client;
import com.google.gson.*;import com.mojang.blaze3d.vertex.*;import java.nio.file.*;
public final class PuffRenderProbe {
 static final class Recorder implements VertexConsumer {
  final StringBuilder out=new StringBuilder();int count;
  public VertexConsumer addVertex(float x,float y,float z){if(count++>0)out.append(',');out.append(x).append(',').append(y).append(',').append(z);return this;}
  public VertexConsumer setColor(int r,int g,int b,int a){return this;}
  public VertexConsumer setUv(float u,float v){return this;}
  public VertexConsumer setUv1(int u,int v){return this;}
  public VertexConsumer setUv2(int u,int v){return this;}
  public VertexConsumer setNormal(float x,float y,float z){return this;}
 }
 public static void main(String[] args)throws Exception{
  JsonObject motions=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject().getAsJsonObject("models");
  String id=args[3];byte[] bytes=Files.readAllBytes(Path.of(args[0],"yukkuri",id+".json"));JsonObject data=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
  JsonObject fit=JsonParser.parseString(Files.readString(Path.of(args[4]))).getAsJsonObject().getAsJsonObject("models").getAsJsonObject(id);
  if(fit!=null)YukkuriModel.applyFit(id,data,fit,io.github.longshou.mcyukkuri.body.ModelFingerprint.of(bytes));
  YukkuriModel.applyRevision(id,data,motions.getAsJsonObject(id));
  var make=YukkuriModel.class.getDeclaredConstructor(JsonObject.class,String.class);make.setAccessible(true);
  YukkuriModel model=make.newInstance(data,"probe");
  var expression=YukkuriModel.class.getDeclaredMethod("setExpression",String.class);expression.setAccessible(true);
  var envelopes=YukkuriModel.class.getDeclaredField("puffEnvelopes");envelopes.setAccessible(true);
  StringBuilder json=new StringBuilder("{\\"frames\\":[");
  for(int i=5;i<args.length;i++){
   String[] spec=args[i].split(":");float time=Float.parseFloat(spec[1]);
   expression.invoke(model,model.expressionFor(spec[0],time,"neutral"));
   var recorder=new Recorder();model.render(spec[0],time,new PoseStack(),recorder,0,0,true,"idle",0,0);
   expression.invoke(model,"");
   if(i>5)json.append(',');json.append("{\\"action\\":\\"").append(spec[0]).append("\\",\\"time\\":").append(time).append(",\\"points\\":[").append(recorder.out).append("]}");
  }
  json.append("],\\"envelope\\":[");
  float[] e=((java.util.Map<String,float[]>)envelopes.get(model)).get("puff");
  // v10 puffs are root-transform clips without an envelope; the cheek morph path is then idle.
  if(e!=null)for(int k=0;k<=640;k++){if(k>0)json.append(',');json.append(YukkuriModel.puffEnvelope(e,k*.01f));}
  Files.writeString(Path.of(args[2]),json.append("]}").toString());
 }
}`;
const source=path.join(out,'io/github/longshou/mcyukkuri/client/PuffRenderProbe.java');fs.mkdirSync(path.dirname(source),{recursive:true});fs.writeFileSync(source,java);
const tool=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+'.exe'):name;
cp.execFileSync(tool('javac'),['-nowarn','-cp',classpath,'-d',path.join(out,'classes'),source],{stdio:['ignore','ignore','inherit']});
const models=JSON.parse(fs.readFileSync(runtime)).models,ids=(process.env.PUFF_IDS||Object.keys(models).join(',')).split(',');
const frames=[['puff',.2],['puff',.7],['puff',1.4],['puff',2.0],['puff',2.6],['taiso',11.2],['idle',0],['waddle',.3],['press',1.2],['hop',.4]];
const report={createdAt:new Date().toISOString(),scope:'Production YukkuriModel.applyFit, applyRevision, constructor and render() driven offline with a recording VertexConsumer; compared triangle by triangle with preview/review/pose.js. No Minecraft client, lighting or GPU.',models:[]};
let worst=0,triangles=0,envelopeError=0;
for(const id of ids){
 const file=path.join(out,id+'.json');
 cp.execFileSync(tool('java'),['-cp',path.join(out,'classes')+path.delimiter+classpath,'io.github.longshou.mcyukkuri.client.PuffRenderProbe',assets,runtime,file,id,fitRuntime,...frames.map(f=>f.join(':'))],{stdio:['ignore','ignore','inherit']});
 const result=JSON.parse(fs.readFileSync(file)),model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json'))),pose=new RuntimePose(model,1,models[id],fits[id]||null);
 let modelWorst=0,count=0;
 result.frames.forEach((frame,f)=>{
  pose.expression(pose.expressionFor(frame.action,frame.time));const data=pose.frame(frame.action,frame.time),expected=[];
  // Java draws in part order and swaps the puff meshes in place; pose.js appends them.
  const puffing=puffAmount(pose.clips[frame.action],frame.time)>0;
  pose.model.parts.forEach((original,pi)=>{const part=puffing?(pose.puffSurface?.part_index===pi?pose.puffSurface:pose.puffAttachments.get(pi)||original):original;
   for(let k=part.start*8;k<(part.start+part.count)*8;k+=24){const a=[0,1,2].map(i=>data[k+8+i]-data[k+i]),b=[0,1,2].map(i=>data[k+16+i]-data[k+i]);
    const n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];if(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]<1e-16)continue;
    expected.push([0,1,2].map(j=>[data[k+j*8],data[k+j*8+1],data[k+j*8+2]]));}});
  const quads=frame.points.length/12;
  if(quads!==expected.length)throw Error(`${id} ${frame.action}@${frame.time}: Java ${quads} triangles, preview ${expected.length}`);
  for(let q=0;q<quads;q++)for(let j=0;j<3;j++)for(let a=0;a<3;a++){
   const error=Math.abs(frame.points[q*12+j*3+a]*16-expected[q][j][a]);modelWorst=Math.max(modelWorst,error);}
  count+=quads;
 });
 const clip=pose.clips.puff;result.envelope.forEach((v,k)=>{envelopeError=Math.max(envelopeError,Math.abs(v-puffAmount(clip,k*.01)));}); // empty without an envelope
 if(modelWorst>2e-3)throw Error(`${id}: Java/preview vertex mismatch ${modelWorst}`);
 worst=Math.max(worst,modelWorst);triangles+=count;report.models.push({id,frames:frames.length,triangles:count,maximumError:modelWorst});console.log(id,count,'triangles, max error',modelWorst.toExponential(2));
}
if(envelopeError>1e-5)throw Error('Envelope mismatch '+envelopeError);
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
Object.assign(report,{triangles,maximumError:worst,envelopeSamples:641,envelopeMaximumError:envelopeError,runtimeSha256:hash(runtime),placementFitSha256:hash(fitRuntime),
 javaSha256:hash(path.join(root,'src/main/java/io/github/longshou/mcyukkuri/client/YukkuriModel.java')),poseSha256:hash(path.join(root,'preview/review/pose.js')),scriptSha256:hash(__filename)});
const dest=path.join(root,'art/source/motion-corrections/reports/current');fs.mkdirSync(dest,{recursive:true});fs.writeFileSync(path.join(dest,'java-parity.json'),JSON.stringify(report,null,2)+'\n');
console.log('Java parity:',report.models.length,'models,',triangles,'triangles, max error',worst,'envelope',envelopeError);
