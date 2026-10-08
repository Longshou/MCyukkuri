package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Numeric authoring rig with triangle geometry and two-bone skinning. Client only. */
public final class YukkuriModel {
    public static final ResourceManagerReloadListener RELOAD = YukkuriModel::reload;
    private static Map<String, YukkuriModel> models = Map.of();
    private static Map<String, JsonObject> detachedBadges = Map.of();
    private static final float DEGREES = (float) (Math.PI / 180);
    private final float[] vertices;
    private final float[] restVertices;
    private final int[] featureBones;
    private final Map<Integer,Matrix4f> featureTransforms=new HashMap<>();
    private record ExpressionPatch(int start,float[] values) {}
    private final Map<String,List<ExpressionPatch>> expressionPatches=new HashMap<>();
    private List<ExpressionPatch> activePatches=List.of();
    private final String id;
    private final io.github.longshou.mcyukkuri.body.ModelFingerprint sourceHash;
    private boolean warnedWoundMismatch;
    private JsonObject badgeMount;
    private final Binding[] bindings;
    private final Bone[] bones;
    private final List<Part> parts = new ArrayList<>();
    private final HouseNormals houseNormals;
    private int woundTriangle=-1;
    private boolean drawingWoundedBody;
    private float pregnancyBelly,pregnancyContraction;
    private LiveBirthRenderer.Delivery delivery;
    public void setPregnancy(float belly,float contraction){pregnancyBelly=belly;pregnancyContraction=contraction;}
    public void setDelivery(LiveBirthRenderer.Delivery value){delivery=value;}
    /** Current selected mesh, retaining the visible parts and UVs for the unborn child's emergence. */
    float[] deliveryVertices(boolean skinOnly){
        var output=new ArrayList<Float>();
        for(var part:parts){
            if(!part.anatomy.isEmpty()||part.name.contains("puff"))continue;
            if(skinOnly&&!bodySurface(part.name))continue;
            for(int i=part.start*8;i<(part.start+part.count)*8;i++)output.add(restVertices[i]);
        }
        float[] result=new float[output.size()];for(int i=0;i<result.length;i++)result[i]=output.get(i);return result;
    }
    private int[] gripBones;
    private Vector3f[] gripPoints;
    private boolean gripping,carrying;
    private String hatMotion="";
    private float hatPhase;
    public void setCarrying(boolean value){carrying=value;}
    /** Afloat in a hat boat the side locks tuck inside the inverted crown instead of poking through its wall
     *  (extra Z roll per bone, previewed with scripts/render_hat_boat_preview.cjs --roll). */
    private static final Map<String,Float> AFLOAT_ROLL=Map.of("hand_right_tip",-50f,"hand_left",8f);
    private boolean afloat;
    public void setAfloat(boolean value){afloat=value;}
    private final Map<String, Clip> clips = new HashMap<>();
    private record ExpressionSpan(float start, float end, String expression) {}
    private final Map<String, List<ExpressionSpan>> actionExpressions = new HashMap<>();
    private final Map<String, float[]> puffEnvelopes = new HashMap<>();
    /** One rest-space morph target: refined skin, blush carriers and swung side locks. */
    private float[] puffOffsets;
    private float puffAmount;
    private Part puffSurface;
    private int puffPartIndex=-1;
    private final Map<Integer,Part> puffAttachments=new HashMap<>();
    private final Map<String,String> expressionAliases=new HashMap<>();
    private HouseNormals puffNormals;
    private final Vector3f puffBlendedNormal=new Vector3f();
    String geometryExpression(String expression) { return expressionAliases.getOrDefault(expression,expression); }

    private static float smooth(float x) { x=Math.clamp(x,0,1); return x*x*(3-2*x); }

    /** Inflate, hold (with an optional tremor that only shrinks the cheeks) and release. */
    static float puffEnvelope(float[] e,float time){
        float u=(time-e[0])/(e[1]-e[0]),base=smooth(u/e[2])*smooth((1-u)/e[2]);
        if(base<=0||e.length<5)return base;
        float hold=smooth((u-e[2])/e[2])*smooth((1-e[2]-u)/e[2]);
        return base*(1-e[3]*hold*(float)(.5-.5*Math.cos(2*Math.PI*e[4]*(time-e[0]))));
    }

    public String expressionFor(String action, float elapsed, String fallback) {
        Clip clip = clips.get(action);
        if (clip == null) return fallback;
        float time = clip.loop ? elapsed % clip.duration : Math.min(elapsed, clip.duration);
        for (ExpressionSpan span : actionExpressions.getOrDefault(action, List.of()))
            if (time >= span.start && time < span.end) return span.expression;
        return fallback;
    }
    // Rendering is serial on the render thread; these buffers are not entity state.
    private final Matrix4f[] world;
    private final float[][] pose;
    private final Vector3f[] triangle = {new Vector3f(), new Vector3f(), new Vector3f()};
    private final Vector3f second = new Vector3f(), normal = new Vector3f(), edge = new Vector3f();

