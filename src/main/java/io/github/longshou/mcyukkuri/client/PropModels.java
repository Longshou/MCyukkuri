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
    private record Model(float[] vertices, Part[] parts, float[][] bounds,net.minecraft.world.phys.Vec3 socket,net.minecraft.world.phys.Vec3 rootSocket,float bodyLift) {}
    private static Map<String, Model> models = Map.of();
    private static void reload(ResourceManager resources) {
        Map<String, Model> loaded = new HashMap<>();
        for(String catalog:java.util.List.of("index","operations-index","shelters-index","facilities-index","playgrounds-index","consumables-index","predators-index","subspecies-index","rounded-index","family-index"))
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
                            new net.minecraft.world.phys.Vec3(rootSocket.get(0).getAsDouble()/16,rootSocket.get(1).getAsDouble()/16,rootSocket.get(2).getAsDouble()/16),data.has("body_lift")?data.get("body_lift").getAsFloat():0));
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
    private static void renderPiece(String name,int units,PoseStack pose,MultiBufferSource buffers,int light,int overlay,int side){
        renderPiece(name,units,pose,buffers,light,overlay,side,-1);
    }
    private static void renderPiece(String name,int units,PoseStack pose,MultiBufferSource buffers,int light,int overlay,int side,int layers){
        Model model=models.get(name);if(model==null)throw new IllegalStateException("Missing prop: "+name);
        var transform=pose.last();var v=model.vertices;
        for(var part:model.parts){
            if(part.unit>units || part.unit>0 && (layers & (1 << (part.unit-1)))==0)continue;
            var consumer=buffers.getBuffer(part.translucent?RenderType.entityTranslucent(part.texture):RenderType.entityCutoutNoCull(part.texture));
            for(int first=part.start;first<part.start+part.count;first+=3)for(int corner=0;corner<4;corner++){
                float center=(v[first*8]+v[(first+1)*8]+v[(first+2)*8])/3;
                if(side!=0&&(center<0?-1:1)!=side)continue;
                int i=(first+Math.min(corner,2))*8;
                consumer.addVertex(transform.pose(),v[i]/16,v[i+1]/16,v[i+2]/16).setColor(part.red,part.green,part.blue,255)
                        .setUv(v[i+3],v[i+4]).setOverlay(overlay).setLight(light).setNormal(transform,v[i+5],v[i+6],v[i+7]);
            }
        }
    }
    private PropModels(){}
}
