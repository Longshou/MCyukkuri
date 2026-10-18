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
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Static triangle props use the same eight-float layout as the authoring exports. */
public final class PropModels {
    public static final ResourceManagerReloadListener RELOAD = PropModels::reload;
    private record Part(int start, int count, int unit, net.minecraft.resources.ResourceLocation texture, int red,int green,int blue,boolean translucent) {}
    private record Model(float[] vertices, Part[] parts, float[][] bounds,net.minecraft.world.phys.Vec3 socket,net.minecraft.world.phys.Vec3 rootSocket,float bodyLift,Map<Integer,net.minecraft.world.phys.AABB> unitBounds) {}
    private static Map<String, Model> models = Map.of();
    private static void reload(ResourceManager resources) {
        Map<String, Model> loaded = new HashMap<>();
        for(String catalog:java.util.List.of("index","operations-index","shelters-index","facilities-index","playgrounds-index","consumables-index","predators-index","subspecies-index","rounded-index","family-index","house-ecology-index","body-index"))
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
                        parts[i]=new Part(start,count,entry.has("unit")?entry.get("unit").getAsInt():0,
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
    private record HatMask(float[] vertices,byte[] codes,float[] drawnBounds){}
    private static final Map<String,HatMask> HAT_MASKS=new HashMap<>();
    private static int hatGeneration=-1;
    /** Damage level 1-3 or (piece >= 0) one shard of an accessory prop, classified like the worn accessory (HatDamageMask). */
    private static HatMask hatMask(String name,int level,int piece){
        Model model=models.get(name);if(model==null)return null;
        if(hatGeneration!=HatDamageMask.generation()){HAT_MASKS.clear();hatGeneration=HatDamageMask.generation();}
        var cached=HAT_MASKS.get(name+"/"+level+"/"+piece);
        if(cached!=null&&cached.vertices==model.vertices)return cached;
        int n=model.vertices.length/8;
        float[] all=HatDamageMask.bounds(model.vertices,new int[]{0,n});
        byte[] codes=HatDamageMask.classify(model.vertices,0,n,all,level,piece);
        var drawn=new java.util.ArrayList<Integer>();
        for(int t=0;t<codes.length;t++)if(codes[t]!=HatDamageMask.HIDDEN){drawn.add(t*3);drawn.add(3);}
        float[] bounds=drawn.isEmpty()?null:HatDamageMask.bounds(model.vertices,drawn.stream().mapToInt(Integer::intValue).toArray());
        var mask=new HatMask(model.vertices,codes,bounds);HAT_MASKS.put(name+"/"+level+"/"+piece,mask);return mask;
    }
    /** Model-unit bounds (min xyz, max xyz) of what renderHat draws, or null when nothing is drawn. */
    public static float[] hatBounds(String name,int level,int piece){var mask=hatMask(name,level,piece);return mask==null?null:mask.drawnBounds;}
    public static void renderHat(String name,int level,int piece,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        Model model=models.get(name);var mask=hatMask(name,level,piece);if(model==null||mask==null)return;
        var transform=pose.last();var v=model.vertices;
        for(var part:model.parts){
            var consumer=buffers.getBuffer(part.translucent?RenderType.entityTranslucent(part.texture):RenderType.entityCutoutNoCull(part.texture));
            for(int first=part.start;first<part.start+part.count;first+=3){
                byte code=mask.codes[first/3];if(code==HatDamageMask.HIDDEN)continue;
                boolean frayed=code==HatDamageMask.FRAYED;
                int r=frayed?part.red*HatDamageMask.tintRed()/255:part.red,g=frayed?part.green*HatDamageMask.tintGreen()/255:part.green,b=frayed?part.blue*HatDamageMask.tintBlue()/255:part.blue;
                for(int corner=0;corner<4;corner++){
                    int i=(first+Math.min(corner,2))*8;
                    consumer.addVertex(transform.pose(),v[i]/16,v[i+1]/16,v[i+2]/16).setColor(r,g,b,255)
                            .setUv(v[i+3],v[i+4]).setOverlay(overlay).setLight(light).setNormal(transform,v[i+5],v[i+6],v[i+7]);
                }
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
