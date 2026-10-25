package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyCavity;
import io.github.longshou.mcyukkuri.body.BodyLife;
import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.WoundKind;
import io.github.longshou.mcyukkuri.body.WoundProfiles;
import io.github.longshou.mcyukkuri.body.WoundShape;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.headwear.HatDamageGeometry;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Authored profiles reprojected onto real skin, hair and face triangles at one authoritative site. */
final class HairWoundRenderer {
    record VisibleWound(UUID id,WoundKind kind,WoundShape shape,int triangle,float u,float v,int severity,float size) {
        VisibleWound(BodyWound wound){this(wound.id(),wound.kind(),wound.shape(),wound.triangle(),wound.u(),wound.v(),wound.severity(),wound.size());}
        /** Same comparison as record equality (float bits), without building a record. */
        boolean same(BodyWound wound){return id.equals(wound.id())&&kind==wound.kind()&&shape==wound.shape()&&triangle==wound.triangle()
                &&Float.compare(u,wound.u())==0&&Float.compare(v,wound.v())==0&&severity==wound.severity()&&Float.compare(size,wound.size())==0;}
    }
    /** Everything that selects the topology of a prepared body except the face expression. */
    private record Key(String asset,List<VisibleWound> wounds,int signs,int hairLoss,
                       boolean accessory,int headwear,boolean corePresent,boolean exposed) {
        boolean same(String asset,List<BodyWound> list,int signs,int hairLoss,boolean accessory,int headwear,boolean corePresent){
            if(!this.asset.equals(asset)||this.signs!=signs||this.hairLoss!=hairLoss||this.accessory!=accessory||this.headwear!=headwear
                    ||this.corePresent!=corePresent||wounds.size()!=list.size())return false;
            for(int i=0;i<list.size();i++)if(!wounds.get(i).same(list.get(i)))return false;
            return true;
        }
    }
    /**
     * One owner's prepared body. Expression patches only move face parts, so every variant shares the expensive
     * expression independent work (cuts, cavity, hair and body decals, wall rings) of the first one.
     */
    private static final class Entry {
        final Key key;
        final Prepared shared;
        final Map<Object,Prepared> variants=new LinkedHashMap<>(MAX_VARIANTS,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<Object,Prepared> eldest){return size()>MAX_VARIANTS;}
        };
        boolean weak;
        Entry(Key key,Prepared shared,Object expression,boolean weak){this.key=key;this.shared=shared;this.weak=weak;variants.put(expression,shared);}
    }
    private static final int MAX_CACHED_BODIES=32,MAX_VARIANTS=8;
    private static final Map<Object,Entry> CACHE=new LinkedHashMap<>(MAX_CACHED_BODIES,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Object,Entry> eldest){return size()>MAX_CACHED_BODIES;}
    };
    static void clear(){CACHE.clear();}
    static Prepared prepare(Object owner,YukkuriModel model,String asset,String expression,List<BodyWound> wounds,
                            int signs,float health,int hairLoss,boolean accessory,int headwear,boolean corePresent){
        // Leakage clocks and hat regrowth percentages do not change topology.
        // The current fit and bone pose still apply every rendered frame.
        int level=Headwear.visualLevel(headwear);
        boolean weak=health<=BodyLife.WEAK_HEALTH_RATIO;
        var geometry=model.woundExpressionKey(expression);
        var old=CACHE.get(owner);
        // Common frame: nothing but possibly the expression changed, so no key, core opening or list is built.
        if(old!=null&&old.weak==weak&&old.key.same(asset,wounds,signs,hairLoss,accessory,level,corePresent)){
            var hit=old.variants.get(geometry);if(hit!=null)return hit;
        }
        var mesh=WoundSurface.get(asset);
        var core=BodySigns.coreWound(mesh,wounds,(signs&BodySigns.CORE_HURT)!=0,health);
        if(old!=null&&old.key.same(asset,wounds,signs,hairLoss,accessory,level,corePresent)&&old.key.exposed==(core!=null)){
            old.weak=weak;var hit=old.variants.get(geometry);if(hit!=null)return hit;
            var result=new Prepared(model,asset,wounds,signs,hairLoss,accessory,core,corePresent,old.shared);
            old.variants.put(geometry,result);return result;
        }
        var key=new Key(asset,wounds.stream().map(VisibleWound::new).toList(),signs,hairLoss,accessory,level,corePresent,core!=null);
        var result=new Prepared(model,asset,wounds,signs,hairLoss,accessory,core,corePresent,null);
        CACHE.put(owner,new Entry(key,result,geometry,weak));return result;
    }
    /** Triangles one part contributed, kept per part so a new expression only redoes the parts it moves. */
    private static final class PartOut {
        final Map<Integer,List<Triangle>> patches=new HashMap<>();
        final Map<ResourceLocation,List<Triangle>> layers=new LinkedHashMap<>();
    }
    private record Bary(float a,float b,float c) {
        Bary mix(Bary other,float t){return new Bary(a+(other.a-a)*t,b+(other.b-b)*t,c+(other.c-c)*t);}
    }
    private record Piece(float x,float y,Bary bary) {
        Piece mix(Piece other,float t){return new Piece(x+(other.x-x)*t,y+(other.y-y)*t,bary.mix(other.bary,t));}
    }
    private static final Bary A=new Bary(1,0,0),B=new Bary(0,1,0),C=new Bary(0,0,1);
    private static final class Corner {
        final int triangle;
        final Bary bary;
        final Vector3f rest;
        final float u,v,alpha;
        Sample skinBinding;
        float skinWeight;
        int[] quad;
        float quadU,quadV;
        Corner(int triangle,Bary bary,Vector3f rest,float u,float v,float alpha){
            this.triangle=triangle;this.bary=bary;this.rest=rest;this.u=u;this.v=v;this.alpha=alpha;
        }
    }
    private record Triangle(Corner a,Corner b,Corner c,float red,float green,float blue,float inset) {
        Triangle(Corner a,Corner b,Corner c,float red,float green,float blue){this(a,b,c,red,green,blue,0);}
    }
    private record Sample(int triangle,Bary bary,Vector3f point) {}
    private static final class Cut {
        final BodyWound wound;
        final HairWoundAssets.Model info;
        final HairWoundAssets.Profile profile;
        final Vector3f anchor,n,u,v,center;
        final float radiusU,radiusV,cutU,cutV,depth,scale;
        final float[][] skin,hair;
        final BodyCavity.Opening core;
        Cut(BodyWound wound,WoundSurface mesh,HairWoundAssets.Model model,BodyCavity.Opening core){
            this.wound=wound;this.info=model;this.core=core;profile=HairWoundAssets.profile(wound);
            anchor=mesh.anchor(wound);n=mesh.normal(wound.triangle());u=WoundSurface.tangent(n);v=new Vector3f(n).cross(u);
            center=new Vector3f(0,4.5f,.5f).mul(model.scale).add(model.offset);
            float size=wound.kind()==WoundKind.SCAR?Math.min(wound.size(),1):wound.size();
            float openingScale=wound.shape()==WoundShape.BLUNT?.76f:1;
            float vertical=WoundProfiles.verticalScale(mesh.assetId,n);
            scale=model.span(n);radiusU=core==null?profile.radiusU()*size*model.span(u):core.radiusU/openingScale;
            radiusV=core==null?profile.radiusV()*size*model.span(v)*vertical:core.radiusV/openingScale;
            cutU=core==null?profile.cutU()*size*model.span(u):core.radiusU;
            cutV=core==null?profile.cutV()*size*model.span(v)*vertical:core.radiusV;
            depth=profile.depth()*size*scale;
            skin=core==null?scaled(profile.outline(),cutU,cutV):core.polygon;
            hair=core==null?scaled(profile.hairOutline(),cutU,cutV):core.hairPolygon;
        }
        float x(Vector3f point){return new Vector3f(point).sub(anchor).dot(u);}
        float y(Vector3f point){return new Vector3f(point).sub(anchor).dot(v);}
        boolean front(Vector3f point){return new Vector3f(point).sub(center).dot(n)>.15f*scale;}
        boolean near(Vector3f point){return front(point)&&Math.abs(new Vector3f(point).sub(anchor).dot(n))<2.5f*scale;}
        boolean near(Vector3f point,String part){return core!=null&&info.coreTunnel.contains(part)||near(point);}
        boolean open(){return wound.kind()!=WoundKind.SCAR&&profile.open();}
        boolean dent(){return wound.kind()!=WoundKind.SCAR&&wound.shape()==WoundShape.BLUNT;}
        boolean bounds(Vector3f a,Vector3f b,Vector3f c,float extra,String part){
            float ax=x(a),bx=x(b),cx=x(c),ay=y(a),by=y(b),cy=y(c);
            return Math.min(ax,Math.min(bx,cx))<=radiusU*extra&&Math.max(ax,Math.max(bx,cx))>=-radiusU*extra
                    &&Math.min(ay,Math.min(by,cy))<=radiusV*extra&&Math.max(ay,Math.max(by,cy))>=-radiusV*extra
                    &&(near(a,part)||near(b,part)||near(c,part)||near(new Vector3f(a).add(b).add(c).div(3),part));
        }
    }
    static final class Prepared {
        final HairWoundAssets.Model info;
        final List<Cut> cuts;
        final Map<Integer,List<Triangle>> patches=new HashMap<>();
        final Map<ResourceLocation,List<Triangle>> layers=new LinkedHashMap<>();
        final Map<String,Float> rigidOffsets;
        final Map<String,YukkuriModel.Part> parts;
        final Map<Integer,String> triangleParts;
        final YukkuriModel model;
        final WoundSurface mesh;
        /** The model's live vertices, read only while the constructor runs. */
        private float[] source;
        /** What each part (by start, and TAIL for the cavity walls) drew; shared by the variants of one body. */
        private final Map<Integer,PartOut> outputs;
        private PartOut current;
        private static final int TAIL=-1;
        /** Only face parts move with the expression, so a variant may reuse what was drawn on every other part. */
        private final boolean reusable;
        final Vector3f[] display;
        final List<BodyWound> wounds;
        final int whole,sole;
        final CavityRenderer cavity;
        final Vector3f[] cavitySkin;
        final Vector3f[] cavityRest;
        final int[] cavityIndices;
        final Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f(),normal=new Vector3f(),edge=new Vector3f();
        final Vector3f skinPoint=new Vector3f();
        final Vector3f[] posedTriangle={a,b,c};
        final float[] triangleUv=new float[6];
        Prepared(YukkuriModel model,String asset,List<BodyWound> wounds,int signs,int hairLoss,boolean accessory,
                 BodyWound coreWound,boolean corePresent,Prepared donor){
            this.model=model;mesh=WoundSurface.get(asset);info=HairWoundAssets.model(asset);
            // The expression is already applied to the live vertices and the constructor never changes them.
            source=model.woundVertices();
            display=new Vector3f[source.length/8];
            boolean isolated=true;
            for(var part:model.woundParts())if(model.woundExpressionPart(part)&&!(part.name().startsWith("face_")&&!info.hair.contains(part.name())
                    &&!part.name().equals(info.body)&&!info.thermalHair.contains(part.name())&&!info.rigid.containsKey(part.name())))isolated=false;
            reusable=isolated;
            if(donor!=null&&!(donor.reusable&&isolated))donor=null;
            if(donor!=null){
                this.wounds=donor.wounds;whole=donor.whole;sole=donor.sole;parts=donor.parts;triangleParts=donor.triangleParts;
                cuts=donor.cuts;rigidOffsets=donor.rigidOffsets;outputs=donor.outputs;
                cavity=donor.cavity;cavitySkin=donor.cavitySkin;cavityRest=donor.cavityRest;cavityIndices=donor.cavityIndices;
            }else{
                this.wounds=List.copyOf(wounds);whole=HairWoundAssets.wholeBurn(wounds,info.house);
                int foot=(signs&BodySigns.FOOT_BURNED)!=0?3:0;
                for(var wound:wounds)if(wound.kind()==WoundKind.SCORCH&&wound.shape()==WoundShape.SOLE_BURN)foot=Math.max(foot,info.house?3:wound.severity());
                sole=foot;
                parts=new HashMap<>();triangleParts=new HashMap<>();cuts=new ArrayList<>();rigidOffsets=new HashMap<>();outputs=new HashMap<>();
                for(var part:model.woundParts()){
                    parts.put(part.name(),part);
                    for(int first=part.start();first<part.start()+part.count();first+=3)triangleParts.put(first,part.name());
                }
                BodyCavity.Opening opening=null;
                for(var wound:wounds)if(!info.house&&mesh.valid(wound)&&HairWoundAssets.profile(wound)!=null){
                    var core=wound==coreWound?BodyCavity.opening(mesh,wound):null;if(core!=null)opening=core;
                    cuts.add(new Cut(wound,mesh,info,core));
                }
                if(opening==null){cavity=null;cavitySkin=null;cavityRest=null;cavityIndices=new int[0];}else{
                    cavity=new CavityRenderer(mesh,opening,new Vector3f[0],wounds,(signs&BodySigns.CORE_HURT)!=0,corePresent,false);
                    cavitySkin=new Vector3f[mesh.weights.length];cavityRest=new Vector3f[mesh.weights.length];
                    var used=new BitSet(mesh.weights.length);for(var hit:opening.rim)used.set(hit.triangle(),hit.triangle()+3);
                    cavityIndices=used.stream().toArray();for(int i:cavityIndices){cavitySkin[i]=new Vector3f();cavityRest[i]=mesh.deformDisplay(mesh.rest(i),wounds);}
                }
                if(whole>0)rigidOffsets();
            }
            // Vertices no expression moves keep the donor's already deformed display point.
            for(int i=0;i<display.length;i++)
                display[i]=donor!=null&&!model.woundExpressionVertex(i)?donor.display[i]
                        :deformRest(triangleParts.getOrDefault(i/3*3,""),new Vector3f(source[i*8],source[i*8+1],source[i*8+2]));
            for(var part:model.woundParts()){
                if(donor!=null&&!model.woundExpressionPart(part)){merge(donor.outputs.get(part.start()));continue;}
                current=new PartOut();
                buildPart(part,hairLoss,accessory);
                if(donor==null)outputs.put(part.start(),current);
                merge(current);
            }
            if(donor==null){
                current=new PartOut();
                for(var cut:cuts)if(cut.open()){
                    if(cut.core==null)ordinaryWall(cut);
                    hairEdge(cut,hairLoss,accessory);
                }
                outputs.put(TAIL,current);
            }
            merge(outputs.get(TAIL));
            current=null;source=null;
        }
        private void merge(PartOut out){
            if(out==null)return;
            patches.putAll(out.patches);
            for(var entry:out.layers.entrySet())layers.computeIfAbsent(entry.getKey(),ignored->new ArrayList<>()).addAll(entry.getValue());
        }
        private void buildPart(YukkuriModel.Part part,int hairLoss,boolean accessory){
            if(!model.woundVisible(part,hairLoss,accessory)||!part.anatomy().isEmpty()||part.name().contains("puff"))return;
            if(whole>0&&model.woundDamagedHat(part)){thermalHat(part);return;}
            boolean receiver=receives(part.name());
            for(int first=part.start();first<part.start()+part.count();first+=3){
                if(part.name().startsWith("face_")&&source[first*8+7]>=-.15f)continue;
                var pa=point(first,A);var pb=point(first,B);var pc=point(first,C);
                List<Bary[]> triangles=new ArrayList<>();triangles.add(new Bary[]{A,B,C});boolean changed=false;
                if(receiver){
                    for(var cut:cuts)if(cut.dent()&&cut.bounds(pa,pb,pc,1.2f,part.name())){
                        triangles=subdivide(pa,pb,pc,Math.min(18,Math.max(1,(int)Math.ceil(Math.max(pa.distance(pb),Math.max(pb.distance(pc),pc.distance(pa)))/(.16f*cut.scale)))));
                        changed=true;break;
                    }
                    for(var cut:cuts)if(cut.open()&&cut.bounds(pa,pb,pc,1.5f,part.name())){
                        var outside=new ArrayList<Bary[]>();
                        for(var tri:triangles)outside.addAll(outside(cut,info.hair.contains(part.name())?cut.hair:cut.skin,first,tri));
                        triangles=outside;changed=true;
                    }
                    if(changed){var base=new ArrayList<Triangle>();for(var tri:triangles)base.add(triangle(first,tri,null,1));current.patches.put(first,base);}
                    for(var cut:cuts)if(cut.bounds(pa,pb,pc,1.25f,part.name())){
                        String texture=cut.wound.kind()==WoundKind.SCAR?"scar":cut.wound.shape().id()+"-"+cut.wound.severity();
                        var layer=layer(HairWoundAssets.material(texture));
                        // Only the outward facing layer receives ink. Original depth testing
                        // resolves actual hair coverage and keeps detached accessories opaque.
                        var n=new Vector3f(pb).sub(pa).cross(new Vector3f(pc).sub(pa));
                        if(n.lengthSquared()<1e-12||n.normalize().dot(cut.n)<.05f)continue;
                        for(var tri:triangles){
                            var polygon=polygon(cut,first,tri);float rx=cut.radiusU,ry=cut.radiusV;
                            float[][] box={{-rx,-ry},{rx,-ry},{rx,ry},{-rx,ry}};
                            for(int i=0;i<4;i++)polygon=clip(polygon,box[i],box[(i+1)%4],true);
                            for(var fragment:fan(polygon))layer.add(triangle(first,fragment,cut,1));
                        }
                    }
                }
                if(whole>0||sole>0)thermal(first,part.name(),triangles);
            }
        }
        private List<Triangle> layer(ResourceLocation texture){return current.layers.computeIfAbsent(texture,ignored->new ArrayList<>());}
        private Vector3f point(int first,Bary bary){return new Vector3f(source[first*8],source[first*8+1],source[first*8+2]).mul(bary.a)
                .fma(bary.b,new Vector3f(source[(first+1)*8],source[(first+1)*8+1],source[(first+1)*8+2]))
                .fma(bary.c,new Vector3f(source[(first+2)*8],source[(first+2)*8+1],source[(first+2)*8+2]));}
        private float uv(int first,Bary bary,int coordinate){return source[first*8+coordinate]*bary.a+source[(first+1)*8+coordinate]*bary.b+source[(first+2)*8+coordinate]*bary.c;}
        private Corner corner(int first,Bary bary,Cut decal,float alpha){
            var point=point(first,bary);
            var corner=new Corner(first,bary,point,decal==null?uv(first,bary,3):.5f+decal.x(point)/(2*decal.radiusU),
                    decal==null?uv(first,bary,4):.5f-decal.y(point)/(2*decal.radiusV),alpha);
            bindHair(corner);
            corner.rest.set(deformRest(triangleParts.getOrDefault(first,""),new Vector3f(corner.rest)));
            return corner;
        }
        private void bindHair(Corner corner){
            var point=corner.rest;
            if(info.hair.contains(triangleParts.get(corner.triangle)))for(var cut:cuts)if(cut.open()&&cut.near(point,triangleParts.get(corner.triangle))){
                float distance=distance(cut.hair,cut.x(point),cut.y(point));
                float weight=1-Math.clamp(distance/(.25f*cut.scale),0,1);
                if(weight<=corner.skinWeight)continue;
                var binding=sample(cut,cut.x(point),cut.y(point),false,0,true);
                if(binding!=null){corner.skinBinding=binding;corner.skinWeight=weight;}
            }
        }
        private Triangle triangle(int first,Bary[] bary,Cut decal,float alpha){return new Triangle(corner(first,bary[0],decal,alpha),corner(first,bary[1],decal,alpha),corner(first,bary[2],decal,alpha),255,255,255);}
        Vector3f deform(int index,Vector3f point){return point.set(display[index]);}
        /** Skin the wound art replaces (body, face, hair and the rump mark) is clipped by the opening and follows the dent. */
        private boolean receives(String part){return info.surface(part)||mesh.replaced.contains(part);}
        private Vector3f deformRest(String part,Vector3f point){
            // This exact shared field is also used by actual server hit tests.
            // It is evaluated only while rebuilding a changed cached surface.
            if(receives(part))point.set(mesh.deformDisplay(point,wounds));
            else if(whole>0){if(info.heated(part))WoundProfiles.thermalPoint(mesh.assetId,point,whole);
                else point.x+=rigidOffsets.getOrDefault(part,0f);}
            return point;
        }
        private float width(float modelY){float y=(modelY-info.offset.y)/info.scale.y,fall=(y-HairWoundAssets.cheekHeight)/HairWoundAssets.cheekFalloff;
            return 1-HairWoundAssets.widthLoss[whole-1]*(HairWoundAssets.minimumInset+(1-HairWoundAssets.minimumInset)*(float)Math.exp(-fall*fall));}
        private void rigidOffsets(){
            var groups=new HashMap<String,List<YukkuriModel.Part>>();
            for(var part:parts.values())if(!info.heated(part.name()))groups.computeIfAbsent(info.rigid.getOrDefault(part.name(),part.name()),ignored->new ArrayList<>()).add(part);
            for(var group:groups.values()){float x=0,y=0;int count=0;for(var part:group)for(int i=part.start();i<part.start()+part.count();i++){x+=source[i*8];y+=source[i*8+1];count++;}
                float offset=x/count*(width(y/count)-1);for(var part:group)rigidOffsets.put(part.name(),offset);}
        }
        private void thermal(int first,String part,List<Bary[]> triangles){
            if(whole>0)thermal(first,part,triangles,whole,false);
            if(sole>whole)thermal(first,part,triangles,sole,true);
        }
        private void thermal(int first,String part,List<Bary[]> triangles,int grade,boolean soleScope){
            if(!info.heated(part)&&!info.accessories.containsKey(part))return;
            for(var tri:triangles){
                var pa=point(first,tri[0]);var pb=point(first,tri[1]);var pc=point(first,tri[2]);
                var normal=new Vector3f(pb).sub(pa).cross(new Vector3f(pc).sub(pa));if(normal.lengthSquared()<1e-12)continue;
                normal.mul(info.scale).normalize();float[] weights={(float)Math.pow(Math.abs(normal.x),4),(float)Math.pow(Math.abs(normal.y),4),(float)Math.pow(Math.abs(normal.z),4)};
                float sum=weights[0]+weights[1]+weights[2];
                var layer=layer(HairWoundAssets.material("thermal-"+grade));
                for(int axis=0;axis<3;axis++)if(weights[axis]/sum>.002f){var corners=new Corner[3];
                    for(int j=0;j<3;j++){
                        var p=point(first,tri[j]);var canonical=info.canonical(p);float coverage;
                        if(!soleScope)coverage=info.accessories.containsKey(part)?(info.accessories.get(part).equals("char")?1:.55f):part.startsWith("face_")?.35f:1;
                        else {float t=Math.clamp((canonical.y-HairWoundAssets.soleLow)/(HairWoundAssets.soleHigh-HairWoundAssets.soleLow),0,1);
                            coverage=part.equals(info.body)?1-t*t*(3-2*t):0;}
                        // UVs remain unwrapped within a triangle; the selected texture repeats.
                        float u=(axis==0?canonical.z:canonical.x)/HairWoundAssets.textureScale;
                        float v=(axis==1?canonical.z:canonical.y)/HairWoundAssets.textureScale;
                        corners[j]=new Corner(first,tri[j],p,u,v,coverage*weights[axis]/sum);
                        bindHair(corners[j]);
                        p.set(deformRest(part,new Vector3f(p)));
                    }if(corners[0].alpha>0||corners[1].alpha>0||corners[2].alpha>0)layer.add(new Triangle(corners[0],corners[1],corners[2],255,255,255));
                }
            }
        }
        /** Reuse the actual damaged hat's cells, including recessed caps and pieced seams. */
        private void thermalHat(YukkuriModel.Part part){
            var layer=layer(HairWoundAssets.material("thermal-"+whole));
            float coverage=info.accessories.getOrDefault(part.name(),"char").equals("char")?1:.55f;
            for(var face:model.woundHatFaces(part)){
                if(face.original()){
                    for(int first=face.originalStart();first<face.originalStart()+face.originalCount();first+=3)
                        thermal(first,part.name(),List.<Bary[]>of(new Bary[]{A,B,C}),whole,false);
                    continue;
                }
                int[] indices=face.corners();Vector3f[] sourceCorners=new Vector3f[4],cell=new Vector3f[4];
                for(int i=0;i<4;i++){int k=indices[i]*8;sourceCorners[i]=new Vector3f(source[k],source[k+1],source[k+2]);cell[i]=new Vector3f();}
                var n=new Vector3f();var temp=new Vector3f();
                for(int x=0;x<face.n();x++)for(int y=0;y<face.m();y++){
                    if(!HatDamageGeometry.cell(sourceCorners,face.n(),face.m(),x,y,HatDamageGeometry.DRAW,face.flip(),cell,n,temp))continue;
                    float u0=x/(float)face.n(),u1=(x+1f)/face.n(),v0=y/(float)face.m(),v1=(y+1f)/face.m();
                    float[] us={u0,u1,u1,u0},vs={v0,v0,v1,v1};
                    var canonicalNormal=new Vector3f(n).mul(info.scale).normalize();
                    float[] weights={(float)Math.pow(Math.abs(canonicalNormal.x),4),(float)Math.pow(Math.abs(canonicalNormal.y),4),(float)Math.pow(Math.abs(canonicalNormal.z),4)};
                    float sum=weights[0]+weights[1]+weights[2];
                    for(int axis=0;axis<3;axis++)if(weights[axis]/sum>.002f){var corners=new Corner[4];
                        for(int j=0;j<4;j++){
                            var p=info.canonical(cell[j]);float u=(axis==0?p.z:p.x)/HairWoundAssets.textureScale,v=(axis==1?p.z:p.y)/HairWoundAssets.textureScale;
                            corners[j]=new Corner(indices[0]/3*3,A,new Vector3f(cell[j]),u,v,coverage*weights[axis]/sum);
                            corners[j].quad=indices;corners[j].quadU=us[j];corners[j].quadV=vs[j];
                        }
                        float inset=face.codes()[x*face.m()+y]==HatDamageGeometry.HIDDEN?-HatDamageGeometry.CAP_INSET:0;
                        if(face.flip()){
                            layer.add(new Triangle(corners[0],corners[2],corners[1],255,255,255,inset));
                            layer.add(new Triangle(corners[0],corners[3],corners[2],255,255,255,inset));
                        }else{
                            layer.add(new Triangle(corners[0],corners[1],corners[2],255,255,255,inset));
                            layer.add(new Triangle(corners[0],corners[2],corners[3],255,255,255,inset));
                        }
                    }
                }
            }
        }
        private List<Piece> polygon(Cut cut,int first,Bary[] tri){var out=new ArrayList<Piece>();for(var bary:tri){var p=point(first,bary);out.add(new Piece(cut.x(p),cut.y(p),bary));}return out;}
        private List<Bary[]> outside(Cut cut,float[][] outline,int first,Bary[] tri){
            var polygon=polygon(cut,first,tri);var result=new ArrayList<Bary[]>();float[] zero={0,0};
            for(int i=0;i<outline.length;i++){
                var p=outline[i];var q=outline[(i+1)%outline.length];
                var sector=clip(clip(polygon,zero,p,true),q,zero,true);
                result.addAll(fan(clip(sector,p,q,false)));
            }return result;
        }
        private Sample sample(Cut cut,float x,float y,boolean hair,int loss,boolean accessory){
            Sample result=null;float depth=-Float.MAX_VALUE;
            for(var part:parts.values()){
                if(hair?!info.hair.contains(part.name()):!part.name().equals(info.body))continue;
                if(!model.woundVisible(part,loss,accessory))continue;
                for(int first=part.start();first<part.start()+part.count();first+=3){
                    var a=point(first,A);var b=point(first,B);var c=point(first,C);
                    float ax=cut.x(a),ay=cut.y(a),bx=cut.x(b),by=cut.y(b),cx=cut.x(c),cy=cut.y(c);
                    float det=(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);if(Math.abs(det)<1e-9)continue;
                    float u=((x-ax)*(cy-ay)-(y-ay)*(cx-ax))/det,v=((bx-ax)*(y-ay)-(by-ay)*(x-ax))/det;
                    if(u<-.0001f||v<-.0001f||u+v>1.0001f)continue;
                    var bary=new Bary(1-u-v,u,v);var point=point(first,bary);if(!cut.near(point,part.name()))continue;
                    float z=new Vector3f(point).sub(cut.anchor).dot(cut.n);if(z>depth){result=new Sample(first,bary,point);depth=z;}
                }
            }return result;
        }
        private Corner derived(Sample sample,Vector3f point){return new Corner(sample.triangle,sample.bary,
                deformRest(triangleParts.getOrDefault(sample.triangle,""),new Vector3f(point)),.5f,.5f,1);}
        private Corner hairDerived(Cut cut,Sample sample,Vector3f point){
            var corner=derived(sample,point);corner.skinBinding=sample(cut,cut.x(sample.point),cut.y(sample.point),false,0,true);
            if(corner.skinBinding!=null)corner.skinWeight=1;return corner;
        }
        private void colored(List<Triangle> layer,Corner a,Corner b,Corner c,float[] color){layer.add(new Triangle(a,b,c,color[0],color[1],color[2]));}
        private void ordinaryWall(Cut cut){
            var layer=layer(MCYukkuri.id("textures/props/body_cavity_white.png"));
            var controls=HairWoundAssets.cavityControls;int count=cut.skin.length;
            Sample[] samples=new Sample[count];for(int i=0;i<count;i++)samples[i]=sample(cut,cut.skin[i][0],cut.skin[i][1],false,0,true);
            Sample centre=sample(cut,0,0,false,0,true);
            for(int r=0;r<controls.length-1;r++)for(int j=0;j<count;j++){
                int k=(j+1)%count;if(samples[j]==null||samples[k]==null)continue;
                var level=controls[r];var next=controls[r+1];
                var a=wall(cut,samples[j],level,r,centre);var b=wall(cut,samples[k],level,r,centre);
                var c=wall(cut,samples[k],next,r+1,centre);var d=wall(cut,samples[j],next,r+1,centre);
                float tone=HairWoundAssets.wallTone(r,j,cut.skin[j][1]/cut.cutV,level[0]);
                float[] palette=HairWoundAssets.cavityColoursFor(mesh.assetId,r);
                float[] color={palette[0]*tone,palette[1]*tone,palette[2]*tone};colored(layer,a,b,c,color);colored(layer,a,c,d,color);
            }
        }
        private Corner wall(Cut cut,Sample rim,float[] level,int index,Sample centre){
            var delta=new Vector3f(rim.point).sub(cut.anchor);
            float rimDepth=delta.dot(cut.n);
            float centreDepth=centre==null?rimDepth:new Vector3f(centre.point).sub(cut.anchor).dot(cut.n);
            float surface=rimDepth*(1-level[2])+centreDepth*level[2];
            // Source first two rings have fixed dough thickness; deeper rings follow real grade depth.
            float depth=index<3?level[1]*cut.scale:level[1]*cut.depth;
            var point=new Vector3f(cut.anchor).fma(delta.dot(cut.u)*level[0],cut.u).fma(delta.dot(cut.v)*level[0],cut.v).fma(surface-depth,cut.n);
            return derived(rim,point);
        }
        private void hairEdge(Cut cut,int loss,boolean accessory){
            var layer=layer(MCYukkuri.id("textures/props/body_cavity_white.png"));
            int count=cut.hair.length;Sample[] samples=new Sample[count],floor=new Sample[count];
            for(int i=0;i<count;i++){
                samples[i]=sample(cut,cut.hair[i][0],cut.hair[i][1],true,loss,accessory);
                floor[i]=sample(cut,cut.skin[i][0],cut.skin[i][1],false,0,true);
            }
            for(int j=0;j<count;j++){
                int k=(j+1)%count;var pa=samples[j];var pb=samples[k];var sa=floor[j];var sb=floor[k];
                if(pa==null||pb==null||sa==null||sb==null)continue;
                // The wall runs from the hair opening down to the skin opening so no hair layer edge or shell interior shows.
                float ha=new Vector3f(pa.point).sub(sa.point).dot(cut.n),hb=new Vector3f(pb.point).sub(sb.point).dot(cut.n);
                if(ha<-.15f*cut.scale||hb<-.15f*cut.scale||ha>4f*cut.scale||hb>4f*cut.scale)continue;
                float amount=.18f+.08f*(float)Math.sin(j*1.7);
                var color=new Vector3f(info.hairEdge).lerp(info.hairHighlight,amount);float[] rgb={color.x,color.y,color.z};
                var a=hairDerived(cut,pa,new Vector3f(pa.point).fma(.012f*cut.scale,cut.n));
                var b=hairDerived(cut,pb,new Vector3f(pb.point).fma(.012f*cut.scale,cut.n));
                var c=derived(sb,new Vector3f(sb.point).fma(-.012f*cut.scale,cut.n));
                var d=derived(sa,new Vector3f(sa.point).fma(-.012f*cut.scale,cut.n));
                colored(layer,a,c,b,rgb);colored(layer,a,d,c,rgb);
                boolean strand=cut.wound.shape()==WoundShape.BITE?(j==(int)(count*.14f)||j==(int)(count*.28f)||j==(int)(count*.39f)
                        ||j==(int)(count*.61f)||j==(int)(count*.75f)||j==(int)(count*.89f)):j%(cut.dent()?6:4)==0;
                if(strand&&(cut.wound.shape()!=WoundShape.CUT||Math.abs(Math.sin(j*2*Math.PI/count))>.45)){
                    float length=cut.wound.shape()==WoundShape.BITE?.12f:cut.dent()?.24f:.25f;
                    var tip=new Vector3f(pa.point).fma(-cut.x(pa.point)*length,cut.u).fma(-cut.y(pa.point)*length,cut.v).fma((cut.dent()?-.32f:-.08f)*cut.scale,cut.n);
                    colored(layer,a,hairDerived(cut,pa,tip),b,rgb);
                }
            }
        }
        /** Whether an injury patch replaces this skin triangle (the remains overlay leaves it to the wound art). */
        boolean replaced(int first){return patches.containsKey(first);}
        boolean triangle(int first,PoseStack stack,VertexConsumer buffer,int light,int overlay){
            var triangles=patches.get(first);if(triangles==null)return false;
            for(var tri:triangles)emit(tri,stack,buffer,light,overlay,false);return true;
        }
        boolean pregnancySurface(int first,PregnancyFaces.Surface surface){
            var triangles=patches.get(first);if(triangles==null)return false;
            for(var tri:triangles){pose(tri.a,a,true);pose(tri.b,b,true);pose(tri.c,c,true);surface.add(posedTriangle);}return true;
        }
        void render(Matrix4f[] world,float pregnancy,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
            if(cavity!=null){for(int i:cavityIndices){cavitySkin[i].set(mesh.skin(i,cavityRest[i],world));
                    if(pregnancy>.001f)LiveBirthGeometry.deform(cavitySkin[i],pregnancy,0);}
                cavity.render(world,cavitySkin,pregnancy,stack,buffers,light,overlay);}
            for(var entry:layers.entrySet()){
                var buffer=buffers.getBuffer(RenderType.entityTranslucent(entry.getKey()));
                for(var tri:entry.getValue())emit(tri,stack,buffer,light,overlay,true);
            }
        }
        private void pose(Corner corner,Vector3f out,boolean pregnancy){
            if(corner.quad!=null){model.woundHatQuad(corner.quad,corner.quadU,corner.quadV,out);return;}
            var point=corner.rest;
            model.woundSkin(corner.triangle,corner.bary.a,corner.bary.b,corner.bary.c,point,out,pregnancy);
            if(corner.skinBinding!=null){var binding=corner.skinBinding;
                model.woundSkin(binding.triangle,binding.bary.a,binding.bary.b,binding.bary.c,point,skinPoint,pregnancy);
                out.lerp(skinPoint,corner.skinWeight);}
        }
        private void emit(Triangle tri,PoseStack stack,VertexConsumer buffer,int light,int overlay,boolean offset){
            String part=triangleParts.getOrDefault(tri.a.triangle,"");boolean pregnancy=!model.woundPregnancyFace(part);
            pose(tri.a,a,pregnancy);pose(tri.b,b,pregnancy);pose(tri.c,c,pregnancy);
            normal.set(b).sub(a).cross(edge.set(c).sub(a));if(normal.lengthSquared()<1e-12)return;normal.normalize();
            // Only actual projected overlays use a microscopic normal lift; solid
            // wound edges and wall vertices retain their authored recess/depth.
            float lift=(offset&&tri.red==255&&tri.a.u!=.5f?.006f:0)+tri.inset;
            if(lift!=0){a.fma(lift,normal);b.fma(lift,normal);c.fma(lift,normal);}
            triangleUv[0]=tri.a.u;triangleUv[1]=tri.a.v;triangleUv[2]=tri.b.u;triangleUv[3]=tri.b.v;triangleUv[4]=tri.c.u;triangleUv[5]=tri.c.v;
            if(model.woundPatch(part,posedTriangle,triangleUv,stack,buffer,light,overlay,
                    Math.round(tri.red),Math.round(tri.green),Math.round(tri.blue),tri.a.alpha,tri.b.alpha,tri.c.alpha))return;
            var transform=stack.last();for(int i=0;i<4;i++){
                var corner=i==0?tri.a:i==1?tri.b:tri.c;var point=i==0?a:i==1?b:c;
                buffer.addVertex(transform.pose(),point.x/16,point.y/16,point.z/16)
                        .setColor(Math.round(tri.red),Math.round(tri.green),Math.round(tri.blue),Math.round(Math.clamp(corner.alpha,0,1)*255))
                        .setUv(corner.u,corner.v).setOverlay(overlay).setLight(light).setNormal(transform,normal.x,normal.y,normal.z);
            }
        }
    }
    private static float[][] scaled(float[][] source,float u,float v){float[][] out=new float[source.length][2];for(int i=0;i<out.length;i++){out[i][0]=source[i][0]*u;out[i][1]=source[i][1]*v;}return out;}
    private static float distance(float[][] polygon,float x,float y){float best=Float.MAX_VALUE;for(int i=0;i<polygon.length;i++){
        var a=polygon[i];var b=polygon[(i+1)%polygon.length];float dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy;
        float t=length<1e-12?0:Math.clamp(((x-a[0])*dx+(y-a[1])*dy)/length,0,1);
        best=Math.min(best,(float)Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy));
    }return best;}
    private static float side(float[] a,float[] b,Piece p){return (b[0]-a[0])*(p.y-a[1])-(b[1]-a[1])*(p.x-a[0]);}
    private static List<Piece> clip(List<Piece> source,float[] a,float[] b,boolean inside){
        var result=new ArrayList<Piece>();if(source.isEmpty())return result;
        var previous=source.getLast();float before=side(a,b,previous);boolean prevIn=inside?before>=0:before<=0;
        for(var current:source){float next=side(a,b,current);boolean currentIn=inside?next>=0:next<=0;
            if(prevIn!=currentIn)result.add(previous.mix(current,Math.clamp(before/(before-next),0,1)));
            if(currentIn)result.add(current);previous=current;before=next;prevIn=currentIn;
        }return result;
    }
    private static List<Bary[]> fan(List<Piece> polygon){var result=new ArrayList<Bary[]>();for(int j=1;j+1<polygon.size();j++){
        var a=polygon.getFirst();var b=polygon.get(j);var c=polygon.get(j+1);
        if(Math.abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x))>1e-9)result.add(new Bary[]{a.bary,b.bary,c.bary});
    }return result;}
    private static List<Bary[]> subdivide(Vector3f a,Vector3f b,Vector3f c,int steps){
        var result=new ArrayList<Bary[]>();for(int x=0;x<steps;x++)for(int y=0;y<steps-x;y++){
            result.add(new Bary[]{bary(x,y,steps),bary(x+1,y,steps),bary(x,y+1,steps)});
            if(x+y<steps-1)result.add(new Bary[]{bary(x+1,y,steps),bary(x+1,y+1,steps),bary(x,y+1,steps)});
        }return result;
    }
    private static Bary bary(int x,int y,int steps){return new Bary(1-(float)x/steps-(float)y/steps,(float)x/steps,(float)y/steps);}
    private HairWoundRenderer(){}
}