    private record Bone(String name, int parent, float x, float y, float z) {}
    private record GroundContact(int bone, Vector3f[] points, float clearance) {}
    private final List<GroundContact> groundContacts = new ArrayList<>();
    private final Matrix4f contactMatrix = new Matrix4f();
    private final Vector3f contactPoint = new Vector3f();
    private record Binding(int first, int second, float weight) {}
    private record Part(String name, int start, int count, String anatomy) {}
    /** Share light across a deforming skin plane, while preserving authored hard edges. */
    private static final class HouseNormals {
        final int[] groups;
        final List<Integer> triangles=new ArrayList<>();
        final Vector3f[] normals;
        HouseNormals(float[] vertices,List<Part> parts){this(vertices,parts,false);}
        HouseNormals(float[] vertices,List<Part> parts,boolean smooth){
            groups=new int[vertices.length/8];java.util.Arrays.fill(groups,-1);
            var ids=new HashMap<String,Integer>();
            for(var part:parts)if(smooth||part.name.equals("house_body_surface")){
                for(int first=part.start;first<part.start+part.count;first+=3)triangles.add(first);
                for(int i=part.start;i<part.start+part.count;i++){
                    int offset=i*8;
                    String key=Math.round(vertices[offset]*100000)+","+Math.round(vertices[offset+1]*100000)+","+Math.round(vertices[offset+2]*100000)+":"+
                            (smooth?"":Math.round(vertices[offset+5]*1000)+","+Math.round(vertices[offset+6]*1000)+","+Math.round(vertices[offset+7]*1000));
                    groups[i]=ids.computeIfAbsent(key,k->ids.size());
                }
            }
            normals=new Vector3f[ids.size()];for(int i=0;i<normals.length;i++)normals[i]=new Vector3f();
        }
    }
    private static boolean bodySurface(String name){return name.startsWith("soft_head_slice_")||name.equals("house_body_surface")||name.equals("predator_body_surface")||name.equals("deficient_body_surface")||name.equals("rounded_body_surface");}
    /** Idle face distance from the root, measured from the selected mesh in blocks. */
    public float faceDepth() {
        float depth=0;
        for(var part:parts)if(part.name.equals("face_mouth"))
            for(int vertex=part.start;vertex<part.start+part.count;vertex++)depth=Math.max(depth,-vertices[vertex*8+2]/16f);
        return depth;
    }
    private record Track(int bone, int offset, float[][] keys) {
        void sample(float time, float[] target) {
            int low = 0, high = keys.length - 1;
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (keys[middle][0] <= time) low = middle;
                else high = middle;
            }
            float[] a = keys[low], b = keys[Math.min(low + 1, keys.length - 1)];
            float q = b[0] == a[0] ? 0 : Math.clamp((time - a[0]) / (b[0] - a[0]), 0f, 1f);
            for (int i = 0; i < 3; i++) target[offset + i] = a[i + 1] + (b[i + 1] - a[i + 1]) * q;
        }
    }
    private record Clip(float duration, boolean loop, List<Track> tracks) {
        void sample(float elapsed, float[][] pose) {
            float time = loop ? Math.max(0, elapsed) % duration : Math.clamp(elapsed, 0f, duration);
            for (Track track : tracks) track.sample(time, pose[track.bone]);
        }
    }

    public static YukkuriModel get(String id) {
        YukkuriModel model = models.get(id);
        if (model == null) throw new IllegalStateException("Yukkuri resources were not loaded: " + id);
        return model;
    }

    /** Attach to the actual posed skin; call directly after rendering this same body. */
    public void renderWound(PoseStack target,net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay,int severity) {
        renderSurfaceProp("wound_patch",Math.min(8,severity),1,target,buffers,light,overlay);
    }
    public void renderSurfaceProp(String name,int units,float scale,PoseStack target,net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay) {
        locateSurface();
        if(woundTriangle<0)return;
        for(int i=0;i<3;i++)skin(woundTriangle+i,triangle[i]);
        var origin=new Vector3f(triangle[0]).add(triangle[1]).add(triangle[2]).div(48);
        var normal=new Vector3f(triangle[1]).sub(triangle[0]).cross(new Vector3f(triangle[2]).sub(triangle[0]));
        if(normal.lengthSquared()<1e-10)return;normal.normalize();if(normal.z>0)normal.negate();
        var right=new Vector3f(normal).cross(0,1,0);if(right.lengthSquared()<1e-10)return;right.normalize();
        var up=new Vector3f(right).cross(normal).normalize();
        var basis=new org.joml.Matrix3f(right,normal,up);var rotation=new org.joml.Quaternionf().setFromNormalized(basis);
        target.pushPose();target.translate(origin.x+normal.x*.002,origin.y+normal.y*.002,origin.z+normal.z*.002);target.mulPose(rotation);
        float factor=(id.startsWith("house_")?.22f:.65f)*scale;target.scale(factor,factor,factor);
        PropModels.render(name,units,target,buffers,light,overlay);target.popPose();
    }
    private void locateSurface(){
        if(woundTriangle<0){
            float x=id.startsWith("house_")?1.2f:3.6f,y=id.startsWith("house_")?1.4f:2.2f,z=id.startsWith("house_")?-1.65f:-4.8f;
            float best=Float.MAX_VALUE;
            for(var part:parts)if(bodySurface(part.name)){
                for(int first=part.start;first<part.start+part.count;first+=3){
                    float a=0,b=0,c=0;for(int i=0;i<3;i++){int v=(first+i)*8;a+=vertices[v]/3;b+=vertices[v+1]/3;c+=vertices[v+2]/3;}
                    float distance=(a-x)*(a-x)+(b-y)*(b-y)+(c-z)*(c-z);if(distance<best){best=distance;woundTriangle=first;}
                }
            }
        }
    }

    public org.joml.Vector3f surfacePoint(){
        locateSurface();
        if(woundTriangle<0)return new org.joml.Vector3f(0,.2f,-.3f);
        var point=new org.joml.Vector3f();var vertex=new org.joml.Vector3f();
        for(int i=0;i<3;i++){skin(woundTriangle+i,vertex);point.add(vertex);}return point.div(48);
    }
    /** Pose the same three accessory vertices used by the authored mount, including body stretch. */
    public void renderBadge(int tier, PoseStack stack, net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay) {
        if (tier < 1 || badgeMount == null) return;
        int first = badgeMount.get("triangle").getAsInt();
        float[] weights = floats(badgeMount.getAsJsonArray("weights"));
        var center = new Vector3f();
        for (int i = 0; i < 3; i++) { skin(first+i,triangle[i]); center.fma(weights[i]/16,triangle[i]); }
        var outward = new Vector3f(triangle[1]).sub(triangle[0]).cross(new Vector3f(triangle[2]).sub(triangle[0]));
        if (outward.lengthSquared() < 1e-10f) return;
        outward.normalize();
        // Recover a tangent from the deformed triangle, so tilting the hat also tilts its badge.
        float[] restNormal = floats(badgeMount.getAsJsonArray("rest_normal"));
        var restRight = new Vector3f(0,1,0).cross(new Vector3f(restNormal));
        if (restRight.lengthSquared() < 1e-10f) return; restRight.normalize();
        var a = new Vector3f(vertices[(first+1)*8]-vertices[first*8],vertices[(first+1)*8+1]-vertices[first*8+1],vertices[(first+1)*8+2]-vertices[first*8+2]);
        var b = new Vector3f(vertices[(first+2)*8]-vertices[first*8],vertices[(first+2)*8+1]-vertices[first*8+1],vertices[(first+2)*8+2]-vertices[first*8+2]);
        float aa=a.dot(a),ab=a.dot(b),bb=b.dot(b),det=aa*bb-ab*ab;
        if (Math.abs(det)<1e-10f) return;
        float u=(restRight.dot(a)*bb-restRight.dot(b)*ab)/det,v=(restRight.dot(b)*aa-restRight.dot(a)*ab)/det;
        var right = new Vector3f(triangle[1]).sub(triangle[0]).mul(u).fma(v,new Vector3f(triangle[2]).sub(triangle[0])).normalize();
        var up = new Vector3f(outward).cross(right).normalize();
        float half = badgeMount.get("diameter").getAsFloat()*.5f;
        emitBadge(tier,center,right,up,outward,half,stack,buffers,light,overlay);
    }
    public static void renderDetachedBadge(String name,int tier,boolean cut,PoseStack stack,net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay) {
        var mount=detachedBadges.get(name);if(mount==null||tier<1||tier>2)return;
        var center=new Vector3f(floats(mount.getAsJsonArray("rest_point"))).div(16);
        var outward=new Vector3f(floats(mount.getAsJsonArray("rest_normal"))).normalize();
        var right=new Vector3f(0,1,0).cross(outward).normalize();var up=new Vector3f(outward).cross(right).normalize();
        stack.pushPose();
        if(cut){int side=center.x<0?-1:1;stack.translate(side*.1,0,side*.035);stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side*12));}
        emitBadge(tier,center,right,up,outward,mount.get("diameter").getAsFloat()*.5f,stack,buffers,light,overlay);
        stack.popPose();
    }
    private static void emitBadge(int tier,Vector3f center,Vector3f right,Vector3f up,Vector3f outward,float half,
            PoseStack stack,net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay) {
        center.fma(Math.max(.0007f,half*.025f),outward);
        var buffer=buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(MCYukkuri.id("textures/item/"+(tier==2?"gold":"silver")+"_yukkuri_badge.png")));
        float[][] corners={{-1,-1,0,1},{1,-1,1,1},{1,1,1,0},{-1,1,0,0}};
        for(var corner:corners){
            var point=new Vector3f(center).fma(corner[0]*half,right).fma(corner[1]*half,up);
            buffer.addVertex(stack.last().pose(),point.x,point.y,point.z).setColor(255,255,255,255).setUv(corner[2],corner[3])
                    .setOverlay(overlay).setLight(light).setNormal(stack.last(),outward.x,outward.y,outward.z);
        }
    }
    private static void reload(ResourceManager manager) {
        Map<String, YukkuriModel> loaded = new HashMap<>();
        JsonObject motionRevisions;
        try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/motion_corrections.json")).open()) {
            JsonObject root = JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            // Format 2 stores the puff as offsets; an older override pack keeps the base clips instead.
            if (root.get("format").getAsInt() == 2) motionRevisions = root.getAsJsonObject("models");
            else { motionRevisions = new JsonObject(); org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Motion corrections ignored: unsupported format {}", root.get("format")); }
        } catch (IOException error) { throw new UncheckedIOException("Cannot load motion corrections", error); }
        JsonObject placementFits;
        try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/placement_fit.json")).open()) {
            JsonObject root = JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format").getAsInt() == 1) placementFits = root.getAsJsonObject("models");
            else { placementFits = new JsonObject(); org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Placement fit ignored: unsupported format {}", root.get("format")); }
        } catch (IOException error) { throw new UncheckedIOException("Cannot load placement fit", error); }
        var assetIds = new ArrayList<String>();
        for (YukkuriVariant variant : YukkuriVariant.values()) assetIds.add(variant.assetId());
        assetIds.addAll(io.github.longshou.mcyukkuri.subspecies.SubspeciesAssets.MODELS);
        for (String id : assetIds) {
            try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri/" + id + ".json")).open()) {
                byte[] bytes=stream.readAllBytes();
                var hash=io.github.longshou.mcyukkuri.body.ModelFingerprint.of(bytes);
                JsonObject data = JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                // Seat headwear and round the hair first; motion revisions were authored on the fitted mesh.
                JsonObject fit = placementFits.getAsJsonObject(id);
                if (fit != null) applyFit(id,data,fit,hash);
                JsonObject revision = motionRevisions.getAsJsonObject(id);
                if (revision != null) applyRevision(id,data,revision);
                // Hash still identifies unchanged base geometry for wounds and badges.
                loaded.put(id, new YukkuriModel(data,hash));
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot load Yukkuri model " + id, error);
            }
        }
        try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri/badge_mounts.json")).open()) {
            var data=JsonParser.parseString(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var mounts=data.getAsJsonObject("variants");
            Map<String,JsonObject> props=new HashMap<>();
            for(var entry:data.getAsJsonObject("detached").entrySet()) {
                try(var prop=manager.getResourceOrThrow(MCYukkuri.id("yukkuri_props/"+entry.getKey()+".json")).open()) {
                    var fingerprint=io.github.longshou.mcyukkuri.body.ModelFingerprint.of(prop.readAllBytes());
                    if(fingerprint.matches(entry.getValue().getAsJsonObject().get("model_sha256").getAsString()))props.put(entry.getKey(),entry.getValue().getAsJsonObject());
                }
            }
            detachedBadges=Map.copyOf(props);
            for(var entry:loaded.entrySet()) {
                var mount=mounts.getAsJsonObject(entry.getKey()); var model=entry.getValue();
                if(mount==null)continue;
                int first=mount.get("triangle").getAsInt();
                boolean belongs=model.parts.stream().anyMatch(p->p.name.equals(mount.get("part").getAsString())&&first>=p.start&&first+3<=p.start+p.count);
                if(model.sourceHash.matches(mount.get("model_sha256").getAsString())&&belongs)model.badgeMount=mount;
                else org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Badge attachment disabled for incompatible accessory geometry: {}",entry.getKey());
            }
        } catch(IOException error) { throw new UncheckedIOException("Cannot load badge mounts",error); }
        LivingPresentation.reload(manager);
        WoundRenderer.clear();
        models = Map.copyOf(loaded);
    }

    /** Splice placement-fit meshes into the base model they were authored on: a part is overwritten
     *  in place (same triangle order) or appended and redirected; flat normals are rebuilt. */
    static void applyFit(String id,JsonObject data,JsonObject fit,io.github.longshou.mcyukkuri.body.ModelFingerprint hash) {
        if (!hash.matches(fit.get("base_sha256").getAsString())) { org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Placement fit skipped for changed base geometry: {}",id); return; }
        JsonArray vertices=data.getAsJsonArray("vertices"),weights=data.getAsJsonArray("weights"),parts=data.getAsJsonArray("parts"),bones=data.getAsJsonArray("bones");
        for (var raw:fit.getAsJsonArray("parts")) {
            JsonObject entry=raw.getAsJsonObject();int index=entry.get("part_index").getAsInt();
            if (index<0||index>=parts.size()) throw new IllegalArgumentException("Invalid placement fit part");
            JsonObject part=parts.get(index).getAsJsonObject();
            if (!part.get("name").getAsString().equals(entry.get("name").getAsString())) throw new IllegalArgumentException("Placement fit part mismatch");
            float[] source=floats(entry.getAsJsonArray("vertices"));JsonArray binding=entry.getAsJsonArray("weights"),indices=entry.getAsJsonArray("indices");
            int count=indices.size();boolean inplace=entry.get("inplace").getAsBoolean();
            if (count%3!=0||source.length%5!=0||binding.size()*5!=source.length*3||(inplace&&count!=part.get("count").getAsInt()))
                throw new IllegalArgumentException("Invalid placement fit mesh");
            int start=inplace?part.get("start").getAsInt():vertices.size()/8;
            int[] corner=new int[3];var a=new Vector3f();var b=new Vector3f();
            for (int t=0;t<count;t+=3) {
                for (int j=0;j<3;j++) { corner[j]=indices.get(t+j).getAsInt(); if (corner[j]<0||corner[j]*5+5>source.length) throw new IllegalArgumentException("Invalid placement fit index"); }
                a.set(source[corner[1]*5]-source[corner[0]*5],source[corner[1]*5+1]-source[corner[0]*5+1],source[corner[1]*5+2]-source[corner[0]*5+2]);
                b.set(source[corner[2]*5]-source[corner[0]*5],source[corner[2]*5+1]-source[corner[0]*5+1],source[corner[2]*5+2]-source[corner[0]*5+2]);
                a.cross(b);if (a.lengthSquared()>1e-24f) a.normalize(); else a.set(0,0,-1);
                for (int j=0;j<3;j++) {
                    int k=corner[j],target=start+t+j;float[] row={source[k*5],source[k*5+1],source[k*5+2],source[k*5+3],source[k*5+4],a.x,a.y,a.z};
                    JsonArray bind=new JsonArray();bind.add(binding.get(k*3));bind.add(binding.get(k*3+1));bind.add(binding.get(k*3+2));
                    if (inplace) { for (int c=0;c<8;c++) vertices.set(target*8+c,new com.google.gson.JsonPrimitive(row[c])); weights.set(target,bind); }
                    else { for (float value:row) vertices.add(value); weights.add(bind); }
                }
            }
            if (!inplace) { part.addProperty("start",start); part.addProperty("count",count); }
        }
        for (var raw:fit.getAsJsonArray("bone_pivots")) {
            JsonObject moved=raw.getAsJsonObject();int index=moved.get("index").getAsInt();
            if (index<0||index>=bones.size()||!bones.get(index).getAsJsonObject().get("name").getAsString().equals(moved.get("name").getAsString()))
                throw new IllegalArgumentException("Placement fit bone mismatch");
            bones.get(index).getAsJsonObject().add("pivot",moved.getAsJsonArray("pivot").deepCopy());
        }
        if (fit.has("ground_contacts")) data.add("ground_contacts",fit.getAsJsonArray("ground_contacts").deepCopy());
    }

    /** Merge selected motion clips and the puff morph into a base model whose skeleton matches. */
    static void applyRevision(String id,JsonObject data,JsonObject revision) {
        JsonArray expected = revision.getAsJsonArray("bone_names"), actual = data.getAsJsonArray("bones");
        boolean compatible = expected.size() == actual.size();
        for (int i=0; compatible && i<expected.size(); i++)
            compatible = expected.get(i).getAsString().equals(actual.get(i).getAsJsonObject().get("name").getAsString());
        if (!compatible) { org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Motion corrections skipped for incompatible skeleton: {}",id); return; }
        revision.getAsJsonObject("clips").entrySet().forEach(e -> data.getAsJsonObject("clips").add(e.getKey(),e.getValue()));
        if (revision.has("expression_aliases")) data.add("expression_aliases",revision.get("expression_aliases"));
        if (revision.has("expression_patches")) {
            if (!data.has("expression_patches")) data.add("expression_patches",new JsonObject());
            revision.getAsJsonObject("expression_patches").entrySet().forEach(e -> data.getAsJsonObject("expression_patches").add(e.getKey(),e.getValue()));
        }
        if (revision.has("puff_surface")) {
            JsonObject surface=revision.getAsJsonObject("puff_surface").deepCopy();
            appendPuffPart(data,surface);data.add("puff_surface",surface);
        }
        if (revision.has("puff_attachments")) {
            JsonArray attachments=revision.getAsJsonArray("puff_attachments").deepCopy();
            for(var raw:attachments)appendPuffPart(data,raw.getAsJsonObject());
            data.add("puff_attachments",attachments);
        }
        if (revision.has("puff_offsets")) data.add("puff_offsets",revision.get("puff_offsets"));
    }

    /** Expand a shared-corner puff mesh into the vertex list; offsets are full xyz per corner. */
    private static void appendPuffPart(JsonObject data,JsonObject part) {
        JsonArray source=part.getAsJsonArray("vertices"),indices=part.getAsJsonArray("indices"),offsets=part.getAsJsonArray("offsets");
        int start=data.getAsJsonArray("vertices").size()/8,count=indices==null?source.size()/8:indices.size();
        if(source.size()%8!=0||count%3!=0||offsets==null||offsets.size()*8!=source.size()*3)throw new IllegalArgumentException("Invalid puff mesh");
        JsonArray expandedOffsets=new JsonArray();
        for(int i=0;i<count;i++){
            int index=indices==null?i:indices.get(i).getAsInt();
            if(index<0||index*8+8>source.size())throw new IllegalArgumentException("Invalid puff index");
            for(int k=0;k<8;k++)data.getAsJsonArray("vertices").add(source.get(index*8+k));
            for(int k=0;k<3;k++)expandedOffsets.add(offsets.get(index*3+k));
            JsonArray binding=new JsonArray();binding.add(part.get("bone"));binding.add(part.get("bone"));binding.add(0);data.getAsJsonArray("weights").add(binding);
        }
        part.remove("vertices");part.remove("indices");part.addProperty("start",start);part.addProperty("count",count);
        part.add("offsets",expandedOffsets);
    }

    private YukkuriModel(JsonObject data,io.github.longshou.mcyukkuri.body.ModelFingerprint hash) {
        sourceHash=hash;
        if(data.has("expression_aliases"))data.getAsJsonObject("expression_aliases").entrySet().forEach(e->expressionAliases.put(e.getKey(),e.getValue().getAsString()));
        id = data.get("id").getAsString();
        if (data.get("format").getAsInt() != 1 || data.get("units_per_block").getAsInt() != 16)
            throw new IllegalArgumentException("Unsupported Yukkuri model format");
        vertices = floats(data.getAsJsonArray("vertices"));
        restVertices=vertices.clone();featureBones=new int[vertices.length/8];java.util.Arrays.fill(featureBones,-1);
        JsonArray sourceBones = data.getAsJsonArray("bones");
        bones = new Bone[sourceBones.size()];
        world = new Matrix4f[bones.length];
        pose = new float[bones.length][9];
        for (int i = 0; i < bones.length; i++) {
            JsonObject bone = sourceBones.get(i).getAsJsonObject();
            float[] pivot = floats(bone.getAsJsonArray("pivot"));
            int parent = bone.get("parent").getAsInt();
            if (parent < -1 || parent >= i) throw new IllegalArgumentException("Unordered bone hierarchy");
            bones[i] = new Bone(bone.get("name").getAsString(), parent, pivot[0], pivot[1], pivot[2]);
            world[i] = new Matrix4f();
        }
        JsonArray sourceBindings = data.getAsJsonArray("weights");
        bindings = new Binding[sourceBindings.size()];
        if (vertices.length != bindings.length * 8) throw new IllegalArgumentException("Invalid vertex stride");
        for (int i = 0; i < bindings.length; i++) {
            JsonArray values = sourceBindings.get(i).getAsJsonArray();
            int a = values.get(0).getAsInt(), b = values.get(1).getAsInt();
            float weight = values.get(2).getAsFloat();
            if (a < 0 || a >= bones.length || b < 0 || b >= bones.length || weight < 0 || weight > 1)
                throw new IllegalArgumentException("Invalid vertex binding");
            bindings[i] = new Binding(a, b, weight);
        }
        for (var value : data.getAsJsonArray("parts")) {
            JsonObject part = value.getAsJsonObject();
            int start = part.get("start").getAsInt(), count = part.get("count").getAsInt();
            if (start < 0 || count < 0 || count % 3 != 0 || start + count > bindings.length)
                throw new IllegalArgumentException("Invalid triangle range");
            parts.add(new Part(part.get("name").getAsString(), start, count, part.get("anatomy").getAsString()));
            if(part.has("feature_bone")){
                int bone=part.get("feature_bone").getAsInt();if(bone<0||bone>=bones.length)throw new IllegalArgumentException("Invalid face feature bone");
                java.util.Arrays.fill(featureBones,start,start+count,bone);featureTransforms.putIfAbsent(bone,new Matrix4f());
            }
        }
        if(data.has("puff_surface")) {
            puffOffsets=new float[vertices.length/8*3];
            JsonObject surface=data.getAsJsonObject("puff_surface");puffPartIndex=surface.get("part_index").getAsInt();
            if(puffPartIndex<0||puffPartIndex>=parts.size())throw new IllegalArgumentException("Invalid puff surface");
            puffSurface=puffPart(surface,parts.get(puffPartIndex),bindings.length);
            for(var raw:data.has("puff_attachments")?data.getAsJsonArray("puff_attachments"):new JsonArray()){
                JsonObject attachment=raw.getAsJsonObject();int index=attachment.get("part_index").getAsInt();
                if(index<0||index>=parts.size())throw new IllegalArgumentException("Invalid puff attachment");
                puffAttachments.put(index,puffPart(attachment,parts.get(index),bindings.length));
            }
            // Side locks keep their own triangles; their swing is a rest-space offset per corner.
            for(var raw:data.has("puff_offsets")?data.getAsJsonArray("puff_offsets"):new JsonArray()){
                JsonObject moved=raw.getAsJsonObject();int index=moved.get("part_index").getAsInt();
                if(index<0||index>=parts.size())throw new IllegalArgumentException("Invalid puff offsets");
                Part part=parts.get(index);float[] offsets=floats(moved.getAsJsonArray("offsets"));
                if(offsets.length!=part.count*3)throw new IllegalArgumentException("Puff offsets differ from part topology");
                System.arraycopy(offsets,0,puffOffsets,part.start*3,offsets.length);
            }
        }
        if(data.has("expression_patches"))for(var entry:data.getAsJsonObject("expression_patches").entrySet()){
            var list=new ArrayList<ExpressionPatch>();for(var raw:entry.getValue().getAsJsonArray()){
                var patch=raw.getAsJsonObject();int index=patch.get("index").getAsInt();if(index<0||index>=parts.size())throw new IllegalArgumentException("Invalid expression part");
                var part=parts.get(index);float[] values=floats(patch.getAsJsonArray("vertices"));if(values.length!=part.count*8)throw new IllegalArgumentException("Expression topology differs");
                list.add(new ExpressionPatch(part.start*8,values));
            }expressionPatches.put(entry.getKey(),List.copyOf(list));
        }
        JsonObject allClips=data.getAsJsonObject("clips").deepCopy();
        if(data.has("behavior_clips"))for(var entry:data.getAsJsonObject("behavior_clips").entrySet())allClips.add(entry.getKey(),entry.getValue());
        if(data.has("ground_contacts"))for(var raw:data.getAsJsonArray("ground_contacts")){
            var contact=raw.getAsJsonObject();int bone=contact.get("index").getAsInt();
            if(bone<0||bone>=bones.length||!bones[bone].name.equals(contact.get("name").getAsString()))throw new IllegalArgumentException("Ground contact bone mismatch");
            var points=contact.getAsJsonArray("points");Vector3f[] at=new Vector3f[points.size()];
            for(int i=0;i<at.length;i++)at[i]=new Vector3f(floats(points.get(i).getAsJsonArray()));
            groundContacts.add(new GroundContact(bone,at,contact.get("clearance").getAsFloat()));
        }
        if(data.has("grip")){
            var grip=data.getAsJsonObject("grip");gripBones=new int[2];gripPoints=new Vector3f[2];
            for(int i=0;i<2;i++){gripBones[i]=grip.getAsJsonArray("bones").get(i).getAsInt();
                float[] point=floats(grip.getAsJsonArray("points").get(i).getAsJsonArray());gripPoints[i]=new Vector3f(point);}
        }
        for (var entry : allClips.entrySet()) {
            JsonObject clip = entry.getValue().getAsJsonObject();
            List<Track> tracks = new ArrayList<>();
            for (var value : clip.getAsJsonArray("tracks")) {
                JsonObject track = value.getAsJsonObject();
                int offset = switch (track.get("channel").getAsString()) {
                    case "position" -> 0;
                    case "rotation" -> 3;
                    case "scale" -> 6;
                    default -> throw new IllegalArgumentException("Unknown animation channel");
                };
                JsonArray keys = track.getAsJsonArray("keys");
                float[][] samples = new float[keys.size()][];
                for (int i = 0; i < samples.length; i++) samples[i] = floats(keys.get(i).getAsJsonArray());
                int bone = track.get("bone").getAsInt();
                if (bone < 0 || bone >= bones.length || samples.length == 0)
                    throw new IllegalArgumentException("Invalid animation track");
                tracks.add(new Track(bone, offset, samples));
            }
            float duration = clip.get("duration").getAsFloat();
            if (!(duration > 0)) throw new IllegalArgumentException("Invalid clip duration");
            clips.put(entry.getKey(), new Clip(duration, clip.get("loop").getAsBoolean(), tracks));
            if (clip.has("puff_envelope")) puffEnvelopes.put(entry.getKey(),floats(clip.getAsJsonArray("puff_envelope")));
            if (clip.has("expression_spans")) {
                List<ExpressionSpan> spans = new ArrayList<>();
                for (var raw : clip.getAsJsonArray("expression_spans")) {
                    JsonArray span = raw.getAsJsonArray();
                    spans.add(new ExpressionSpan(span.get(0).getAsFloat(),span.get(1).getAsFloat(),span.get(2).getAsString()));
                }
                actionExpressions.put(entry.getKey(),List.copyOf(spans));
            }
        }
        if (!clips.containsKey("idle")) throw new IllegalArgumentException("Missing idle defaults");
        houseNormals=id.startsWith("house_")?new HouseNormals(restVertices,parts):null;
        if(puffSurface!=null)puffNormals=new HouseNormals(restVertices,List.of(puffSurface),true);
    }

    private Part puffPart(JsonObject source,Part original,int vertexCount){
        int start=source.get("start").getAsInt(),count=source.get("count").getAsInt();
        float[] offsets=floats(source.getAsJsonArray("offsets"));
        if(start<0||count<0||count%3!=0||start+count>vertexCount||offsets.length!=count*3)throw new IllegalArgumentException("Invalid puff mesh range");
        System.arraycopy(offsets,0,puffOffsets,start*3,offsets.length);
        return new Part(original.name,start,count,original.anatomy);
    }

    private static float[] floats(JsonArray values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).getAsFloat();
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Non-finite model value");
        }
        return result;
    }

    public void render(String action, float elapsed, PoseStack stack, VertexConsumer buffer, int light, int overlay) {
        render(action, elapsed, stack, buffer, light, overlay, true, "idle", 0);
    }

    public void render(String action, float elapsed, PoseStack stack, VertexConsumer buffer, int light, int overlay,
            boolean accessory, String behavior, float behaviorTicks) {
        render(action,elapsed,stack,buffer,light,overlay,accessory,behavior,behaviorTicks,0);
    }
    public void render(String action, float elapsed, PoseStack stack, VertexConsumer buffer, int light, int overlay,
            boolean accessory, String behavior, float behaviorTicks,int hairLoss) {
        for (float[] values : pose) {
            java.util.Arrays.fill(values, 0);
            values[6] = values[7] = values[8] = 1;
        }
        clips.get("idle").sample(0, pose); // Sparse clips retain hidden puff defaults.
        String selected=action;
        if(gripBones!=null&&(action.equals("idle")||action.equals("waddle"))){
            if(behavior.equals("house_pickup")||behavior.equals("house_deposit")||behavior.equals("house_carry"))selected=behavior;
            else if(behavior.equals("house_crawl"))selected="house_crawl";
        }
        if(carrying&&gripBones!=null&&(selected.equals("idle")||selected.equals("waddle")))selected="house_carry";
        gripping=carrying||selected.equals("house_pickup")||selected.equals("house_deposit")||selected.equals("house_carry");
        clips.getOrDefault(selected, clips.get("idle")).sample(selected.equals(action)?elapsed:behaviorTicks/20f, pose);
        puffAmount=0;
        float[] envelope=puffEnvelopes.get(selected);
        if (envelope!=null&&puffSurface!=null) {
            Clip clip=clips.get(selected);
            float time=selected.equals(action)?elapsed:behaviorTicks/20f;
            time=clip.loop?time%clip.duration:Math.min(time,clip.duration);
            puffAmount=puffEnvelope(envelope,time);
        }
        // The gripping locks and the wound renderer own different contact
        // geometry. Keep their original mesh until those combined poses have
        // their own morph bindings; never detach a held item or move wound ink.
        // The side-lock swing is part of the same morph, so it stops with it.
        if(gripping||drawingWoundedBody)puffAmount=0;
        if (selected.equals("house_carry") && action.equals("waddle")) {
            // Walking owns the body; the carry clip retains the two closed locks.
            clips.get("waddle").sample(elapsed, pose);
        }
        if(carrying&&gripBones!=null&&!selected.equals("house_pickup")&&!selected.equals("house_deposit")&&!selected.equals("house_carry")){
            // Carry survives interruption; only the lock joints override another body action.
            for(Track track:clips.get("house_carry").tracks)if(track.bone==gripBones[0]||track.bone==gripBones[1])track.sample(0,pose[track.bone]);
        }
        if (behavior.equals("chew") || behavior.equals("sing")) {
            float wave = (float)(.5 + .5 * Math.sin(behaviorTicks * Math.PI * 2 / LivingPresentation.chewPeriod));
            for (int i=0; i<bones.length; i++) if (bones[i].name.equals(LivingPresentation.mouthBone))
                pose[i][7] *= LivingPresentation.mouthMinimum + (LivingPresentation.mouthMaximum - LivingPresentation.mouthMinimum) * wave;
        }
        var hat=io.github.longshou.mcyukkuri.family.HatCarry.shape(id);
        if(hat!=null&&!hatMotion.isEmpty()){
            float q=Math.clamp(hatPhase,0,1);if(hatMotion.equals("HAT_RETURN"))q=1-q;
            float rise=Math.min(1,q/.55f);rise=rise*rise*(3-2*rise);
            float forward=Math.max(0,(q-.4f)/.6f);forward=forward*forward*(3-2*forward);
            var root=pose[hat.get("bone").getAsInt()];root[1]+=hat.get("lift").getAsFloat()*rise;
            root[2]+=hat.get("forward").getAsFloat()*forward;
        }
        if(afloat)for(int i=0;i<bones.length;i++){Float roll=AFLOAT_ROLL.get(bones[i].name);if(roll!=null)pose[i][5]+=roll;}
        for (int i = 0; i < bones.length; i++) local(i, 0, world[i]);
        groundContacts();
        PoseStack.Pose transform = stack.last();
        featureTransforms.forEach((bone,matrix)->{matrix.set(world[bone]);if(bones[bone].parent>=0)matrix.mul(new Matrix4f(world[bones[bone].parent]).invert());});
        if(puffNormals!=null&&puffAmount>0&&!drawingWoundedBody){
            for(var n:puffNormals.normals)n.zero();
            for(int first:puffNormals.triangles){
                for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);
                normal.set(triangle[1]).sub(triangle[0]);edge.set(triangle[2]).sub(triangle[0]);normal.cross(edge);
                for(int corner=0;corner<3;corner++)puffNormals.normals[puffNormals.groups[first+corner]].add(normal);
            }
            for(var n:puffNormals.normals){if(n.lengthSquared()>1e-16f)n.normalize();else n.set(0,0,-1);}
        }
        if(houseNormals!=null&&!drawingWoundedBody){
            for(var n:houseNormals.normals)n.zero();
            for(int first:houseNormals.triangles){
                for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);
                normal.set(triangle[1]).sub(triangle[0]);edge.set(triangle[2]).sub(triangle[0]);normal.cross(edge);
                for(int corner=0;corner<3;corner++)houseNormals.normals[houseNormals.groups[first+corner]].add(normal);
            }
            for(var n:houseNormals.normals){if(n.lengthSquared()>1e-16f)n.normalize();else n.set(0,0,-1);}
        }
        var pregnantSurface=new PregnancyFaces.Surface();
        if(pregnancyBelly>.001f)for(int index=0;index<parts.size();index++)if(bodySurface(parts.get(index).name)){
            Part surface=puffAmount>0&&index==puffPartIndex?puffSurface:parts.get(index);
            for(int first=surface.start;first<surface.start+surface.count;first+=3){
                for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);pregnantSurface.add(triangle);
            }
        }
        for (int partIndex=0;partIndex<parts.size();partIndex++) {
            Part part=puffAmount>0?(puffPartIndex==partIndex?puffSurface:puffAttachments.getOrDefault(partIndex,parts.get(partIndex))):parts.get(partIndex);
            if(drawingWoundedBody&&io.github.longshou.mcyukkuri.body.WoundSurface.get(id).replaced.contains(part.name))continue;
            if(hairHidden(part,hairLoss))continue;
            if (!accessory && LivingPresentation.variants.get(id).accessoryPrefixes().stream().anyMatch(part.name::startsWith)) continue;
            // Explicit visibility also covers interruption, idle and resource reload.
            if (!part.anatomy.isEmpty() && !action.startsWith("anatomy_" + part.anatomy + "_")) continue;
            for (int first = part.start; first < part.start + part.count; first += 3) {
                // House expression cards overlap on the curved face. Their opaque
                // backs can hide neighbouring eyes with the no-cull entity material.
                // Keep the authored front ink; the head already supplies the skin.
                if(id.startsWith("house_")&&part.name.startsWith("face_")&&vertices[first*8+7]>=-.15f)continue;
                if(pregnancyBelly>.001f&&part.name.startsWith("face_")){
                    // The thin sprite boxes have opaque skin-coloured backs. On a curved face those backs
                    // receive inverted lighting through transparent pixels; the underlying head supplies skin.
                    if(vertices[first*8+7]>=-.15f)continue;
                    float[] uv=new float[6];
                    for(int corner=0;corner<3;corner++){skinBase(first+corner,triangle[corner]);uv[corner*2]=vertices[(first+corner)*8+3];uv[corner*2+1]=vertices[(first+corner)*8+4];}
                    PregnancyFaces.render(triangle,uv,pregnancyBelly,pregnancyContraction,delivery,stack,buffer,light,overlay,pregnantSurface);continue;
                }
                for (int corner = 0; corner < 3; corner++) skin(first + corner, triangle[corner]);
                normal.set(triangle[1]).sub(triangle[0]);
                edge.set(triangle[2]).sub(triangle[0]);
                normal.cross(edge);
                if (normal.lengthSquared() < 1.0e-16f) continue;
                normal.normalize();
                if(delivery!=null&&bodySurface(part.name)&&normal.z<-.15f){
                    float[] uv=new float[6];for(int corner=0;corner<3;corner++){uv[corner*2]=vertices[(first+corner)*8+3];uv[corner*2+1]=vertices[(first+corner)*8+4];}
                    if(delivery.drawMotherTriangle(stack,buffer,light,overlay,triangle,uv))continue;
                }
                // Vanilla entity buffers use quads: repeat the third corner.
                for (int corner = 0; corner < 4; corner++) {
                    int index = Math.min(corner, 2);
                    Vector3f point = triangle[index];
                    int uv = (first + index) * 8 + 3;
                    Vector3f vertexNormal=houseNormals!=null&&houseNormals.groups[first+index]>=0
                            ?houseNormals.normals[houseNormals.groups[first+index]]:normal;
                    if(puffNormals!=null&&puffAmount>0&&puffNormals.groups[first+index]>=0)
                        vertexNormal=puffBlendedNormal.set(normal).lerp(puffNormals.normals[puffNormals.groups[first+index]],puffAmount).normalize();
                    buffer.addVertex(transform.pose(), point.x / 16, point.y / 16, point.z / 16)
                            .setColor(255, 255, 255, 255).setUv(vertices[uv], vertices[uv + 1])
                            .setOverlay(overlay).setLight(light)
                            .setNormal(transform, vertexNormal.x, vertexNormal.y, vertexNormal.z);
                }
            }
        }
    }

    public boolean woundGeometryCompatible(){return sourceHash.matches(io.github.longshou.mcyukkuri.body.WoundSurface.get(id).runtimeSourceHash);}
    private void local(int i, float roll, Matrix4f matrix) {
        Bone bone = bones[i];
        float[] v = pose[i];
        if (bone.parent < 0) matrix.identity();
        else matrix.set(world[bone.parent]);
        matrix.translate(bone.x + v[0], bone.y + v[1], bone.z + v[2])
                .rotateZYX((v[5] + roll) * DEGREES, v[4] * DEGREES, v[3] * DEGREES)
                .scale(v[6], v[7], v[8]).translate(-bone.x, -bone.y, -bone.z);
    }
    private float contactHeight(GroundContact contact, float roll) {
        local(contact.bone, roll, contactMatrix);
        float lowest = Float.MAX_VALUE;
        for (Vector3f point : contact.points) lowest = Math.min(lowest, contactMatrix.transformPosition(point, contactPoint).y);
        return lowest;
    }
    private float contactRoll(GroundContact contact, float sign) {
        if (contactHeight(contact, sign * 90) < contact.clearance) return Float.NaN;
        float lo = 0, hi = 90;
        for (int k = 0; k < 14; k++) { float mid = (lo + hi) / 2; if (contactHeight(contact, sign * mid) >= contact.clearance) hi = mid; else lo = mid; }
        return sign * hi;
    }
    /** Placement-fit ground contact (preview/review/pose.js does the same): a long braid end that would pass
     *  below the ground swings about its bone's Z axis, whichever way clears it with the smaller angle. */
    private void groundContacts() {
        for (GroundContact contact : groundContacts) {
            if (contactHeight(contact, 0) >= contact.clearance) continue;
            float up = contactRoll(contact, 1), down = contactRoll(contact, -1), roll;
            if (Float.isNaN(up) && Float.isNaN(down)) roll = contactHeight(contact, 90) >= contactHeight(contact, -90) ? 90 : -90;
            else if (Float.isNaN(up)) roll = down;
            else if (Float.isNaN(down)) roll = up;
            else roll = Math.abs(up) <= Math.abs(down) ? up : down;
            pose[contact.bone][5] += roll;
            boolean[] moved = new boolean[bones.length];
            moved[contact.bone] = true; local(contact.bone, 0, world[contact.bone]);
            for (int i = contact.bone + 1; i < bones.length; i++)
                if (bones[i].parent >= 0 && moved[bones[i].parent]) { moved[i] = true; local(i, 0, world[i]); }
        }
    }

    public void renderLiving(String action,float elapsed,PoseStack stack,VertexConsumer buffer,int light,int overlay,
            boolean accessory,String behavior,float behaviorTicks,int hairLoss,io.github.longshou.mcyukkuri.entity.YukkuriEntity entity,
            net.minecraft.client.renderer.MultiBufferSource buffers){
        drawingWoundedBody=WoundRenderer.active(entity);
        if(drawingWoundedBody&&!woundGeometryCompatible()){
            drawingWoundedBody=false;if(!warnedWoundMismatch){org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Wound attachment disabled for incompatible resource-pack geometry: {}",id);warnedWoundMismatch=true;}
        }
        setExpression(expressionFor(action,elapsed,entity.getExpressionId()));
        hatMotion=entity.contactPose().equals("HAT_REMOVE")||entity.contactPose().equals("HAT_RETURN")?entity.contactPose():"";
        hatPhase=entity.contactTurn();
        try{render(action,elapsed,stack,buffer,light,overlay,accessory||hatMotion.equals("HAT_REMOVE"),behavior,behaviorTicks,hairLoss);
            if(drawingWoundedBody)WoundRenderer.render(entity,expressionFor(action,elapsed,entity.getExpressionId()),world,stack,buffer,buffers,light,overlay);
        }finally{drawingWoundedBody=false;hatMotion="";setExpression("");}
    }
    public void renderRemains(Object key,net.minecraft.nbt.CompoundTag data,PoseStack stack,VertexConsumer buffer,
                              net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay){
        var body=data.getCompound("Body");
        var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get(id);
        var wounds=io.github.longshou.mcyukkuri.body.BodyWound.read(body).stream()
                .map(w->io.github.longshou.mcyukkuri.body.WoundMigration.restore(id,mesh,body,w))
                .filter(java.util.Objects::nonNull).filter(mesh::valid).toList();
        drawingWoundedBody=!wounds.isEmpty()&&woundGeometryCompatible();
        setCarrying(false);setAfloat(false);setPregnancy(0,0);setDelivery(null);setExpression("sleep");
        boolean accessory=!data.contains("Accessory")||data.getBoolean("Accessory");
        try{
            render("idle",0,stack,buffer,light,overlay,accessory,"idle",0,body.getInt("HairLoss"));
            if(drawingWoundedBody)WoundRenderer.render(key,id,"sleep",wounds,0,body.getInt("Bandage")>0,data.getInt("Filling"),id.startsWith("house_"),world,stack,buffer,buffers,light,overlay);
            if(accessory)renderBadge(data.getInt("Medal"),stack,buffers,light,overlay);
        }finally{drawingWoundedBody=false;setExpression("");}
    }
    private void setExpression(String name){
        // A revision may carry its own named patch; otherwise borrow the alias's geometry.
        if(!expressionPatches.containsKey(name))name=geometryExpression(name);
        for(var patch:activePatches)System.arraycopy(restVertices,patch.start,vertices,patch.start,patch.values.length);
        activePatches=expressionPatches.getOrDefault(name,List.of());
        for(var patch:activePatches)System.arraycopy(patch.values,0,vertices,patch.start,patch.values.length);
    }

    private boolean hairHidden(Part part,int loss){
        if(loss==0||!(part.name.contains("hair")||part.name.contains("bang")||part.name.contains("braid")||part.name.startsWith("wasa_")))return false;
        if((loss&4)!=0)return true;
        if(part.name.contains("bang")||part.name.contains("crown")||part.name.contains("wrap")||part.name.startsWith("wasa_scalp"))return false;
        float x=0;for(int v=part.start;v<part.start+part.count;v++)x+=vertices[v*8];
        return x<0?(loss&1)!=0:(loss&2)!=0;
    }
    /** Place the lowest visible source vertex above the floor during rigid contact poses. */
    public float contactLift(float angle,boolean accessory,int loss){
        float minimum=Float.MAX_VALUE,c=(float)Math.cos(angle*DEGREES),s=(float)Math.sin(angle*DEGREES);
        for(var part:parts){
            if(!part.anatomy.isEmpty()||part.name.contains("puff")||hairHidden(part,loss))continue;
            if(!accessory&&LivingPresentation.variants.get(id).accessoryPrefixes().stream().anyMatch(part.name::startsWith))continue;
            for(int v=part.start;v<part.start+part.count;v++)minimum=Math.min(minimum,(vertices[v*8+1]*c-vertices[v*8+2]*s)/16);
        }
        return minimum==Float.MAX_VALUE?0:.006f-minimum;
    }
    /** The item follows the midpoint of the two actual transformed lock tips. */
    public Vector3f gripPoint(){
        if(!gripping||gripBones==null)return null;
        var result=new Vector3f();
        for(int i=0;i<2;i++)result.add(world[gripBones[i]].transformPosition(new Vector3f(gripPoints[i])));
        return result.div(32);
    }
    private void skin(int index, Vector3f target) {
        skinBase(index,target);
        io.github.longshou.mcyukkuri.family.LiveBirthGeometry.deform(target,pregnancyBelly,pregnancyContraction);
    }
    private void skinBase(int index, Vector3f target) {
        int offset = index * 8;
        Binding binding = bindings[index];
        target.set(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
        if(puffAmount>0)target.add(puffAmount*puffOffsets[index*3],puffAmount*puffOffsets[index*3+1],puffAmount*puffOffsets[index*3+2]);
        if (binding.weight > 0) {
            second.set(target);
            world[binding.second].transformPosition(second);
        }
        world[binding.first].transformPosition(target);
        if (binding.weight > 0) target.lerp(second, binding.weight);
        int feature=featureBones[index];if(feature>=0)featureTransforms.get(feature).transformPosition(target);
    }
}
