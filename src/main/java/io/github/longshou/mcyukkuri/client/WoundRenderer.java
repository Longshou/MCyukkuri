package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.*;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.*;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.*;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** R3.1 skin depression, scratch paths, tear rings and scorch islands on posed triangles. */
final class WoundRenderer {
    private static final Map<Object,Geometry> CACHE=new WeakHashMap<>();
    static void clear(){CACHE.clear();}
    private record Mark(WoundSurface.Hit a,WoundSurface.Hit b,WoundSurface.Hit c,int material,float offset) {}
    private record Face(int triangle,float[] a,float[] b,float[] c,float minX,float minY,float maxX,float maxY,float det) {
        WoundSurface.Hit hit(float[] p){float x=p[0]-a[0],y=p[1]-a[1];float u=Math.clamp((x*(c[1]-a[1])-y*(c[0]-a[0]))/det,0,1),v=
                Math.clamp(((b[0]-a[0])*y-(b[1]-a[1])*x)/det,0,1);float sum=Math.max(1,u+v);return new WoundSurface.Hit(triangle,u/sum,v/sum,0);}
    }
    private static final class Geometry {
        final List<BodyWound> wounds;
        final List<SkinDecal> signs;
        final WoundSurface mesh;
        final String expression;
        final Vector3f[] original;
        final Vector3f[] rest;
        final Vector3f[] posed;
        final int[] normalGroups;
        final Vector3f[] smoothNormals;
        final WoundSurface.Hit[] faceAnchors;
        final List<Mark> marks=new ArrayList<>();
        List<Face> patchFaces=List.of();
        Geometry(WoundSurface mesh,List<BodyWound> wounds,List<SkinDecal> signs,String expression){this.mesh=mesh;this.wounds=wounds;this.signs=signs;
                this.expression=expression;rest=new Vector3f[mesh.weights.length];posed=new Vector3f[rest.length];original=new Vector3f[rest.length];
            normalGroups=new int[rest.length];var groups=new HashMap<String,Integer>();
            for(int i=0;i<rest.length;i++){var original=mesh.rest(i,expression);this.original[i]=new Vector3f(original);rest[i]=
                    mesh.deformDisplay(original,wounds);posed[i]=new Vector3f();var n=i<mesh.skinCount?mesh.normal(i/3*3):new Vector3f(0,0,-1);
                String key=Math.round(original.x*100000)+","+Math.round(original.y*100000)+","+Math.round(original.z*100000)+":"+Math.round(n.x*1000)+","+Math.round(n.y*1000)+","+Math.round(n.z*1000);
                normalGroups[i]=groups.computeIfAbsent(key,k->groups.size());}
            faceAnchors=new WoundSurface.Hit[rest.length];
            for(int i=mesh.faceParts.isEmpty()?rest.length:mesh.skinCount;i<rest.length;i++){var p=original[i];var hit=mesh.ray(new Vector3f(p).add(0,0,-.2f),new Vector3f(0,0,1),null,List.of());
                if(hit!=null&&Math.abs(restPoint(mesh,hit).z-p.z)<.2f)faceAnchors[i]=hit;}
            smoothNormals=new Vector3f[groups.size()];for(int i=0;i<smoothNormals.length;i++)smoothNormals[i]=new Vector3f();for(var w:
                    wounds)if(mesh.valid(w))author(SkinDecal.of(w,mesh.displaySite(w)));
            for(var sign:signs)if(mesh.valid(sign))author(sign);
        }
        boolean same(List<BodyWound> other,List<SkinDecal> otherSigns){
            if(other.size()!=wounds.size()||otherSigns.size()!=signs.size())return false;
            for(int i=0;i<other.size();i++){var a=wounds.get(i);var b=other.get(i);if(!a.id().equals(b.id())||a.kind()!=b.kind()||
                    a.severity()!=b.severity()||a.size()!=b.size()||a.triangle()!=b.triangle()||a.u()!=b.u()||a.v()!=b.v()||!Objects.equals(a.display(),b.display()))return false;}
            // Use the same field comparisons as the former combined list (including float signed zeros).
            for(int i=0;i<otherSigns.size();i++){var a=signs.get(i);var b=otherSigns.get(i);if(!a.id().equals(b.id())||a.kind()!=b.kind()||
                    a.severity()!=b.severity()||a.size()!=b.size()||a.triangle()!=b.triangle()||a.u()!=b.u()||a.v()!=b.v())return false;}
            return true;
        }
        void author(SkinDecal w){float s=w.size()*(.6f+w.severity()*.16f);patchFaces=faces(w,s*2*(w.kind()==WoundKind.DIRT||w.kind()==WoundKind.SCAR||
                w.kind()==WoundKind.MOLD||w.kind()==WoundKind.CHAR?3.4f:1));
            switch(w.kind()){
                case TEAR->{disk(w,0,0,1.13f*s,.79f*s,1,.88f);disk(w,0,0,s,.7f*s,3,.78f);disk(w,0,0,.8f*s,.55f*s,4,0);}
                case SCRATCH->{if(w.severity()>=2)disk(w,0,0,.75f*s,.44f*s,2,0);for(int i=0;i<2+w.severity();i++){float x=i*.24f*s;line(w,-.65f*s+x,
                        -.42f*s,-.45f*s+x,-.03f*s,.035f+w.severity()*.015f,3);line(w,-.45f*s+x,-.03f*s,-.40f*s+x,.45f*s,.035f+w.severity()*.015f,3);}}
                case DENT->{
                    // A shallow depression alone disappears in flat lighting and under the hurt flash.
                    // Keep a localized bruise on the same triangles so even a light punch remains visible.
                    disk(w,0,0,1.04f*s,.78f*s,14,.7f);
                    disk(w,0,0,.73f*s,.55f*s,2,0);
                    for(int sign:new int[]{-1,1})for(int i=0;i<11;i++)line(w,sign*s*(1-.12f*(float)Math.sin(i*Math.PI/11)),s*(-.72f+i*.13f),
                            sign*s*(1-.12f*(float)Math.sin((i+1)*Math.PI/11)),s*(-.72f+(i+1)*.13f),.035f+w.severity()*.018f,14);
                }
                case SCORCH->{for(int i=0;i<3+w.severity()*2;i++){float a=i*2.3999f,r=.2f*(float)Math.sqrt(i)*s,cx=(float)Math.cos(a)*r,cy=
                        (float)Math.sin(a)*r;disk(w,cx,cy,.36f*s,.26f*s,13,.78f);disk(w,cx,cy,.29f*s,.20f*s,11,0);}}
                // Status marks use body_marks.png: material numbers from 16 select its swatches (see art/props/body-marks).
                case DIRT->{float q=w.size()*(.45f+.2f*w.severity());
                    disk(w,0,0,q,.8f*q,18,0);
                    for(int i=0;i<3;i++){float a=i*2.1f+w.size()*5,r=.55f*q;disk(w,(float)Math.cos(a)*r,(float)Math.sin(a)*r,.42f*q,.34f*q,19,0);}
                    disk(w,-.25f*q,.15f*q,.3f*q,.24f*q,20,0);}
                case SCAR->{float q=w.size()*(.8f+.15f*w.severity());
                    line(w,-1.7f*q,-.45f*q,-.35f*q,.1f*q,.5f,17);line(w,-.35f*q,.1f*q,1.6f*q,.5f*q,.5f,17);
                    line(w,-1.7f*q,-.45f*q,-.35f*q,.1f*q,.26f,16);line(w,-.35f*q,.1f*q,1.6f*q,.5f*q,.26f,16);
                    for(int i=-1;i<=1;i++){float x=i*.85f*q;line(w,x-.12f,-.75f*q+.18f*i,x+.12f,.9f*q+.1f*i,.2f,17);}}
                // Lasting body signs (body.BodySigns), swatches 5-14 of body_marks.png.
                case MOLD->{float q=w.size()*(.5f+.22f*w.severity());int seed=w.id().hashCode();
                    disk(w,0,0,q,.82f*q,21,0);
                    for(int i=0;i<2+w.severity();i++){float a=i*2.3999f+(seed&7),r=q*(.4f+.12f*(i%3));disk(w,(float)Math.cos(a)*r,(float)Math.sin(a)*r,.26f*q,.21f*q,22,0);}
                    for(int i=0;i<2+w.severity()*2;i++){float a=i*1.7f+(seed>>3&7),r=q*.5f*(float)Math.sqrt((i+1f)/(3+w.severity()*2));disk(w,
                            (float)Math.cos(a)*r,(float)Math.sin(a)*r,.1f*q,.09f*q,23,0);}}
                case CHAR->{float q=w.size()*1.1f;
                    disk(w,0,0,1.15f*q,.8f*q,30,0);disk(w,0,0,.85f*q,.58f*q,29,0);
                    for(int i=0;i<4;i++){float a=i*1.57f+.4f;line(w,0,0,(float)Math.cos(a)*1.1f*q,(float)Math.sin(a)*.75f*q,.12f,29);}}
                case PASTE->{float q=s*.62f;disk(w,0,0,q,.7f*q,24,0);
                    for(int i=0;i<5;i++){float a=i*2.3999f,r=.45f*q;disk(w,(float)Math.cos(a)*r,(float)Math.sin(a)*r*.7f,.13f*q,.1f*q,25,0);}}
                case CORE->{float q=s*.46f;disk(w,0,0,q,.8f*q,26,0);disk(w,-.25f*q,.22f*q,.32f*q,.22f*q,27,0);
                    if(w.severity()>=3){line(w,-.9f*q,-.5f*q,0,.05f*q,.16f,28);line(w,0,.05f*q,.85f*q,.6f*q,.16f,28);line(w,0,.05f*q,.15f*q,-.85f*q,.14f,28);}}
            }
        }
        List<Face> faces(SkinDecal w,float range){var result=new ArrayList<Face>();var n=mesh.normal(w.triangle());var u=WoundSurface.tangent(n);
                var v=new Vector3f(n).cross(u);var anchor=mesh.anchor(w);
            for(int i=0;i<mesh.skinCount;i+=3){if(mesh.normal(i).dot(n)<.5)continue;float[][] p=new float[3][2];boolean near=false;for(int j=0;j<3;
                    j++){var d=mesh.rest(i+j).sub(anchor);near|=Math.abs(d.dot(n))<1.6;p[j][0]=d.dot(u);p[j][1]=d.dot(v);}if(!near)continue;
                float minX=Math.min(p[0][0],Math.min(p[1][0],p[2][0])),minY=Math.min(p[0][1],Math.min(p[1][1],p[2][1])),maxX=Math.max(p[0][0],
                        Math.max(p[1][0],p[2][0])),maxY=Math.max(p[0][1],Math.max(p[1][1],p[2][1]));
                if(minX>range||maxX<-range||minY>range||maxY<-range)continue;float det=
                        (p[1][0]-p[0][0])*(p[2][1]-p[0][1])-(p[1][1]-p[0][1])*(p[2][0]-p[0][0]);if(Math.abs(det)>1e-9)result.add(new Face(i,p[0],p[1],p[2],minX,minY,maxX,maxY,det));
            }return result;
        }
        WoundSurface.Hit project(SkinDecal w,float x,float y){var n=mesh.normal(w.triangle());var u=WoundSurface.tangent(n);var v=new Vector3f(n).cross(u);var anchor=mesh.anchor(w);
            var hit=mesh.ray(new Vector3f(anchor).fma(x,u).fma(y,v).fma(4,n),new Vector3f(n).negate(),null,List.of());
            if(hit==null||mesh.normal(hit.triangle()).dot(n)<.5)return null;var p=mesh.anchor(hit);return Math.abs(p.sub(anchor).dot(n))>1.6?null:hit;
        }
        void mark(SkinDecal w,float[] a,float[] b,float[] c,int material){
            float minX=Math.min(a[0],Math.min(b[0],c[0])),minY=Math.min(a[1],Math.min(b[1],c[1])),maxX=Math.max(a[0],Math.max(b[0],c[0])),maxY=Math.max(a[1],Math.max(b[1],c[1]));
            for(var face:patchFaces){if(minX>face.maxX||maxX<face.minX||minY>face.maxY||maxY<face.minY)continue;
                List<float[]> polygon=new ArrayList<>(List.of(a,b,c));float[][] edge={face.a,face.b,face.c};float sign=Math.signum(face.det);
                for(int k=0;k<3&&!polygon.isEmpty();k++){var from=edge[k];var to=edge[(k+1)%3];var clipped=new ArrayList<float[]>();var previous=
                        polygon.getLast();float prev=side(from,to,previous)*sign;
                    for(var current:polygon){float next=side(from,to,current)*sign;if((prev>=0)!=(next>=0)){float t=Math.clamp(prev/(prev-next),0,1);
                            clipped.add(new float[]{previous[0]+(current[0]-previous[0])*t,previous[1]+(current[1]-previous[1])*t});}
                            if(next>=0)clipped.add(current);previous=current;prev=next;}polygon=clipped;
                }
                for(int j=1;j+1<polygon.size();j++)marks.add(new Mark(face.hit(polygon.getFirst()),face.hit(polygon.get(j)),face.hit(polygon.get(j+
                        1)),material,material==3||material==14?.035f:material==16?.03f:material==17?.024f:material==18?.018f:material==19?.022f:material==20?.026f:offset(material)));
            }
        }
        /** Sign layers stack outward: mould base, fuzz, spores; char rim, char; filling, its gloss; core, gloss, crack. Paste sits above the tear rings. */
        private static float offset(int material){return switch(material){case 21->.02f;case 22->.026f;case 23->.032f;case 24->.045f;case 25->.05f;
                case 26->.055f;case 27->.06f;case 28->.065f;case 29->.026f;case 30->.02f;default->.02f;};}
        private static float side(float[] a,float[] b,float[] p){return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]);}
        float[] diskPoint(SkinDecal w,float cx,float cy,float rx,float ry,float radius,int j){double a=j*Math.PI*2/24,rough=1+.09*Math.sin(a*7+
                w.id().toString().length())+.04*Math.cos(a*13);return new float[]{cx+rx*radius*(float)(Math.cos(a)*rough),cy+ry*radius*(float)(Math.sin(a)*rough)};}
        void disk(SkinDecal w,float cx,float cy,float rx,float ry,int material,float inner){for(int r=0;r<4;r++)for(int j=0;j<24;j++){float a=inner+
                (1-inner)*r/4,b=inner+(1-inner)*(r+1)/4;var p=diskPoint(w,cx,cy,rx,ry,a,j);var q=diskPoint(w,cx,cy,rx,ry,b,j);var v=diskPoint(w,cx,cy,
                rx,ry,b,j+1);var t=diskPoint(w,cx,cy,rx,ry,a,j+1);mark(w,p,q,v,material);mark(w,p,v,t,material);}}
        void line(SkinDecal w,float ax,float ay,float bx,float by,float width,int mat){float len=(float)Math.hypot(bx-ax,by-ay),nx=
                -(by-ay)/len*width/2,ny=(bx-ax)/len*width/2;int steps=Math.max(1,(int)Math.ceil(len/.12));
            for(int i=0;i<steps;i++){float x=ax+(bx-ax)*i/steps,y=ay+(by-ay)*i/steps,xx=ax+(bx-ax)*(i+1)/steps,yy=ay+(by-ay)*(i+1)/steps;mark(w,
                    new float[]{x-nx,y-ny},new float[]{xx-nx,yy-ny},new float[]{xx+nx,yy+ny},mat);mark(w,new float[]{x-nx,y-ny},new float[]{xx+nx,yy+ny},new float[]{x+nx,y+ny},mat);}
        }
    }
    static boolean active(YukkuriView e){return !e.visibleWounds().isEmpty()||!e.visibleMarks().isEmpty()||(e.visibleSigns()&(BodySigns.MOLD_MASK|BodySigns.FOOT_BURNED))!=0;}
    private record SignKey(int packed,int filling,List<BodyWound> wounds,String asset){}
    private static final Map<Object,Map.Entry<SignKey,List<SkinDecal>>> SIGNS=new WeakHashMap<>();
    /** Derived body signs, recomputed only when the synced states change. */
    static List<SkinDecal> signs(Object key,UUID seed,String asset,int packed,int filling,List<BodyWound> wounds){
        var id=new SignKey(packed,filling,wounds,asset);var cached=SIGNS.get(key);
        if(cached!=null&&cached.getKey().equals(id))return cached.getValue();
        var result=List.copyOf(BodySigns.of(WoundSurface.get(asset),seed,packed,wounds,filling));SIGNS.put(key,Map.entry(id,result));return result;
    }
    static void render(YukkuriView e,String expression,Matrix4f[] world,PoseStack stack,VertexConsumer body,MultiBufferSource buffers,int light,int overlay){
        var wounds=e.visibleWounds();var signs=signs(e,e.getUUID(),e.assetId(),e.visibleSigns(),e.visibleFilling(),wounds);
        // Legacy marks keep their existing path; only the four derived signs are separate drawing data.
        var all=e.visibleMarks().isEmpty()?wounds:Stream.concat(wounds.stream(),e.visibleMarks().stream()).toList();
        render(e,e.assetId(),expression,all,signs,e.livePregnancyProgress(),e.visibleBandage(),e.visibleFilling(),e.profile().houseBody(),world,stack,body,buffers,light,overlay);
    }
    static void render(Object key,String asset,String expression,List<BodyWound> wounds,List<SkinDecal> signs,float pregnancy,boolean bandaged,int filling,boolean house,
                       Matrix4f[] world,PoseStack stack,VertexConsumer body,MultiBufferSource buffers,int light,int overlay){
        if(wounds.isEmpty()&&signs.isEmpty())return;var mesh=WoundSurface.get(asset);var geometry=CACHE.get(key);
        String faceExpression=YukkuriModel.get(asset).geometryExpression(expression);
        if(geometry==null||geometry.mesh!=mesh||!geometry.expression.equals(faceExpression)||!geometry.same(wounds,signs)){geometry=new Geometry(mesh,
                wounds,signs,faceExpression);CACHE.put(key,geometry);}
        // The puff morph is off while wound geometry is drawn, so the wounded skin keeps its rest shape.
        var temp=new Vector3f();for(int i=0;i<geometry.rest.length;i++){var dest=geometry.posed[i].zero();var w=mesh.weights[i];for(int k=0;
                k<w.length;k+=2){world[(int)w[k]].transformPosition(geometry.rest[i],temp);dest.fma(w[k+1],temp);}}
        // Face wafers use the same deformed triangles as the skin; independent sampling produces rim z-fighting.
        for(int i=mesh.skinCount;i<geometry.rest.length;i++){var h=geometry.faceAnchors[i];if(h==null)continue;
            var delta=new Vector3f(geometry.original[i]).sub(restPoint(mesh,h));var dest=
                    geometry.posed[i].set(geometry.posed[h.triangle()]).mul(1-h.u()-h.v()).fma(h.u(),geometry.posed[h.triangle()+1]).fma(h.v(),geometry.posed[h.triangle()+2]);
            var weights=mesh.weights[i];for(int k=0;k<weights.length;k+=2){world[(int)weights[k]].transformDirection(delta,temp);dest.fma(weights[k+1],temp);}}
        // Shared skin weights keep face decals attached; retain the original local face animation on top.
        for(var part:mesh.faceParts){var relative=new Matrix4f(world[part.bone()]);if(part.parent()>=0)relative.mul(new Matrix4f(world[part.parent()]).invert());
            for(int i=part.start();i<part.start()+part.count();i++)relative.transformPosition(geometry.posed[i]);}
        for(var p:geometry.posed)LiveBirthGeometry.deform(p,pregnancy,0);
        for(var n:geometry.smoothNormals)n.zero();
        for(int i=0;i<geometry.posed.length;i+=3){var n=new Vector3f(geometry.posed[i+1]).sub(geometry.posed[i]).cross(new Vector3f(geometry.posed[i+
                2]).sub(geometry.posed[i]));for(int j=0;j<3;j++)geometry.smoothNormals[geometry.normalGroups[i+j]].add(n);}
        for(var n:geometry.smoothNormals){if(n.lengthSquared()>1e-12)n.normalize();else n.set(0,0,-1);}
        for(int i=0;i<geometry.posed.length;i+=3)emit(stack,body,light,overlay,geometry.posed[i],geometry.posed[i+1],geometry.posed[i+2],new float[]{
                mesh.points[i*5+3],mesh.points[i*5+4],mesh.points[(i+1)*5+3],mesh.points[(i+1)*5+4],mesh.points[(i+2)*5+3],mesh.points[(i+2)*5+4]},
                geometry.smoothNormals[geometry.normalGroups[i]],geometry.smoothNormals[geometry.normalGroups[i+1]],geometry.smoothNormals[geometry.normalGroups[i+2]]);
        var ink=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/props/body_wounds.png")));
        for(var mark:geometry.marks){if(mark.material>=16)continue;var a=point(geometry,mark.a,mark.offset);var b=point(geometry,mark.b,mark.offset);
                var c=point(geometry,mark.c,mark.offset);float u=(mark.material+.5f)/16;emit(stack,ink,light,overlay,a,b,c,new float[]{u,.5f,u,.5f,u,
                .5f},pointNormal(geometry,mark.a),pointNormal(geometry,mark.b),pointNormal(geometry,mark.c));}
        // Dirt and scars (materials 16+) come from their own swatch strip; the shared buffer must not be interleaved.
        if(geometry.marks.stream().anyMatch(m->m.material>=16)){
            var tone=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/props/body_marks.png")));
            for(var mark:geometry.marks){if(mark.material<16)continue;var a=point(geometry,mark.a,mark.offset);var b=point(geometry,mark.b,
                    mark.offset);var c=point(geometry,mark.c,mark.offset);float u=(mark.material-16+.5f)/16;emit(stack,tone,light,overlay,a,b,c,
                    new float[]{u,.5f,u,.5f,u,.5f},pointNormal(geometry,mark.a),pointNormal(geometry,mark.b),pointNormal(geometry,mark.c));}
        }
        // Wet filling remains at an open outlet; it represents no extra inventory.
        if(!bandaged&&filling>0)for(var w:wounds)if(w.kind()==WoundKind.TEAR&&BodySigns.open(w)&&mesh.valid(w)){
            var p=point(geometry,mesh.displaySite(w).hit(),.09f);stack.pushPose();stack.translate(p.x/16,p.y/16,p.z/16);float s=house?.035f:.10f;
                    stack.scale(s,s,s);PropModels.render("leaked_filling",1,stack,buffers,light,overlay);stack.popPose();
        }
    }
    private static Vector3f point(Geometry g,WoundSurface.Hit h,float offset){var a=g.posed[h.triangle()];var b=g.posed[h.triangle()+1];var c=
            g.posed[h.triangle()+2];return new Vector3f(a).mul(1-h.u()-h.v()).fma(h.u(),b).fma(h.v(),c).fma(offset,pointNormal(g,h));}
    private static Vector3f pointNormal(Geometry g,WoundSurface.Hit h){
            return new Vector3f(g.smoothNormals[g.normalGroups[h.triangle()]]).mul(1-h.u()-h.v()).fma(h.u(),
            g.smoothNormals[g.normalGroups[h.triangle()+1]]).fma(h.v(),g.smoothNormals[g.normalGroups[h.triangle()+2]]).normalize();}
    private static Vector3f restPoint(WoundSurface mesh,WoundSurface.Hit h){return mesh.anchor(h,WoundSurface.AnchorMath.FMA);}
    private static void emit(PoseStack stack,VertexConsumer buffer,int light,int overlay,Vector3f a,Vector3f b,Vector3f c,float[] uv,Vector3f na,
            Vector3f nb,Vector3f nc){if(new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).lengthSquared()<1e-12)return;var transform=stack.last();
        for(int j=0;j<4;j++){int i=Math.min(j,2);var p=i==0?a:i==1?b:c;var n=i==0?na:i==1?nb:nc;buffer.addVertex(transform.pose(),p.x/16,p.y/16,
                p.z/16).setColor(255,255,255,255).setUv(uv[i*2],uv[i*2+1]).setOverlay(overlay).setLight(light).setNormal(transform,n.x,n.y,n.z);}
    }
    private WoundRenderer(){}
}
