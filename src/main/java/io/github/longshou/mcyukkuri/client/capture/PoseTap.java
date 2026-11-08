package io.github.longshou.mcyukkuri.client.capture;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.client.YukkuriModel;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import io.github.longshou.mcyukkuri.client.PropModels;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Render-thread-only observation. Never samples animation or changes an entity. */
public final class PoseTap {
    private static final boolean ENABLED = Boolean.getBoolean("mcyukkuri.capture");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Map<String, JsonObject> CURRENT = new LinkedHashMap<>();
    private static final Map<String, JsonObject> PREVIOUS = new HashMap<>();
    private static BufferedWriter writer;
    private static Path directory;
    private static String run, window;
    private static long frame, sequence;
    private static long clockStart, timedFrame;
    private static double renderSeconds;

    private PoseTap() {}
    public static JsonArray matrix(Matrix4f value) {
        var a = new JsonArray();
        for (float x : value.get(new float[16])) a.add((double)x);
        return a;
    }
    public static boolean active() { return ENABLED && writer != null; }
    public static JsonObject previous(String uuid){return PREVIOUS.get(uuid);}
    public static void begin(Path directory) {
        PoseTap.directory=directory;
        try { writer = Files.newBufferedWriter(directory.resolve("pose.jsonl")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        run = directory.getParent().getFileName().toString(); window = directory.getFileName().toString();
        frame = 0; timedFrame=-1;clockStart=System.nanoTime();CURRENT.clear(); PREVIOUS.clear();
    }
    public static void model(YukkuriView entity, YukkuriModel model, boolean accessory, int hairLoss) {
        if (active()) CURRENT.put(entity.getUUID().toString(), model.capturePose(accessory,hairLoss));
    }
    private static Matrix4f origin(YukkuriView entity, float partial) {
        var p = entity.getPosition(partial);
        return new Matrix4f().translation((float)p.x * 16, (float)p.y * 16, (float)p.z * 16).scale(16);
    }
    public static void body(YukkuriView entity, Matrix4f start, Matrix4f drawn, float partial) {
        if (!active()) return;
        String uuid = entity.getUUID().toString();
        var f = CURRENT.get(uuid);
        envelope(f, uuid, entity.level().getGameTime(), entity.level().dimension().location().toString(), partial);
        f.addProperty("model", entity.assetId()); f.addProperty("phase", entity.getBehaviorId());
        f.addProperty("contact", entity.contactPose());
        String yawSource = entity.vehicleYukkuri() != null ? "carry" : entity.vehicleHat() != null ? "hull"
                : List.of("IMPACT", "STRUGGLE", "EXHAUSTED").contains(entity.waterDistressPhase()) ? "distress" : "body";
        f.addProperty("yawsrc", yawSource);
        var transform = origin(entity, partial).mul(new Matrix4f(start).invert()).mul(drawn).scale(1/16f);
        var root = transform.mul(new Matrix4f(PoseChecks.matrix(f.get("root"))), new Matrix4f());
        var forward = root.transformDirection(new Vector3f(0, 0, -1));
        f.addProperty("yaw", Math.toDegrees(Math.atan2(-forward.x, forward.z)));
        f.add("root", matrix(root));
        f.add("position",PoseGeometry.point(new Vector3f((float)entity.getPosition(partial).x*16,(float)entity.getPosition(partial).y*16,(float)entity.getPosition(partial).z*16)));
        PoseGeometry.transform(f.getAsJsonObject("meshes"),transform);
        if(f.has("head"))f.add("head",matrix(transform.mul(new Matrix4f(PoseChecks.matrix(f.get("head"))),new Matrix4f())));
        for(String key:List.of("mouth_anchor","wet_tip"))if(f.has(key))f.add(key,PoseGeometry.point(transform.transformPosition(PoseGeometry.vector(f.get(key)))));
        var bones = f.getAsJsonObject("bones");
        for (String name : new ArrayList<>(bones.keySet()))
            bones.add(name, matrix(transform.mul(new Matrix4f(PoseChecks.matrix(bones.get(name))), new Matrix4f())));
        var sup = new JsonObject();
        sup.addProperty("kind", entity.vehicleYukkuri()!=null ? "carrier" : entity.vehicleHat()!=null ? "hull" : "ground");
        sup.add("host", entity.vehicleYukkuri()!=null ? new JsonPrimitive(entity.vehicleYukkuri().getUUID().toString())
                : entity.vehicleHat()!=null ? new JsonPrimitive(entity.vehicleHat().getUUID().toString()) : JsonNull.INSTANCE);
        f.add("sup", sup);
        var hold=entity.vehicleYukkuri()==null?null:ParentCarry.hold(entity.vehicleYukkuri(),entity);
        if(hold!=null)sup.addProperty("hold",hold.name());
        // Gap measures lower-surface rider support. A mouth grip is lateral contact (checked by pass).
        f.addProperty("gap_applicable",entity.isPassenger()&&hold!=ParentCarry.Hold.MOUTH);
        var item = new JsonObject(); item.addProperty("held", !entity.getHeldItem().isEmpty());
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(entity.getHeldItem().getItem()).toString());
        if (!entity.getHeldItem().isEmpty()) item.addProperty("place", entity.heldPlace().place().name());
        item.add("drawn", JsonNull.INSTANCE); f.add("item", item);
        var water = new JsonObject(); water.add("point", JsonNull.INSTANCE); f.add("water", water);
        if(f.has("wet_tip")) {
            var tip=PoseChecks.vector(f.get("wet_tip"));var pos=BlockPos.containing(tip.x/16,tip.y/16,tip.z/16);
            if(entity.level().hasChunkAt(pos))for(int dy=1;dy>=-1;dy--) {
                var at=pos.above(dy);var fluid=entity.level().getFluidState(at);
                if(fluid.is(FluidTags.WATER)){water.addProperty("point",at.getY()+fluid.getHeight(entity.level(),at));break;}
            }
        }
        var crossed = new JsonArray(); var previous = PREVIOUS.get(uuid);
        for (var marker : f.getAsJsonObject("clip_markers").entrySet()) {
            if (!marker.getValue().isJsonPrimitive()) continue;
            double time = marker.getValue().getAsDouble(), now = f.get("ct").getAsDouble();
            double before = previous != null && previous.get("clip").equals(f.get("clip")) ? previous.get("ct").getAsDouble() : -1;
            if ((before <= now ? before < time : true) && time <= now) crossed.add(marker.getKey());
        }
        f.add("markers", crossed);
        f.addProperty("transition",previous==null||!previous.get("clip").equals(f.get("clip"))||!previous.get("phase").equals(f.get("phase")));
    }
    public static void held(YukkuriView entity, Matrix4f drawn, Matrix4f anchor, float partial) {
        if (!active()) return;
        var item = CURRENT.get(entity.getUUID().toString()).getAsJsonObject("item");
        item.add("drawn", drawn == null ? JsonNull.INSTANCE : matrix(origin(entity, partial).mul(drawn)));
        if(drawn==null)return;
        var transform=origin(entity,partial).mul(drawn).translate(0,-5/16f,0).rotateZ((float)Math.PI/4).scale((float)Math.sqrt(.5)).translate(-.5f,-.5f,-.5f);
        item.add("drawn",matrix(transform));item.add("grip",PoseGeometry.point(new Vector3f(.8125f,.8125f,.5f)));
        if(anchor!=null)item.add("anchor",PoseGeometry.point(origin(entity,partial).mul(anchor).getTranslation(new Vector3f())));
        var model=Minecraft.getInstance().getItemRenderer().getModel(entity.getHeldItem(),entity.level(),null,entity.getId());
        var f=CURRENT.get(entity.getUUID().toString());
        if(model.isCustomRenderer()){f.addProperty("geometry_error","custom held-item renderer");return;}
        var mesh=new JsonObject();mesh.addProperty("closed",false);var triangles=new JsonArray();mesh.add("triangles",triangles);
        var directions=new ArrayList<Direction>(Arrays.asList(Direction.values()));directions.add(null);
        for(var direction:directions)for(var quad:model.getQuads(null,direction,RandomSource.create(42))) {
            var vertices=quad.getVertices();int stride=vertices.length/4;
            for(int[] corners:new int[][]{{0,1,2},{0,2,3}}){var tri=new JsonArray();for(int corner:corners){int i=corner*stride;
                tri.add(PoseGeometry.point(transform.transformPosition(new Vector3f(Float.intBitsToFloat(vertices[i]),Float.intBitsToFloat(vertices[i+1]),Float.intBitsToFloat(vertices[i+2])))));}triangles.add(tri);}
        }
        f.getAsJsonObject("meshes").add("item",mesh);
    }
    public static void hat(PlacedHatView hat, Matrix4f start, Matrix4f drawn, float partial) {
        if (!active()) return;
        var f = new JsonObject();
        envelope(f, hat.getUUID().toString(), hat.level().getGameTime(), hat.level().dimension().location().toString(), partial);
        f.addProperty("k", "support"); f.addProperty("model", hat.profile().hull());
        var p = hat.getPosition(partial);
        var transform = new Matrix4f().translation((float)p.x*16, (float)p.y*16, (float)p.z*16).scale(16)
                .mul(new Matrix4f(start).invert()).mul(drawn);
        f.add("root", matrix(transform)); CURRENT.put(hat.getUUID().toString(), f);
        f.add("position",PoseGeometry.point(new Vector3f((float)p.x*16,(float)p.y*16,(float)p.z*16)));
        var meshes=new JsonObject();
        var hull=new Matrix4f(transform).translate(0,(float)hat.profile().top()*hat.hullScale()/16,0).scale(hat.hullScale()/16,hat.hullScale()*(float)hat.profile().squash()/16,hat.hullScale()/16);
        meshes.add("hat",PropModels.captureMesh(hat.profile().hull(),1,hull));f.add("meshes",meshes);
        if(hat.hullDamage()>0)f.addProperty("geometry_error","damaged placed hat");
    }
    private static void envelope(JsonObject f, String uuid, long tick, String dimension, float partial) {
        if(timedFrame!=frame){timedFrame=frame;renderSeconds=(System.nanoTime()-clockStart)/1e9;}
        f.addProperty("v", 1); f.addProperty("k", "pose"); f.addProperty("src", "client");
        f.addProperty("run", run); f.addProperty("win", window); f.addProperty("seq", sequence++);
        f.addProperty("f", frame); f.addProperty("u", uuid); f.addProperty("t", tick);
        f.addProperty("dim", dimension); f.addProperty("pt", partial);
        f.addProperty("render_seconds",renderSeconds);
    }
    public static long frameId() { return frame; }
    public static boolean markerFrame() {
        for (var e : CURRENT.entrySet()) {
            var f = e.getValue();
            if (!f.has("markers")) continue;
            var previous = PREVIOUS.get(e.getKey());
            if (!f.getAsJsonArray("markers").isEmpty() || previous == null
                    || !previous.get("clip").equals(f.get("clip")) || !previous.get("phase").equals(f.get("phase"))) return true;
        }
        return false;
    }
    public static boolean evaluateFrame() {
        boolean hit=false;
        if(!active())return false;
        for (var entry : CURRENT.entrySet()) {
                var f = entry.getValue();
                if (f.get("k").getAsString().equals("pose")) {
                    PoseGeometry.context(f,CURRENT);
                    var sup = f.getAsJsonObject("sup");
                    if (!sup.get("host").isJsonNull()) {
                        var host = CURRENT.get(sup.get("host").getAsString());
                        if (host != null && sup.get("kind").getAsString().equals("hull")) sup.add("frame", host.get("root"));
                    }
                    var previous = PREVIOUS.get(entry.getKey());
                    var samples = previous == null ? List.of(f) : List.of(previous, f);
                    var results = PoseChecks.evaluate(List.of(f), List.of("pass","tilt","gap","wet","held"));
                    results.addAll(PoseChecks.evaluate(samples,List.of("posejump")));
                    var latest = new JsonArray();
                    for (var r : results) if (r.getAsJsonObject().get("f").equals(f.get("f"))) latest.add(r);
                    f.add("m", latest);
                    if(previous!=null)for(var result:latest) {
                        var r=result.getAsJsonObject();String verdict=r.get("verdict").getAsString();
                        if(!List.of("fail","suspect").contains(verdict))continue;
                        boolean existed=false;
                        for(var old:previous.getAsJsonArray("m"))if(old.getAsJsonObject().get("metric").equals(r.get("metric"))&&old.getAsJsonObject().get("verdict").equals(r.get("verdict")))existed=true;
                        hit|=!existed;
                    }
                }
        }
        return hit;
    }
    public static void finishFrame(boolean shot) {
        if (!active()) return;
        try {
            for (var entry : CURRENT.entrySet()) {
                var f=entry.getValue();var output=new JsonObject();
                for(var field:f.entrySet())if(!List.of("meshes","skin_points","pairs").contains(field.getKey())&&(shot||!List.of("bones","skin").contains(field.getKey())))output.add(field.getKey(),field.getValue());
                if(output.has("sup")){var sup=output.getAsJsonObject("sup").deepCopy();sup.remove("mesh");output.add("sup",sup);}
                if(shot){
                    String evidence="pose-"+frame+"-"+entry.getKey()+".json.gz";
                    try(var compressed=new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(directory.resolve(evidence))),StandardCharsets.UTF_8)){JSON.toJson(f,compressed);}
                    output.addProperty("evidence",evidence);
                }
                writer.write(JSON.toJson(output)); writer.newLine();
                if(f.get("k").getAsString().equals("pose"))PREVIOUS.put(entry.getKey(),f);
            }
            PREVIOUS.keySet().retainAll(CURRENT.keySet());
            CURRENT.clear(); frame++;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static void end() {
        try { writer.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
        writer = null; CURRENT.clear(); PREVIOUS.clear();
    }
}
