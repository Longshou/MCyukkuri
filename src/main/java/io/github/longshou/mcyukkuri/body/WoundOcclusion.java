package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.headwear.HatDamageGeometry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Actual remaining hair and independent accessories can occlude a core ray just as they occlude its drawing. */
final class WoundOcclusion {
    private record Part(String name,int start,int count,boolean hair,boolean receiver,boolean accessory,int hiddenMask,int pregnancyGroup) {}
    private record Surface(float[] points,float[] layoutVertices,float[][] weights,List<Part> parts,Map<String,Vector3f> rigidCenters,
                           float[] hatBounds,int[] hatSeat,Map<Integer,Map<String,HatDamageGeometry.Layout[]>> hatLayouts) {}
    private static final Map<String,Surface> SURFACES=new ConcurrentHashMap<>();
    private static HatDamageGeometry.Pattern hatPattern;
    private WoundOcclusion() {}
    static void clear() { SURFACES.clear();hatPattern=null; }
    private static synchronized HatDamageGeometry.Pattern hatPattern() {
        if(hatPattern!=null)return hatPattern;
        var input=WoundOcclusion.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_damage.json");
        if(input==null)throw new IllegalStateException("Missing headwear damage geometry");
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            return hatPattern=HatDamageGeometry.read(JsonParser.parseReader(reader).getAsJsonObject());
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    private static Surface surface(String id) {
        return SURFACES.computeIfAbsent(id,key->{
            var input=WoundOcclusion.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_wounds/hair/"+key+".json.gz");
            if(input==null)throw new IllegalStateException("Missing wound occluders for "+key);
            try(var reader=new InputStreamReader(new GZIPInputStream(input),StandardCharsets.UTF_8)){
                var raw=JsonParser.parseReader(reader).getAsJsonObject();var points=raw.getAsJsonArray("points");
                float[] xyz=new float[points.size()];for(int i=0;i<xyz.length;i++)xyz[i]=points.get(i).getAsFloat();
                var uv=raw.getAsJsonArray("uv");float[] layoutVertices=new float[xyz.length/3*8];
                for(int i=0;i<xyz.length/3;i++){System.arraycopy(xyz,i*3,layoutVertices,i*8,3);
                    layoutVertices[i*8+3]=uv.get(i*2).getAsFloat();layoutVertices[i*8+4]=uv.get(i*2+1).getAsFloat();}
                var rows=raw.getAsJsonArray("weights");float[][] weights=new float[rows.size()][3];
                for(int i=0;i<weights.length;i++){var row=rows.get(i).getAsJsonArray();for(int k=0;k<3;k++)weights[i][k]=row.get(k).getAsFloat();}
                var bones=raw.getAsJsonArray("bones");int braid=-1;
                for(int i=0;i<bones.size();i++)if(bones.get(i).getAsJsonObject().get("name").getAsString().equals("hand_right"))braid=i;
                var parts=new ArrayList<Part>();for(var entry:raw.getAsJsonArray("parts")){var p=entry.getAsJsonObject();
                    String name=p.get("name").getAsString();int start=p.get("start").getAsInt(),bone=(int)weights[start][0];
                    boolean accessory=p.get("accessory").getAsBoolean();
                    int group=accessory?1:name.startsWith("braid_lock")&&braid>=0
                            &&(bone==braid||bones.get(bone).getAsJsonObject().get("parent").getAsInt()==braid)?2:0;
                    parts.add(new Part(name,start,p.get("count").getAsInt(),p.get("hair").getAsBoolean(),p.get("receiver").getAsBoolean(),
                            accessory,p.get("hiddenMask").getAsInt(),group));}
                var centers=new HashMap<String,Vector3f>();var counts=new HashMap<String,Integer>();
                for(var part:parts)if(!part.hair){String group=WoundProfiles.rigidGroup(key,part.name);
                    var center=centers.computeIfAbsent(group,ignored->new Vector3f());
                    for(int i=part.start;i<part.start+part.count;i++)center.add(xyz[i*3],xyz[i*3+1],xyz[i*3+2]);
                    counts.merge(group,part.count,Integer::sum);}
                centers.forEach((group,center)->center.div(counts.get(group)));
                var ranges=new ArrayList<Integer>();for(var part:parts)if(part.accessory){ranges.add(part.start);ranges.add(part.count);}
                float[] bounds=HatDamageGeometry.bounds(layoutVertices,ranges.stream().mapToInt(Integer::intValue).toArray());
                float low=bounds[1]+(bounds[4]-bounds[1])*.15f;var seat=new ArrayList<Integer>();
                for(var part:parts)if(part.accessory)for(int i=part.start;i<part.start+part.count;i++)if(xyz[i*3+1]<=low)seat.add(i);
                return new Surface(xyz,layoutVertices,weights,List.copyOf(parts),Map.copyOf(centers),bounds,
                        seat.stream().mapToInt(Integer::intValue).toArray(),new ConcurrentHashMap<>());
            }catch(IOException ex){throw new UncheckedIOException(ex);}
        });
    }
    private static Vector3f rest(Surface surface,int index) {
        int i=index*3;return new Vector3f(surface.points[i],surface.points[i+1],surface.points[i+2]);
    }
    private static Vector3f posed(Surface surface,int index,WoundSurface mesh,List<BodyWound> wounds,Matrix4f[] world,float pregnancy,Part part,int whole,WoundSurface.AttachmentLifts lifts) {
        var point=rest(surface,index);
        if(part.receiver)point=mesh.deformDisplay(point,wounds);
        else if(part.hair)WoundProfiles.thermalPoint(mesh.assetId,point,whole);
        else if(whole>0){
            // An independent accessory keeps its size and translates as one registered group.
            var center=surface.rigidCenters.get(WoundProfiles.rigidGroup(mesh.assetId,part.name));
            point.x+=WoundProfiles.thermalPoint(mesh.assetId,new Vector3f(center),whole).x-center.x;
        }
        var weight=surface.weights[index];var out=world[(int)weight[0]].transformPosition(point,new Vector3f());
        if(weight[1]>=0&&weight[2]>0){var second=world[(int)weight[1]].transformPosition(point,new Vector3f());out.lerp(second,weight[2]);}
        if(pregnancy>.001f){
            if(part.pregnancyGroup==1)out.add(lifts.crown());
            else if(part.pregnancyGroup==2)out.add(lifts.braid());
            else LiveBirthGeometry.deform(out,pregnancy,0);
        }
        return out;
    }
    static boolean blocked(YukkuriEntity entity,WoundSurface mesh,BodyCavity.Opening opening,Vector3f origin,Vector3f direction,
                           Matrix4f[] world,float coreDistance) {
        var surface=surface(mesh.assetId);var wounds=entity.body().wounds();int hairLoss=entity.body().hairLoss();
        int whole=wounds.stream().filter(w->w.kind()==WoundKind.SCORCH&&w.shape()==WoundShape.WHOLE_BURN).mapToInt(BodyWound::severity).max().orElse(0);
        float pregnancy=entity.livePregnancyProgress();
        var lifts=WoundPlacement.pregnancyLifts(entity);
        int level=Headwear.visualLevel(entity.visibleHeadwear());float fit=Headwear.visualFit(entity.visibleHeadwear());
        boolean accessory=entity.hasVisibleAccessory()||level==3||entity.contactPose().equals("HAT_REMOVE");
        var seat=new Vector3f();
        if(accessory&&fit<.999f&&surface.hatSeat.length>0){
            for(var part:surface.parts)if(part.accessory)for(int index:surface.hatSeat)
                if(index>=part.start&&index<part.start+part.count)seat.add(posed(surface,index,mesh,wounds,world,pregnancy,part,whole,lifts));
            seat.div(surface.hatSeat.length);
        }
        for(var part:surface.parts){
            if(part.accessory&&!accessory||(hairLoss&part.hiddenMask)!=0)continue;
            if(part.accessory&&level>0){
                if(hatBlocked(surface,part,level,fit,seat,mesh,wounds,world,pregnancy,whole,lifts,origin,direction,coreDistance))return true;
                continue;
            }
            for(int i=part.start;i<part.start+part.count;i+=3){
                var a=posed(surface,i,mesh,wounds,world,pregnancy,part,whole,lifts);
                var b=posed(surface,i+1,mesh,wounds,world,pregnancy,part,whole,lifts);
                var c=posed(surface,i+2,mesh,wounds,world,pregnancy,part,whole,lifts);
                if(part.accessory&&fit<.999f){a.sub(seat).mul(fit).add(seat);b.sub(seat).mul(fit).add(seat);c.sub(seat).mul(fit).add(seat);}
                var ab=new Vector3f(b).sub(a);var ac=new Vector3f(c).sub(a);var h=new Vector3f(direction).cross(ac);float determinant=ab.dot(h);
                if(Math.abs(determinant)<1e-8f)continue;
                float inverse=1/determinant;var delta=new Vector3f(origin).sub(a);float u=delta.dot(h)*inverse;
                if(u<0||u>1)continue;
                var q=delta.cross(ab);float v=direction.dot(q)*inverse;if(v<0||u+v>1)continue;
                float distance=ac.dot(q)*inverse;if(distance<0||distance>=coreDistance-.0001f)continue;
                var point=rest(surface,i).mul(1-u-v).fma(u,rest(surface,i+1)).fma(v,rest(surface,i+2));
                if(!part.receiver||!cut(mesh,wounds,opening,point))return true;
            }
        }
        return false;
    }
    private static boolean hatBlocked(Surface surface,Part part,int level,float fit,Vector3f seat,WoundSurface mesh,List<BodyWound> wounds,
                                      Matrix4f[] world,float pregnancy,int whole,WoundSurface.AttachmentLifts lifts,Vector3f origin,Vector3f direction,float coreDistance) {
        var byPart=surface.hatLayouts.computeIfAbsent(level,ignored->new ConcurrentHashMap<>());
        var layouts=byPart.computeIfAbsent(part.name,ignored->HatDamageGeometry.layout(surface.layoutVertices,part.start,part.count,part.name,surface.hatBounds,level,hatPattern()));
        var face=new Vector3f[4];var cell=new Vector3f[]{new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
        var normal=new Vector3f();var temp=new Vector3f();
        for(var layout:layouts){
            if(layout.original()){
                for(int first=layout.originalStart();first<layout.originalStart()+layout.originalCount();first+=3){
                    for(int k=0;k<3;k++){face[k]=posed(surface,first+k,mesh,wounds,world,pregnancy,part,whole,lifts);if(fit<.999f)face[k].sub(seat).mul(fit).add(seat);}
                    if(BodyCavity.intersection(origin,direction,face[0],face[1],face[2])<coreDistance-.0001f)return true;
                }
                continue;
            }
            var corners=layout.corners();for(int k=0;k<4;k++){face[k]=posed(surface,corners[k],mesh,wounds,world,pregnancy,part,whole,lifts);
                if(fit<.999f)face[k].sub(seat).mul(fit).add(seat);}
            for(int i=0;i<layout.n();i++)for(int j=0;j<layout.m();j++){
                if(!HatDamageGeometry.cell(face,layout.n(),layout.m(),i,j,layout.code(i,j),layout.flip(),cell,normal,temp))continue;
                // A nibbled square has a real inset cap; it is not a transparent hole.
                if(BodyCavity.intersection(origin,direction,cell[0],cell[1],cell[2])<coreDistance-.0001f
                        ||BodyCavity.intersection(origin,direction,cell[0],cell[2],cell[3])<coreDistance-.0001f)return true;
            }
        }
        return false;
    }
    private static boolean cut(WoundSurface mesh,List<BodyWound> wounds,BodyCavity.Opening opening,Vector3f point) {
        if(opening.containsHair(point))return true;
        var fit=WoundProfiles.fit(mesh.assetId);var scale=fit.scale();
        for(var wound:wounds){
            if(!BodySigns.open(wound)||!mesh.valid(wound))continue;
            var n=mesh.normal(wound.triangle());if(!n.isFinite())continue;
            float normalScale=new Vector3f(n).mul(scale).length();
            if(new Vector3f(point).sub(fit.center()).dot(n)<=.15f*normalScale)continue;
            var delta=new Vector3f(point).sub(mesh.anchor(wound));float depth=delta.dot(n);
            if(Math.abs(depth)>=2.5f*normalScale)continue;
            var grade=WoundProfiles.grade(wound.shape(),wound.severity());
            var u=WoundSurface.tangent(n);var v=new Vector3f(n).cross(u);
            float cutScale=wound.shape()==WoundShape.BLUNT?.55f:1;
            float x=delta.dot(u)/(grade.radius()[0]*cutScale*wound.size()*new Vector3f(u).mul(scale).length());
            float y=delta.dot(v)/(grade.radius()[1]*cutScale*wound.size()*new Vector3f(v).mul(scale).length()*WoundProfiles.verticalScale(mesh.assetId,n));
            if(contains(grade.hairOutline(),x,y))return true;
        }
        return false;
    }
    private static boolean contains(float[][] polygon,float x,float y) {
        boolean inside=false;for(int i=0,j=polygon.length-1;i<polygon.length;j=i++){
            var a=polygon[i];var b=polygon[j];
            if((a[1]>y)!=(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])inside=!inside;
        }return inside;
    }
}
