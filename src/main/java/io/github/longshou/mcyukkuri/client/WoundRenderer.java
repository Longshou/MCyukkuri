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

/** Existing injury deformation with the selected authored materials clipped to posed skin triangles. */
final class WoundRenderer {
    private static final int MAX_TOPOLOGIES=8;
    private static final String[] MATERIALS={"scratch","dent","tear","scorch","scar"};
    private static final Map<Object,Geometry> CACHE=new WeakHashMap<>();
    private static final Map<TopologyKey,Topology> TOPOLOGIES=new LinkedHashMap<TopologyKey,Topology>(MAX_TOPOLOGIES,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<TopologyKey,Topology> eldest){return size()>MAX_TOPOLOGIES;}
    };
    static void clear(){CACHE.clear();SIGNS.clear();TOPOLOGIES.clear();}
    private record Mark(WoundSurface.Hit a,WoundSurface.Hit b,WoundSurface.Hit c,int material,float offset,float[] uv) {}
    private record Face(int triangle,float[] a,float[] b,float[] c,float minX,float minY,float maxX,float maxY,float det) {
        WoundSurface.Hit hit(float[] p){float x=p[0]-a[0],y=p[1]-a[1];float u=Math.clamp((x*(c[1]-a[1])-y*(c[0]-a[0]))/det,0,1),v=
                Math.clamp(((b[0]-a[0])*y-(b[1]-a[1])*x)/det,0,1);float sum=Math.max(1,u+v);return new WoundSurface.Hit(triangle,u/sum,v/sum,0);}
    }
    private record TopologyKey(WoundSurface mesh,String expression) {}
    /** Rounded exactly as the original normal-group key, without constructing a String per corner. */
    private record NormalKey(int x,int y,int z,int nx,int ny,int nz) {}
    private record TransformKey(int x,int y,int z,float[] weights) {
        @Override public boolean equals(Object other){return other instanceof TransformKey key&&x==key.x&&y==key.y&&z==key.z&&Arrays.equals(weights,key.weights);}
        @Override public int hashCode(){int hash=31*(31*(31*x+y)+z)+Arrays.hashCode(weights);return hash;}
    }
    /** Immutable mesh/expression data. It is shared by all entities using that selected runtime body. */
    private static final class Topology {
        final Vector3f[] original;
        final int[] normalGroups,transformGroups;
        final int normalGroupCount,transformGroupCount;
        private WoundSurface.Hit[] faceAnchors;
        Topology(WoundSurface mesh,String expression){
            int count=mesh.weights.length;original=new Vector3f[count];
            for(int i=0;i<count;i++)original[i]=mesh.rest(i,expression);
            normalGroups=new int[count];var groups=new HashMap<NormalKey,Integer>();
            for(int i=0;i<mesh.skinCount;i+=3){var normal=mesh.normal(i);for(int j=0;j<3;j++)normalGroups[i+j]=normalGroup(groups,original[i+j],normal);}
            var faceNormal=new Vector3f(0,0,-1);
            for(int i=mesh.skinCount;i<count;i++)normalGroups[i]=normalGroup(groups,original[i],faceNormal);
            normalGroupCount=groups.size();
            transformGroups=new int[count];var transforms=new HashMap<TransformKey,Integer>();
            for(int i=0;i<count;i++){
                var point=original[i];var key=new TransformKey(Float.floatToIntBits(point.x),Float.floatToIntBits(point.y),Float.floatToIntBits(point.z),mesh.weights[i]);
                var group=transforms.get(key);if(group==null){group=transforms.size();transforms.put(key,group);}transformGroups[i]=group;
            }
            transformGroupCount=transforms.size();
        }
        private static int normalGroup(Map<NormalKey,Integer> groups,Vector3f point,Vector3f normal){
            var key=new NormalKey(Math.round(point.x*100000),Math.round(point.y*100000),Math.round(point.z*100000),Math.round(normal.x*1000),
                    Math.round(normal.y*1000),Math.round(normal.z*1000));var group=groups.get(key);if(group==null){group=groups.size();groups.put(key,group);}return group;
        }
        WoundSurface.Hit[] faceAnchors(WoundSurface mesh){
            if(faceAnchors!=null)return faceAnchors;var result=new WoundSurface.Hit[original.length];
            if(!mesh.faceParts.isEmpty()){var checked=new boolean[transformGroupCount];var anchors=new WoundSurface.Hit[transformGroupCount];
                for(int i=mesh.skinCount;i<result.length;i++){int group=transformGroups[i];if(!checked[group]){
                    checked[group]=true;var point=original[i];var hit=mesh.ray(new Vector3f(point).add(0,0,-.2f),new Vector3f(0,0,1),null,List.of());
                    if(hit!=null&&Math.abs(restPoint(mesh,hit).z-point.z)<.2f)anchors[group]=hit;
                }result[i]=anchors[group];}
            }
            return faceAnchors=result;
        }
    }
    private static Topology topology(WoundSurface mesh,String expression){return TOPOLOGIES.computeIfAbsent(new TopologyKey(mesh,expression),key->new Topology(mesh,expression));}
    private static final class Geometry {
        final List<BodyWound> wounds;
        final List<SkinDecal> signs;
        final WoundSurface mesh;
        final String expression;
        final boolean drawBody;
        final Topology topology;
        final int[] activeVertices,normalTriangles,normalSlots,uniqueVertices,copiedVertices;
        final Vector3f temp=new Vector3f(), edge=new Vector3f(), normal=new Vector3f();
        final Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();
        final Vector3f na=new Vector3f(),nb=new Vector3f(),nc=new Vector3f();
        final Matrix4f relative=new Matrix4f(),parentInverse=new Matrix4f();
        final boolean woundMarks,toneMarks,texturedMarks;
        final Vector3f[] rest;
        final Vector3f[] posed;
        final Vector3f[] smoothNormals;
        final List<Mark> marks=new ArrayList<>();
        List<Face> patchFaces=List.of();
        Geometry(WoundSurface mesh,List<BodyWound> wounds,List<SkinDecal> signs,String expression){
            this.mesh=mesh;this.wounds=List.copyOf(wounds);this.signs=List.copyOf(signs);this.expression=expression;
            drawBody=deforms(wounds);topology=topology(mesh,expression);int count=mesh.weights.length;
            rest=new Vector3f[count];posed=new Vector3f[count];
            for(var w:wounds)if(mesh.valid(w))author(SkinDecal.of(w,mesh.displaySite(w)));
            for(var sign:signs)if(mesh.valid(sign))author(sign);
            boolean ink=false,tone=false,textured=false;for(var mark:marks)if(mark.material<16)ink=true;else if(mark.material<32)tone=true;else textured=true;
            woundMarks=ink;toneMarks=tone;texturedMarks=textured;
            var used=new BitSet(count);
            if(drawBody)used.set(0,count);
            else for(var mark:marks)for(var hit:List.of(mark.a,mark.b,mark.c))used.set(hit.triangle(),hit.triangle()+3);
            var wantedGroups=new BitSet(topology.normalGroupCount);
            for(int i=used.nextSetBit(0);i>=0;i=used.nextSetBit(i+1))wantedGroups.set(topology.normalGroups[i]);
            var triangles=new BitSet(count/3);
            for(int i=0;i<count;i+=3)if(wantedGroups.get(topology.normalGroups[i])||wantedGroups.get(topology.normalGroups[i+1])||wantedGroups.get(topology.normalGroups[i+2])){
                triangles.set(i/3);used.set(i,i+3);
            }
            activeVertices=used.stream().toArray();normalTriangles=triangles.stream().map(i->i*3).toArray();
            normalSlots=new int[count];Arrays.fill(normalSlots,-1);var groupSlots=new int[topology.normalGroupCount];Arrays.fill(groupSlots,-1);
            smoothNormals=new Vector3f[wantedGroups.cardinality()];int slot=0;
            for(int group=wantedGroups.nextSetBit(0);group>=0;group=wantedGroups.nextSetBit(group+1)){groupSlots[group]=slot;smoothNormals[slot++]=new Vector3f();}
            for(int i:activeVertices)normalSlots[i]=groupSlots[topology.normalGroups[i]];
            var source=new int[topology.transformGroupCount];Arrays.fill(source,-1);int[] unique=new int[activeVertices.length],copies=new int[activeVertices.length*2];int uniqueCount=0,copyCount=0;
            for(int i:activeVertices){int group=topology.transformGroups[i],first=source[group];posed[i]=new Vector3f();if(first<0){source[group]=i;unique[uniqueCount++]=i;
                    rest[i]=mesh.deformDisplay(topology.original[i],wounds);}else{copies[copyCount++]=i;copies[copyCount++]=first;}}
            uniqueVertices=Arrays.copyOf(unique,uniqueCount);copiedVertices=Arrays.copyOf(copies,copyCount);
        }
        boolean same(List<BodyWound> other,List<SkinDecal> otherSigns){
            if(other.size()!=wounds.size()||otherSigns.size()!=signs.size())return false;
            for(int i=0;i<other.size();i++){var a=wounds.get(i);var b=other.get(i);if(!a.id().equals(b.id())||a.kind()!=b.kind()||
                    a.severity()!=b.severity()||a.size()!=b.size()||a.triangle()!=b.triangle()||a.u()!=b.u()||a.v()!=b.v())return false;}
            // Use the same field comparisons as the former combined list (including float signed zeros).
            for(int i=0;i<otherSigns.size();i++){var a=signs.get(i);var b=otherSigns.get(i);if(!a.id().equals(b.id())||a.kind()!=b.kind()||
                    a.severity()!=b.severity()||a.size()!=b.size()||a.triangle()!=b.triangle()||a.u()!=b.u()||a.v()!=b.v())return false;}
            return true;
        }
        void author(SkinDecal w){float s=w.size()*(.6f+w.severity()*.16f);patchFaces=faces(w,s*2*(w.kind()==WoundKind.DIRT||w.kind()==WoundKind.SCAR||
                w.kind()==WoundKind.MOLD||w.kind()==WoundKind.CHAR?3.4f:1));
            switch(w.kind()){
                case SCRATCH->texture(w,s,32);
                case DENT->texture(w,s,33);
                case TEAR->texture(w,s,34);
                case SCORCH->texture(w,s,35);
                // Status marks use body_marks.png: material numbers from 16 select its swatches (see art/props/body-marks).
                case DIRT->{float q=w.size()*(.45f+.2f*w.severity());
                    disk(w,0,0,q,.8f*q,18,0);
                    for(int i=0;i<3;i++){float a=i*2.1f+w.size()*5,r=.55f*q;disk(w,(float)Math.cos(a)*r,(float)Math.sin(a)*r,.42f*q,.34f*q,19,0);}
                    disk(w,-.25f*q,.15f*q,.3f*q,.24f*q,20,0);}
                case SCAR->texture(w,s,36);
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
                case CORE->{float q=BodySigns.coreRadius(w.size(),w.severity());disk(w,0,0,q,.8f*q,26,0);disk(w,-.25f*q,.22f*q,.32f*q,.22f*q,27,0);
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
        void texture(SkinDecal w,float scale,int material){
            float r=2*scale;var a=new float[]{-r,-r};var b=new float[]{r,-r};var c=new float[]{r,r};var d=new float[]{-r,r};
            mark(w,a,b,c,material,r);mark(w,a,c,d,material,r);
        }
        void mark(SkinDecal w,float[] a,float[] b,float[] c,int material){mark(w,a,b,c,material,0);}
        void mark(SkinDecal w,float[] a,float[] b,float[] c,int material,float textureRadius){
            float minX=Math.min(a[0],Math.min(b[0],c[0])),minY=Math.min(a[1],Math.min(b[1],c[1])),maxX=Math.max(a[0],Math.max(b[0],c[0])),maxY=Math.max(a[1],Math.max(b[1],c[1]));
            for(var face:patchFaces){if(minX>face.maxX||maxX<face.minX||minY>face.maxY||maxY<face.minY)continue;
                List<float[]> polygon=new ArrayList<>(List.of(a,b,c));float[][] edge={face.a,face.b,face.c};float sign=Math.signum(face.det);
                for(int k=0;k<3&&!polygon.isEmpty();k++){var from=edge[k];var to=edge[(k+1)%3];var clipped=new ArrayList<float[]>();var previous=
                        polygon.getLast();float prev=side(from,to,previous)*sign;
                    for(var current:polygon){float next=side(from,to,current)*sign;if((prev>=0)!=(next>=0)){float t=Math.clamp(prev/(prev-next),0,1);
                            clipped.add(new float[]{previous[0]+(current[0]-previous[0])*t,previous[1]+(current[1]-previous[1])*t});}
                            if(next>=0)clipped.add(current);previous=current;prev=next;}polygon=clipped;
                }
                for(int j=1;j+1<polygon.size();j++){
                    var p=polygon.getFirst();var q=polygon.get(j);var r=polygon.get(j+1);
                    float[] uv=textureRadius>0?new float[]{.5f+p[0]/(2*textureRadius),.5f-p[1]/(2*textureRadius),
                            .5f+q[0]/(2*textureRadius),.5f-q[1]/(2*textureRadius),.5f+r[0]/(2*textureRadius),.5f-r[1]/(2*textureRadius)}:null;
                    marks.add(new Mark(face.hit(p),face.hit(q),face.hit(r),material,
                            textureRadius>0?.035f:material==3||material==14?.035f:material==16?.03f:material==17?.024f:material==18?.018f:material==19?.022f:material==20?.026f:offset(material),uv));
                }
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
    private static List<BodyWound> visibleMarks(YukkuriView entity){
        var marks=entity.visibleMarks();
        return entity.profile().houseBody()?marks.stream().filter(mark->mark.kind()!=WoundKind.SCAR).toList():marks;
    }
    static boolean active(YukkuriView e){
        return (!e.profile().houseBody()&&!e.visibleWounds().isEmpty())||!visibleMarks(e).isEmpty()
                ||(e.visibleSigns()&(BodySigns.MOLD_MASK|BodySigns.FOOT_BURNED))!=0;
    }
    static boolean deforms(List<BodyWound> wounds){
        for(var wound:wounds)if(wound.kind()==WoundKind.TEAR||wound.kind()==WoundKind.DENT)return true;
        return false;
    }
    static boolean deforms(YukkuriView entity){return !entity.profile().houseBody()&&(deforms(entity.visibleWounds())||deforms(visibleMarks(entity)));}
    private record SignKey(int packed,float healthFraction,List<BodyWound> wounds,String asset){}
    private static final Map<Object,Map.Entry<SignKey,List<SkinDecal>>> SIGNS=new WeakHashMap<>();
    /** Derived body signs, recomputed only when the synced states change. */
    static List<SkinDecal> signs(Object key,UUID seed,String asset,int packed,float healthFraction,List<BodyWound> wounds){
        var id=new SignKey(packed,healthFraction,wounds,asset);var cached=SIGNS.get(key);
        if(cached!=null&&cached.getKey().equals(id))return cached.getValue();
        var result=List.copyOf(BodySigns.of(WoundSurface.get(asset),seed,packed,wounds,healthFraction));SIGNS.put(key,Map.entry(id,result));return result;
    }
    static void render(YukkuriView e,String expression,Matrix4f[] world,PoseStack stack,VertexConsumer body,MultiBufferSource buffers,int light,int overlay){
        float health=e.getMaxHealth()<=0?1:Math.clamp(e.getHealth()/e.getMaxHealth(),0,1);
        boolean house=e.profile().houseBody();var wounds=house?List.<BodyWound>of():e.visibleWounds();var signs=signs(e,e.getUUID(),e.assetId(),e.visibleSigns(),health,wounds);
        // House bodies keep independent mould and baked-sole signs, but omit wounds and old wound scars.
        var marks=visibleMarks(e);var all=marks.isEmpty()?wounds:Stream.concat(wounds.stream(),marks.stream()).toList();
        render(e,e.assetId(),expression,all,signs,e.livePregnancyProgress(),health,e.profile().houseBody(),world,stack,body,buffers,light,overlay);
    }
    static void render(Object key,String asset,String expression,List<BodyWound> wounds,List<SkinDecal> signs,float pregnancy,float healthFraction,boolean house,
                       Matrix4f[] world,PoseStack stack,VertexConsumer body,MultiBufferSource buffers,int light,int overlay){
        if(wounds.isEmpty()&&signs.isEmpty())return;var mesh=WoundSurface.get(asset);var geometry=CACHE.get(key);
        String faceExpression=deforms(wounds)?YukkuriModel.get(asset).geometryExpression(expression):"";
        if(geometry==null||geometry.mesh!=mesh||!geometry.expression.equals(faceExpression)||!geometry.same(wounds,signs)){geometry=new Geometry(mesh,
                wounds,signs,faceExpression);CACHE.put(key,geometry);}
        // The puff morph is off while wound geometry is drawn, so the wounded skin keeps its rest shape.
        var temp=geometry.temp;for(int i:geometry.uniqueVertices){var dest=geometry.posed[i].zero();var w=mesh.weights[i];for(int k=0;
                k<w.length;k+=2){world[(int)w[k]].transformPosition(geometry.rest[i],temp);dest.fma(w[k+1],temp);}}
        for(int i=0;i<geometry.copiedVertices.length;i+=2)geometry.posed[geometry.copiedVertices[i]].set(geometry.posed[geometry.copiedVertices[i+1]]);
        // Face wafers use the same deformed triangles as the skin; independent sampling produces rim z-fighting.
        if(geometry.drawBody){var faceAnchors=geometry.topology.faceAnchors(mesh);for(int i=mesh.skinCount;i<geometry.rest.length;i++){var h=faceAnchors[i];if(h==null)continue;
            var delta=geometry.edge.set(geometry.topology.original[i]).sub(restPoint(mesh,h));var dest=
                    geometry.posed[i].set(geometry.posed[h.triangle()]).mul(1-h.u()-h.v()).fma(h.u(),geometry.posed[h.triangle()+1]).fma(h.v(),geometry.posed[h.triangle()+2]);
            var weights=mesh.weights[i];for(int k=0;k<weights.length;k+=2){world[(int)weights[k]].transformDirection(delta,temp);dest.fma(weights[k+1],temp);}}}
        // Shared skin weights keep face decals attached; retain the original local face animation on top.
        if(geometry.drawBody)for(var part:mesh.faceParts){var relative=geometry.relative.set(world[part.bone()]);if(part.parent()>=0)relative.mul(geometry.parentInverse.set(world[part.parent()]).invert());
            for(int i=part.start();i<part.start()+part.count();i++)relative.transformPosition(geometry.posed[i]);}
        if(pregnancy>.001f)for(int i:geometry.activeVertices)LiveBirthGeometry.deform(geometry.posed[i],pregnancy,0);
        for(var n:geometry.smoothNormals)n.zero();
        for(int i:geometry.normalTriangles){var n=geometry.normal.set(geometry.posed[i+1]).sub(geometry.posed[i]).cross(geometry.edge.set(geometry.posed[i+2]).sub(geometry.posed[i]));
            for(int j=0;j<3;j++){int slot=geometry.normalSlots[i+j];if(slot>=0)geometry.smoothNormals[slot].add(n);}}
        for(var n:geometry.smoothNormals){if(n.lengthSquared()>1e-12)n.normalize();else n.set(0,0,-1);}
        if(geometry.drawBody)for(int i=0;i<geometry.posed.length;i+=3)emit(stack,body,light,overlay,geometry.posed[i],geometry.posed[i+1],geometry.posed[i+2],
                mesh.points[i*5+3],mesh.points[i*5+4],mesh.points[(i+1)*5+3],mesh.points[(i+1)*5+4],mesh.points[(i+2)*5+3],mesh.points[(i+2)*5+4],
                geometry.smoothNormals[geometry.normalSlots[i]],geometry.smoothNormals[geometry.normalSlots[i+1]],geometry.smoothNormals[geometry.normalSlots[i+2]]);
        if(geometry.woundMarks){var ink=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/props/body_wounds.png")));
            for(int i=0;i<geometry.marks.size();i++){var mark=geometry.marks.get(i);if(mark.material<16)drawMark(geometry,mark,stack,ink,light,overlay,mark.material);}}
        if(geometry.toneMarks){
            var tone=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/props/body_marks.png")));
            for(int i=0;i<geometry.marks.size();i++){var mark=geometry.marks.get(i);if(mark.material>=16&&mark.material<32)drawMark(geometry,mark,stack,tone,light,overlay,mark.material-16);}
        }
        // Authored transparency preserves the subtle bruise and skin lip; geometry and UVs are cached with the injury.
        if(geometry.texturedMarks)for(int material=0;material<MATERIALS.length;material++){
            VertexConsumer texture=null;
            for(var mark:geometry.marks)if(mark.material==32+material){
                if(texture==null)texture=buffers.getBuffer(RenderType.entityTranslucent(MCYukkuri.id("textures/props/body_wounds/"+MATERIALS[material]+".png")));
                drawMark(geometry,mark,stack,texture,light,overlay,0);
            }
        }
        // Wet filling remains at an open outlet; it represents no extra inventory.
        if(healthFraction>0)for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(w.kind()==WoundKind.TEAR&&BodySigns.open(w)&&mesh.valid(w)){
            var hit=mesh.displaySite(w).hit();pointNormal(geometry,hit,geometry.na);point(geometry,hit,.09f,geometry.na,geometry.a);stack.pushPose();stack.translate(geometry.a.x/16,geometry.a.y/16,geometry.a.z/16);float s=house?.035f:.10f;
                    stack.scale(s,s,s);PropModels.render("leaked_filling",1,stack,buffers,light,overlay);stack.popPose();
        }}
    }
    private static Vector3f restPoint(WoundSurface mesh,WoundSurface.Hit h){return mesh.anchor(h,WoundSurface.AnchorMath.FMA);}
    private static void drawMark(Geometry g,Mark mark,PoseStack stack,VertexConsumer buffer,int light,int overlay,int material){
        pointNormal(g,mark.a,g.na);pointNormal(g,mark.b,g.nb);pointNormal(g,mark.c,g.nc);
        point(g,mark.a,mark.offset,g.na,g.a);point(g,mark.b,mark.offset,g.nb,g.b);point(g,mark.c,mark.offset,g.nc,g.c);
        float u=(material+.5f)/16;
        if(mark.uv!=null)emit(stack,buffer,light,overlay,g.a,g.b,g.c,mark.uv[0],mark.uv[1],mark.uv[2],mark.uv[3],mark.uv[4],mark.uv[5],g.na,g.nb,g.nc);
        else emit(stack,buffer,light,overlay,g.a,g.b,g.c,u,.5f,u,.5f,u,.5f,g.na,g.nb,g.nc);
    }
    private static void point(Geometry g,WoundSurface.Hit h,float offset,Vector3f normal,Vector3f out){
        out.set(g.posed[h.triangle()]).mul(1-h.u()-h.v()).fma(h.u(),g.posed[h.triangle()+1]).fma(h.v(),g.posed[h.triangle()+2]).fma(offset,normal);
    }
    private static void pointNormal(Geometry g,WoundSurface.Hit h,Vector3f out){
        out.set(g.smoothNormals[g.normalSlots[h.triangle()]]).mul(1-h.u()-h.v()).fma(h.u(),g.smoothNormals[g.normalSlots[h.triangle()+1]])
                .fma(h.v(),g.smoothNormals[g.normalSlots[h.triangle()+2]]).normalize();
    }
    private static void emit(PoseStack stack,VertexConsumer buffer,int light,int overlay,Vector3f a,Vector3f b,Vector3f c,
            float ua,float va,float ub,float vb,float uc,float vc,Vector3f na,Vector3f nb,Vector3f nc){
        float abx=b.x-a.x,aby=b.y-a.y,abz=b.z-a.z,acx=c.x-a.x,acy=c.y-a.y,acz=c.z-a.z;
        float nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
        if(nx*nx+ny*ny+nz*nz<1e-12)return;var transform=stack.last();
        for(int j=0;j<4;j++){int i=Math.min(j,2);var p=i==0?a:i==1?b:c;var n=i==0?na:i==1?nb:nc;
            buffer.addVertex(transform.pose(),p.x/16,p.y/16,p.z/16).setColor(255,255,255,255).setUv(i==0?ua:i==1?ub:uc,i==0?va:i==1?vb:vc)
                    .setOverlay(overlay).setLight(light).setNormal(transform,n.x,n.y,n.z);}
    }
    private WoundRenderer(){}
}
