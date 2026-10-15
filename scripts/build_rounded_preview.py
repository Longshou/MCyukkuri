"""Build the file-openable view of selected Minecraft data; no model geometry is authored here."""
from pathlib import Path
import hashlib,re
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'art/source/rounded-runtime/previews/current'
def main():
    base=ROOT/'art/source/standard-runtime'
    html=(base/'preview.html').read_text(encoding='utf-8')
    ids=['reimu','marisa','chen','alice','patchouli','marisa_tsumuri','reimu_wasa','reimu_deibu','reimu_wasa_deibu']
    names=['灵梦','魔理沙','橙','爱丽丝','帕秋莉','壳魔理沙','多毛灵梦','灵胖','多毛灵胖']
    html=re.sub(r'<select id="subject">.*?</select>','<select id="subject">'+''.join(f'<option value="{i}">{n}</option>'for i,n in zip(ids,names))+'</select>',html)
    html=html.replace('灵梦、魔理沙、橙、爱丽丝、帕秋莉及两种家油。','当前选用的九种圆润游戏形态。灵胖和多毛灵胖按1.5倍实际尺寸展示。')
    html=re.sub(r'<p class="note">.*?</p>','<p class="note">读取本轮游戏运行网格、表情与动作；灵胖面容、蝴蝶结及后发修正版的美术确认状态见来源登记。本页不代替实机验收。<a href="../../README.md">接入说明</a></p>',html)
    html=html.replace('preview-data.js','models.js').replace('../../../standards/','../../../../../standards/')
    script=(base/'preview.js').read_text(encoding='utf-8')
    helpers='''function transformPoint(m,p){return [0,1,2].map(r=>m[r*4]*p[0]+m[r*4+1]*p[1]+m[r*4+2]*p[2]+m[r*4+3]);}
function inversePoint(m,p){
 const [a,b,c,,d,e,f,,g,h,i]=m,x=p[0]-m[3],y=p[1]-m[7],z=p[2]-m[11],det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
 return [((e*i-f*h)*x+(c*h-b*i)*y+(b*f-c*e)*z)/det,((f*g-d*i)*x+(a*i-c*g)*y+(c*d-a*f)*z)/det,((d*h-e*g)*x+(b*g-a*h)*y+(a*e-b*d)*z)/det];
}
'''
    script=script.replace('function posed(model,action,t){',helpers+'function posed(model,action,t){')
    script=script.replace('const out=[],src=model.vertices;',"const out=[],src=model.vertices.slice();for(const p of model.expression_patches?.[$('expression').value]||[])src.splice(p.start*8,p.vertices.length,...p.vertices);const scale=MODELS[$('subject').value].formScale||1;")
    script=script.replace('tri.push([...point,v[3],v[4],0,0,0]);',"if(part.feature_bone!==undefined){const bone=part.feature_bone,parent=model.bones[bone].parent,local=parent>=0?inversePoint(world[parent],point):point;point.splice(0,3,...transformPoint(world[bone],local));}tri.push([...point.map(x=>x*scale),v[3],v[4],0,0,0]);")
    # Equal camera framing retains the visual 1.5x comparison, with room for the larger body.
    script=script.replace("framing[id]={center:(low+high)/2,span:Math.max((high-low)*.95,radius*canvas.height/canvas.width*1.1)};","framing[id]={center:(low+high)/2*(entry.formScale||1),span:Math.max(18,(high-low)*.95,radius*canvas.height/canvas.width*1.1)};")
    script=script.replace('textureSize:128','textureSize:model.texture_size')
    html=html.replace('src="preview.js"','src="preview.js?v='+hashlib.sha256(script.encode('utf-8')).hexdigest()[:12]+'"')
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'index.html').write_text(html,encoding='utf-8');(OUT/'preview.js').write_text(script,encoding='utf-8')
    print('Rounded runtime preview written.')
if __name__=='__main__':main()
