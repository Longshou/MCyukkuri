package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Static triangle props use the same eight-float layout as the authoring exports. */
public final class PropModels {
    public static final ResourceManagerReloadListener RELOAD = PropModels::reload;
    private record Part(String name,int start, int count, int unit, net.minecraft.resources.ResourceLocation texture, int red,int green,int blue,boolean translucent) {}
    private record Model(float[] vertices, Part[] parts, float[][] bounds,net.minecraft.world.phys.Vec3 socket,net.minecraft.world.phys.Vec3 rootSocket,float bodyLift,Map<Integer,net.minecraft.world.phys.AABB> unitBounds) {}
    private static Map<String, Model> models = Map.of();
    private static Map<net.minecraft.resources.ResourceLocation,HatDamageFaces.Sampler> propSamplers = Map.of();
    private static final Map<String,HatDamageFaces.Face[][]> HAT_FACES = new HashMap<>();
    private static void reload(ResourceManager resources) {
        Map<String, Model> loaded = new HashMap<>();
        for(String catalog:java.util.List.of("index","operations-index","shelters-index","facilities-index","playgrounds-index","consumables-index","predators-index","subspecies-index","rounded-index","family-index","house-ecology-index","body-index","hat-damage-index"))
        try (var index = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_props/"+catalog+".json")).openAsReader()) {
            for (var name : JsonParser.parseReader(index).getAsJsonObject().keySet()) {
                try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_props/" + name + ".json")).openAsReader()) {
                    var data = JsonParser.parseReader(reader).getAsJsonObject();
                    if(data.get("format").getAsInt()!=1 || data.get("units_per_block").getAsInt()!=16)throw new IllegalArgumentException("Unsupported prop: "+name);
                    var raw = data.getAsJsonArray("vertices");float[] vertices=new float[raw.size()];
                    if(vertices.length%24!=0)throw new IllegalArgumentException("Invalid prop triangles: "+name);
                    for(int i=0;i<vertices.length;i++){vertices[i]=raw.get(i).getAsFloat();if(!Float.isFinite(vertices[i]))throw new IllegalArgumentException("Invalid prop vertex: "+name);}
                    var entries=data.getAsJsonArray("parts");Part[] parts=new Part[entries.size()];
                    for(int i=0;i<parts.length;i++){
                        var entry=entries.get(i).getAsJsonObject();int start=entry.get("start").getAsInt(),count=entry.get("count").getAsInt();
                        if(start<0||count<0||start%3!=0||count%3!=0||start+count>vertices.length/8)throw new IllegalArgumentException("Invalid prop part: "+name);
                        var tint=entry.has("tint")?entry.getAsJsonArray("tint"):null;
                        parts[i]=new Part(entry.has("name")?entry.get("name").getAsString():"",start,count,entry.has("unit")?entry.get("unit").getAsInt():0,
                                entry.has("texture")?net.minecraft.resources.ResourceLocation.parse(entry.get("texture").getAsString()):MCYukkuri.id("textures/props/"+name+".png"),
                                tint==null?255:tint.get(0).getAsInt(),tint==null?255:tint.get(1).getAsInt(),tint==null?255:tint.get(2).getAsInt(),entry.has("translucent")&&entry.get("translucent").getAsBoolean());
                    }
                    float[][] bounds=new float[3][2];for(int i=0;i<3;i++)for(int j=0;j<2;j++)bounds[i][j]=data.getAsJsonArray("bounds").get(i).getAsJsonArray().get(j).getAsFloat();
                    var socket=data.has("socket")?data.getAsJsonArray("socket"):null;
                    var rootSocket=data.has("root_socket")?data.getAsJsonArray("root_socket"):null;
                    loaded.put(name,new Model(vertices,parts,bounds,socket==null?net.minecraft.world.phys.Vec3.ZERO:
                            new net.minecraft.world.phys.Vec3(socket.get(0).getAsDouble()/16,socket.get(1).getAsDouble()/16,socket.get(2).getAsDouble()/16),rootSocket==null?net.minecraft.world.phys.Vec3.ZERO:
                            new net.minecraft.world.phys.Vec3(rootSocket.get(0).getAsDouble()/16,rootSocket.get(1).getAsDouble()/16,rootSocket.get(2).getAsDouble()/16),data.has("body_lift")?data.get("body_lift").getAsFloat():0,new HashMap<>()));
                }
            }
        } catch(IOException error){throw new UncheckedIOException(error);}
        models=Map.copyOf(loaded);
        Map<net.minecraft.resources.ResourceLocation,HatDamageFaces.Sampler> samplers=new HashMap<>();
        for(var entry:loaded.entrySet())if(entry.getKey().startsWith("accessory_"))for(var part:entry.getValue().parts){
            if(samplers.containsKey(part.texture))continue;
            try{samplers.put(part.texture,HatDamageFaces.load(resources,part.texture));}catch(Exception ignored){}
        }
        propSamplers=Map.copyOf(samplers);
        HAT_FACES.clear();
    }
    public static boolean has(String name){return models.containsKey(name);}
    public static net.minecraft.world.phys.Vec3 socket(String name){return models.get(name).socket;}
    public static net.minecraft.world.phys.Vec3 rootSocket(String name){return models.get(name).rootSocket;}
    public static float bodyLift(String name){return models.get(name).bodyLift;}
    public static float extent(String name){var b=models.get(name).bounds;return Math.max(b[0][1]-b[0][0],Math.max(b[1][1]-b[1][0],b[2][1]-b[2][0]));}
    /** Immutable bounds in block units for exactly the parts drawn by render(name, units, ...).
     * Cached on the loaded model so resource reloads also invalidate these measurements. */
    public static net.minecraft.world.phys.AABB bounds(String name,int units){
        var model=models.get(name);
        return model.unitBounds.computeIfAbsent(units,key->{
            double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
            double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
            for(var part:model.parts){
                if(part.unit>key)continue;
                for(int vertex=part.start;vertex<part.start+part.count;vertex++){
                    int offset=vertex*8;double x=model.vertices[offset]/16.0,y=model.vertices[offset+1]/16.0,z=model.vertices[offset+2]/16.0;
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);
                    minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
                }
            }
            return Double.isFinite(minX)?new net.minecraft.world.phys.AABB(minX,minY,minZ,maxX,maxY,maxZ):new net.minecraft.world.phys.AABB(0,0,0,0,0,0);
        });
    }
    public static void center(String name,PoseStack pose,boolean floor){var b=models.get(name).bounds;
        pose.translate(-(b[0][0]+b[0][1])/32, floor?-b[1][0]/16:-(b[1][0]+b[1][1])/32, -(b[2][0]+b[2][1])/32);}
    public static void render(String name,int units,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        renderPiece(name,units,pose,buffers,light,overlay,0);
    }
    public static void renderLayers(String name,int mask,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        renderPiece(name,Integer.MAX_VALUE,pose,buffers,light,overlay,0,mask);
    }
    public static void renderCut(String name,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        for(int side:new int[]{-1,1}){pose.pushPose();pose.translate(side*.1,0,side*.035);pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side*12));
            renderPiece(name,8,pose,buffers,light,overlay,side);pose.popPose();}
    }
    /** Full prop, square-cell damage exactly like the worn hat (client/HatDamageFaces). {@code piece} is unused. */
    public static void renderHat(String name,int level,int piece,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        Model model=models.get(name);if(model==null)return;
        if(level<=0){render(name,8,pose,buffers,light,overlay);return;}
        var faces=hatFaces(name,model,level);
        // All cloth parts must finish before caps take their own texture's buffer.
        var caps=new HatCapQueue();
        var transform=pose.last();var v=model.vertices;
        for(int partIndex=0;partIndex<model.parts.length;partIndex++){
            var part=model.parts[partIndex];
            var consumer=buffers.getBuffer(part.translucent?RenderType.entityTranslucent(part.texture):RenderType.entityCutoutNoCull(part.texture));
            for(var face:faces[partIndex]){
                if(face.original()){
                    for(int first=face.originalStart();first<face.originalStart()+face.originalCount();first+=3){
                        float ax=v[(first+1)*8]-v[first*8],ay=v[(first+1)*8+1]-v[first*8+1],az=v[(first+1)*8+2]-v[first*8+2];
                        float bx=v[(first+2)*8]-v[first*8],by=v[(first+2)*8+1]-v[first*8+1],bz=v[(first+2)*8+2]-v[first*8+2];
                        float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
                        float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(len<1e-9f)continue;
                        nx/=len;ny/=len;nz/=len;
                        for(int corner=0;corner<4;corner++){
                            int index=Math.min(corner,2),i=(first+index)*8;
                            consumer.addVertex(transform.pose(),v[i]/16,v[i+1]/16,v[i+2]/16).setColor(part.red,part.green,part.blue,255)
                                    .setUv(v[i+3],v[i+4]).setOverlay(overlay).setLight(light).setNormal(transform,nx,ny,nz);
                        }
                    }
                    continue;
                }
                int[] c=face.corners();
                Vector3f[] corners={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
                float[] faceUv=new float[8];
                for(int k=0;k<4;k++){
                    int i=c[k]*8;
                    corners[k].set(v[i],v[i+1],v[i+2]);
                    faceUv[k*2]=v[i+3];faceUv[k*2+1]=v[i+4];
                }
                Vector3f[] cells={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
                Vector3f normal=new Vector3f(),temp=new Vector3f();
                float[] cellUv=new float[8];
                int n=face.n(),m=face.m();
                for(int i=0;i<n;i++)for(int j=0;j<m;j++){
                    int cell=i*m+j;byte code=face.codes()[cell];
                    float u0=i/(float)n,u1=(i+1f)/n,v0=j/(float)m,v1=(j+1f)/m;
                    bilerp(cells[0],corners[0],corners[1],corners[2],corners[3],u0,v0);
                    bilerp(cells[1],corners[0],corners[1],corners[2],corners[3],u1,v0);
                    bilerp(cells[2],corners[0],corners[1],corners[2],corners[3],u1,v1);
                    bilerp(cells[3],corners[0],corners[1],corners[2],corners[3],u0,v1);
                    cellUv[0]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u0,v0);
                    cellUv[1]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u0,v0);
                    cellUv[2]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u1,v0);
                    cellUv[3]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u1,v0);
                    cellUv[4]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u1,v1);
                    cellUv[5]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u1,v1);
                    cellUv[6]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u0,v1);
                    cellUv[7]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u0,v1);
                    normal.set(cells[1]).sub(cells[0]);
                    temp.set(cells[3]).sub(cells[0]);
                    normal.cross(temp);
                    if(normal.lengthSquared()<1.0e-16f)continue;
                    normal.normalize();
                    if(face.flip())normal.negate();
                    if(code==HatDamageMask.HIDDEN){
                        for(int k=0;k<4;k++)cells[k].sub(normal.x*.16f,normal.y*.16f,normal.z*.16f);
                        caps.add(transform,cells,face.cap()[cell],light,overlay,normal);
                    }else if(code==HatDamageMask.EDGE){
                        caps.add(transform,cells,face.rim()[cell],light,overlay,normal);
                    }else{
                        emitCell(consumer,transform,cells,cellUv,pack(part.red,part.green,part.blue),light,overlay,normal);
                    }
                }
            }
        }
        caps.draw(buffers);
    }
    private static HatDamageFaces.Face[][] hatFaces(String name,Model model,int level){
        String key=name+"/"+level;
        var cached=HAT_FACES.get(key);
        if(cached!=null&&cached.length==model.parts.length)return cached;
        int n=model.vertices.length/8;
        float[] all=HatDamageMask.bounds(model.vertices,new int[]{0,n});
        var built=new HatDamageFaces.Face[model.parts.length][];
        for(int i=0;i<built.length;i++){
            var part=model.parts[i];
            built[i]=HatDamageFaces.build(model.vertices,part.start,part.count,part.name,all,level,propSamplers.get(part.texture));
        }
        HAT_FACES.put(key,built);return built;
    }
    private static int pack(int r,int g,int b){return (r<<16)|(g<<8)|b;}
    private static float bilerp(float a,float b,float c,float d,float u,float v){return a+(b-a)*u+(d-a)*v+(a-b+c-d)*u*v;}
    private static void bilerp(Vector3f out,Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u,float v){
        out.set(bilerp(a.x,b.x,c.x,d.x,u,v),bilerp(a.y,b.y,c.y,d.y,u,v),bilerp(a.z,b.z,c.z,d.z,u,v));
    }
    private static void emitCell(com.mojang.blaze3d.vertex.VertexConsumer buffer,PoseStack.Pose transform,Vector3f[] p,float[] uv,int color,int light,int overlay,Vector3f normal){
        emitTriangle(buffer,transform,p[0],p[1],p[2],uv,0,1,2,color,light,overlay,normal);
        emitTriangle(buffer,transform,p[0],p[2],p[3],uv,0,2,3,color,light,overlay,normal);
    }
    private static void emitTriangle(com.mojang.blaze3d.vertex.VertexConsumer buffer,PoseStack.Pose transform,Vector3f a,Vector3f b,Vector3f c,float[] uv,
                                     int ia,int ib,int ic,int color,int light,int overlay,Vector3f normal){
        int r=color>>16&255,g=color>>8&255,bl=color&255;
        Vector3f[] points={a,b,c};int[] indices={ia,ib,ic};
        for(int corner=0;corner<4;corner++){
            int k=Math.min(corner,2);Vector3f point=points[k];int u=indices[k]*2;
            buffer.addVertex(transform.pose(),point.x/16,point.y/16,point.z/16).setColor(r,g,bl,255)
                    .setUv(uv[u],uv[u+1]).setOverlay(overlay).setLight(light).setNormal(transform,normal.x,normal.y,normal.z);
        }
    }
    /** Ordinary static prop with one extra per-item tint; the shared shard uses this. */
    public static void renderTinted(String name,PoseStack pose,MultiBufferSource buffers,int light,int overlay,int tr,int tg,int tb){
        Model model=models.get(name);if(model==null)return;
        var transform=pose.last();var v=model.vertices;
        for(var part:model.parts){
            int r=part.red*tr/255,g=part.green*tg/255,b=part.blue*tb/255;
            var consumer=buffers.getBuffer(part.translucent?RenderType.entityTranslucent(part.texture):RenderType.entityCutoutNoCull(part.texture));
            for(int first=part.start;first<part.start+part.count;first+=3)for(int corner=0;corner<4;corner++){
                int i=(first+Math.min(corner,2))*8;
                consumer.addVertex(transform.pose(),v[i]/16,v[i+1]/16,v[i+2]/16).setColor(r,g,b,255)
                        .setUv(v[i+3],v[i+4]).setOverlay(overlay).setLight(light).setNormal(transform,v[i+5],v[i+6],v[i+7]);
            }
        }
    }
    private static void renderPiece(String name,int units,PoseStack pose,MultiBufferSource buffers,int light,int overlay,int side){
        renderPiece(name,units,pose,buffers,light,overlay,side,-1);
    }
    private static void renderPiece(String name,int units,PoseStack pose,MultiBufferSource buffers,int light,int overlay,int side,int layers){
        Model model=models.get(name);if(model==null)throw new IllegalStateException("Missing prop: "+name);
        var transform=pose.last();var v=model.vertices;
        for(var part:model.parts){
            if(part.unit>units || part.unit>0 && (layers & (1 << (part.unit-1)))==0)continue;
            var consumer=buffers.getBuffer(part.translucent?RenderType.entityTranslucent(part.texture):RenderType.entityCutoutNoCull(part.texture));
            for(int first=part.start;first<part.start+part.count;first+=3){
                if(side!=0){
                    float center=(v[first*8]+v[(first+1)*8]+v[(first+2)*8])/3;
                    if((center<0?-1:1)!=side)continue;
                }
                for(int corner=0;corner<4;corner++){
                    int i=(first+Math.min(corner,2))*8;
                    consumer.addVertex(transform.pose(),v[i]/16,v[i+1]/16,v[i+2]/16).setColor(part.red,part.green,part.blue,255)
                            .setUv(v[i+3],v[i+4]).setOverlay(overlay).setLight(light).setNormal(transform,v[i+5],v[i+6],v[i+7]);
                }
            }
        }
    }
    private PropModels(){}
}
