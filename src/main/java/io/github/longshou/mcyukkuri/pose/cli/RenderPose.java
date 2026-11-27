package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.body.SurfaceMark;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import io.github.longshou.mcyukkuri.pose.*;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Preview transport: core splice, clips, composition, care and skin; triangle normals for the software rasterizer. */
final class RenderPose implements Overlays.Contact {
    private static final Map<String,RenderPose> MODELS=new LinkedHashMap<>(4,.75f,true);
    private static final Map<String,byte[]> SOURCES=new HashMap<>();
    private static int serial;
    final JsonObject data;
    final Rig.Bone[] bones;
    final float[][] pose,defaults;
    final Matrix4f[] world;
    final Compose composer;
    final float[] rest,offsets;
    float[] source;
    final Skin.Binding[] bindings;
    final int[] features;
    final Matrix4f[] featureTransforms;
    private final Matrix4f featureInverse=new Matrix4f();
    final Map<String,Clips.Clip> clips=new HashMap<>();
    final int root;
    private int hat = -1;
    private float hatScale = 1;
    private Vector3f hatAnchor;
    final Vector3f scratch=new Vector3f();
    Vector3f anchor;
    float puff,retention;
    /** Rest body frame of the swaddle retention swelling (Skin.retentionFrame); the request's "retention" is its amount, 0..1. */
    final Skin.RetentionFrame body;
    private final BitSet overSkin=new BitSet();
    private final Skin.Deform retain;
    /** Limbs that give way to the request's "wall" (faces around the body axis in blocks, after its yaw), built on first use. */
    private WallYield wallYield;
    private boolean wallYieldBuilt;
    private SnugglePose snuggle;
    private float looseTop;
    private ReviewMarks.Wet wet;
    private ReviewMarks.Scratch scratchMark;
    private JsonObject reviewActor;
    RenderPose(JsonObject request) {
        data=request.getAsJsonObject("model");
        if(request.has("fit")&&!request.get("fit").isJsonNull())Splice.fit(data,request.getAsJsonObject("fit"),hash->true);
        if(request.has("revision")&&!request.get("revision").isJsonNull()&&!Splice.revision(data,request.getAsJsonObject("revision")))
            throw new IllegalArgumentException("Revision skeleton mismatch");
        bones=Rig.bones(data.getAsJsonArray("bones"));pose=Rig.values(bones.length);defaults=Rig.values(bones.length);
        featureTransforms=new Matrix4f[bones.length];
        world=Rig.matrices(bones.length);composer=new Compose(bones);
        int found=-1;for(int i=0;i<bones.length;i++)if(bones[i].name.equals("root"))found=i;
        if(found<0)throw new IllegalArgumentException("Missing root");root=found;
        rest=Rig.floats(data.getAsJsonArray("vertices"));source=rest;offsets=new float[rest.length/8*3];
        bindings=new Skin.Binding[rest.length/8];features=new int[bindings.length];Arrays.fill(features,-1);
        var weights=data.getAsJsonArray("weights");for(int i=0;i<bindings.length;i++){var w=Rig.floats(weights.get(i).getAsJsonArray());bindings[i]=new Skin.Binding((int)w[0],(int)w[1],w[2]);}
        for(var raw:data.getAsJsonArray("parts")){var p=raw.getAsJsonObject();if(p.has("feature_bone")){
            int b=p.get("feature_bone").getAsInt();Arrays.fill(features,start(p),start(p)+count(p),b);featureTransforms[b]=new Matrix4f();}}
        if(data.has("puff_surface"))offsets(data.getAsJsonObject("puff_surface"));
        if(data.has("puff_attachments"))for(var raw:data.getAsJsonArray("puff_attachments"))offsets(raw.getAsJsonObject());
        if(data.has("puff_offsets"))for(var raw:data.getAsJsonArray("puff_offsets")){
            var p=raw.getAsJsonObject();var part=data.getAsJsonArray("parts").get(p.get("part_index").getAsInt()).getAsJsonObject();
            System.arraycopy(Rig.floats(p.getAsJsonArray("offsets")),0,offsets,start(part)*3,count(part)*3);}
        data.getAsJsonObject("clips").entrySet().forEach(e->clips.put(e.getKey(),Clips.read(e.getValue().getAsJsonObject(),bones.length)));
        if(data.has("behavior_clips"))data.getAsJsonObject("behavior_clips").entrySet().forEach(e->clips.put(e.getKey(),Clips.read(e.getValue().getAsJsonObject(),bones.length)));
        clips.get("idle").sample(0,defaults);
        var ranges=new ArrayList<Integer>();
        for(var raw:data.getAsJsonArray("parts")){
            var p=raw.getAsJsonObject();String name=p.get("name").getAsString(),group=OfflineModel.group(name);
            if(group.equals("body")){ranges.add(start(p));ranges.add(count(p));}
            if(group.equals("hair")||group.equals("braid")||name.contains("back_lock"))overSkin.set(start(p),start(p)+count(p));
            if(WallYield.loose(name))for(int i=start(p);i<start(p)+count(p);i++)looseTop=Math.max(looseTop,rest[i*8+1]);
        }
        body=Skin.retentionFrame(rest,ranges.stream().mapToInt(Integer::intValue).toArray());
        retain=(index,point)->Skin.deformRetention(point,retention,body,overSkin.get(index));
    }
    private static int start(JsonObject p){return p.get("start").getAsInt();}
    private static int count(JsonObject p){return p.get("count").getAsInt();}
    private int bone(String name) {
        for (int i = 0; i < bones.length; i++) if (bones[i].name.equals(name)) return i;
        throw new IllegalArgumentException("Unknown attachment bone: " + name);
    }
    private Matrix4f mouthAttachment(String mouthBone) {
        var grip = new LipGrip(world[bone(mouthBone)]);
        for (var raw : data.getAsJsonArray("parts")) {
            var part = raw.getAsJsonObject();
            if (part.get("name").getAsString().equals("face_mouth"))
                for (int i = start(part); i < start(part) + count(part); i++) grip.include(skin(i));
        }
        return new Matrix4f().translationRotate(grip.point(), grip.rotation());
    }
    private Clips.Track itemTrack(JsonObject clip) {
        var item = clip.getAsJsonObject("props").getAsJsonObject("item");
        var keys = item.getAsJsonArray("keys");
        var samples = new float[keys.size()][];
        for (int i = 0; i < samples.length; i++) samples[i] = Rig.floats(keys.get(i).getAsJsonArray());
        return new Clips.Track(bone(item.get("bone").getAsString()), 0, samples);
    }
    private void offsets(JsonObject p){var v=Rig.floats(p.getAsJsonArray("offsets"));System.arraycopy(v,0,offsets,start(p)*3,v.length);}
    public void compose(){composer.world(pose,world,hat,hatScale,hatAnchor,null);updateFeatures();}
    private void updateFeatures(){Compose.features(bones,world,featureTransforms,featureInverse);}
    private Vector3f skin(int i){var p=new Vector3f();Skin.base(i,p,scratch,source,bindings,world,retention>0?retain:null,puff,offsets,features,featureTransforms);return p;}
    public float minimumY(){float min=Float.POSITIVE_INFINITY;for(var raw:data.getAsJsonArray("parts")){
        var p=raw.getAsJsonObject();if(OfflineModel.group(p.get("name").getAsString()).equals("body"))for(int i=start(p);i<start(p)+count(p);i++)min=Math.min(min,skin(i).y);
    }return min;}
    public Vector3f mouth(){return world[root].transformPosition(new Vector3f(anchor));}
    record Drawn(JsonObject data,float[] vertices,boolean packed) {
        JsonObject encode() {
            data.add("vertices",vertexData(vertices,packed));
            return data;
        }
    }
    private JsonObject frame(JsonObject request, WallYield.Face partnerFace) {
        return draw(request,partnerFace,null).encode();
    }
    Drawn draw(JsonObject request, WallYield.Face partnerFace, ReviewPair.Anatomy anatomy){
        String action=request.get("action").getAsString();float t=request.get("time").getAsFloat();
        for(int i=0;i<pose.length;i++)System.arraycopy(defaults[i],0,pose[i],0,9);
        clips.get(action).sample(t,pose);
        if(request.has("intensity")&&!request.get("intensity").isJsonNull()){
            float q=request.get("intensity").getAsFloat();for(int b=0;b<pose.length;b++)for(int c=0;c<9;c++)if(c!=3&&c!=5)pose[b][c]=defaults[b][c]+(pose[b][c]-defaults[b][c])*q;
            float tilt=Math.abs(pose[root][3])+Math.abs(pose[root][5]);if(tilt>12){pose[root][3]*=12/tilt;pose[root][5]*=12/tilt;}
        }
        if(request.has("adjustment")&&!request.get("adjustment").isJsonNull()){
            var a=request.getAsJsonObject("adjustment");if(a.has("translation")){var v=Rig.floats(a.getAsJsonArray("translation"));for(int i=0;i<3;i++)pose[root][i]+=v[i];}
            if(a.has("pitch"))pose[root][3]+=a.get("pitch").getAsFloat();
        }
        if(anatomy!=null)Overlays.extendAnatomy(bones,pose,clips.get("anatomy_"+anatomy.part()+"_extend"),anatomy.amount());
        var clip=data.getAsJsonObject("clips").getAsJsonObject(action);
        if(data.has("behavior_clips")&&data.getAsJsonObject("behavior_clips").has(action))clip=data.getAsJsonObject("behavior_clips").getAsJsonObject(action);
        puff=clip.has("puff_envelope")?Clips.puffEnvelope(Rig.floats(clip.getAsJsonArray("puff_envelope")),clips.get(action).time(t)):0;
        if(request.has("scratch"))puff=0;
        retention=request.has("retention")?request.get("retention").getAsFloat():0;
        source=rest.clone();String expression=request.get("expression").getAsString();
        var patches=data.getAsJsonObject("expression_patches");
        if(patches!=null&&!patches.has(expression)&&data.has("patch_source")&&data.getAsJsonObject("patch_source").has(expression))expression=data.getAsJsonObject("patch_source").get(expression).getAsString();
        if(patches!=null&&patches.has(expression))for(var raw:patches.getAsJsonArray(expression)){
            var patch=raw.getAsJsonObject();var part=data.getAsJsonArray("parts").get(patch.get("index").getAsInt()).getAsJsonObject();
            var values=Rig.floats(patch.getAsJsonArray("vertices"));System.arraycopy(values,0,source,start(part)*8,values.length);
        }
        var worn = request.getAsJsonObject("hat");
        if (worn != null) {
            hat = bone("hat_follow");
            hatScale = worn.get("scale").getAsFloat();
            hatAnchor = new Vector3f(Rig.floats(worn.getAsJsonArray("anchor")));
        } else {
            hatScale = 1;
        }
        compose();
        var rear=anatomy!=null&&anatomy.rear()!=null?anatomy.rear().transform(world[root]):null;
        if(request.has("care")){
            var care=request.getAsJsonObject("care");anchor=new Vector3f(Rig.floats(care.getAsJsonArray("anchor")));
            var target=Rig.floats(care.getAsJsonArray("target"));
            Overlays.care(this,pose,world,root,!action.equals("first_toilet_assist"),care.has("rim")&&care.get("rim").getAsBoolean(),
                    target[0],target[1],target[2],care.get("blend").getAsFloat());updateFeatures();
        }
        float scale=request.get("scale").getAsFloat();float[] output=new float[rest.length];
        // A wall beside the body: the entity's yaw turns the output, and with "yield" the loose parts give way as in the game.
        var wall=request.has("wall")&&!request.get("wall").isJsonNull()?request.getAsJsonObject("wall"):null;
        var faces=new ArrayList<WallYield.Face>();Matrix4f turn=null;boolean yield=partnerFace!=null;
        if(partnerFace!=null)faces.add(partnerFace);
        if(wall!=null){
            turn=new Matrix4f().rotateY((float)Math.toRadians(wall.get("yaw").getAsFloat()));yield=wall.get("yield").getAsBoolean();
            for(var raw:wall.getAsJsonArray("faces")){var f=Rig.floats(raw.getAsJsonArray());faces.add(new WallYield.Face(f[0],f[1],f[2],f[3],f[4]));}
        }
        if(wall!=null||partnerFace!=null){
            if(!wallYieldBuilt){wallYieldBuilt=true;var parts=data.getAsJsonArray("parts");int n=parts.size();
                var names=new String[n];var starts=new int[n];var counts=new int[n];
                for(int i=0;i<n;i++){var p=parts.get(i).getAsJsonObject();names[i]=p.get("name").getAsString();starts[i]=start(p);counts[i]=count(p);}
                wallYield=WallYield.of(data.get("id").getAsString(),bones,bindings,rest,names,starts,counts);}
            var wallFrame=turn==null?new Matrix4f():new Matrix4f(turn);
            if(wallYield!=null){if(yield)wallYield.solve((i,target)->target.set(skin(i)),world,wallFrame.scale(scale/16),faces,kind->false);else wallYield.clear();}
        }
        boolean wantPaper=request.has("paperCover")&&request.get("paperCover").isJsonArray();
        boolean wantLeash=request.has("leashLoop")&&request.get("leashLoop").isJsonArray();
        List<String> accPrefixes=new ArrayList<>();
        if(wantPaper)for(var raw:request.getAsJsonArray("paperCover"))accPrefixes.add(raw.getAsString());
        else if(wantLeash)for(var raw:request.getAsJsonArray("leashLoop"))accPrefixes.add(raw.getAsString());
        var replaced = new ArrayList<String>();
        if (worn != null) for (var item : worn.getAsJsonArray("accessories")) replaced.add(item.getAsString());
        List<Float> pBody=wantPaper||wantLeash?new ArrayList<>():null,pMesh=wantPaper?new ArrayList<>():null,pHat=wantPaper?new ArrayList<>():null;
        List<Float> lHair=wantLeash?new ArrayList<>():null;
        Matrix4f localToWall=turn!=null?new Matrix4f(turn).scale(1/16f):new Matrix4f().scale(1/16f);
        Matrix4f wallToLocal=new Matrix4f(localToWall).invert();
        for(int pi=0;pi<data.getAsJsonArray("parts").size();pi++){
            var part=data.getAsJsonArray("parts").get(pi).getAsJsonObject();
            String organ=part.has("anatomy")?part.get("anatomy").getAsString():"";
            boolean layered=anatomy!=null&&organ.equals(anatomy.part())&&anatomy.amount()>.001f;
            if(!organ.isEmpty()&&!layered&&!action.startsWith("anatomy_"+organ+"_"))continue;
            boolean smooth=false;
            if(puff>0){if(data.has("puff_surface")&&data.getAsJsonObject("puff_surface").get("part_index").getAsInt()==pi){part=data.getAsJsonObject("puff_surface");smooth=true;}
                if(data.has("puff_attachments"))for(var raw:data.getAsJsonArray("puff_attachments"))if(raw.getAsJsonObject().get("part_index").getAsInt()==pi)part=raw.getAsJsonObject();}
            int start=start(part),end=start+count(part);
            String origName=data.getAsJsonArray("parts").get(pi).getAsJsonObject().get("name").getAsString();
            boolean loose=yield&&WallYield.loose(origName);
            boolean isBody=OfflineModel.group(origName).equals("body")||origName.endsWith("_rump_mark");
            boolean isAcc=(wantPaper||wantLeash)&&accPrefixes.stream().anyMatch(origName::startsWith);
            boolean skipTent=origName.startsWith("face_")||origName.contains("puff");
            boolean isLeashHair=wantLeash&&!isBody&&!isAcc&&!skipTent&&organ.isEmpty()
                    &&!origName.contains("rump")&&!origName.contains("rear_mark")&&!origName.contains("tail")&&!origName.contains("wing");
            if (worn != null && replaced.stream().anyMatch(origName::startsWith)) continue;
            for(int i=start;i<end;i++){var p=skin(i);if(yield&&wallYield!=null)wallYield.apply(i,p);
                if(layered&&rear!=null)rear.transformPosition(p);
                p.mul(scale);
                if(wantPaper&&!skipTent){
                    var tp=new Vector3f(p);
                    if(loose)PaperTent.pressPoint(tp,localToWall,wallToLocal,faces,0f);
                    if(isBody){pBody.add(tp.x);pBody.add(tp.y);pBody.add(tp.z);}
                    if(isAcc){pHat.add(tp.x);pHat.add(tp.y);pHat.add(tp.z);}
                    else{pMesh.add(tp.x);pMesh.add(tp.y);pMesh.add(tp.z);}
                } else if (wantLeash) {
                    if (isBody) { pBody.add(p.x); pBody.add(p.y); pBody.add(p.z); }
                    else if (isLeashHair) { lHair.add(p.x); lHair.add(p.y); lHair.add(p.z); }
                }
                if(turn!=null)turn.transformPosition(p);
                if(loose){float keep=WallYield.press(faces,p.x/16,p.y/16,p.z/16);p.x*=keep;p.z*=keep;}
                int k=i*8;output[k]=p.x;output[k+1]=p.y;output[k+2]=p.z;output[k+3]=source[k+3];output[k+4]=source[k+4];}
            for(int i=start;i<end;i+=3){int k=i*8;
                String name=origName;
                if(data.has("house")&&data.get("house").getAsBoolean()&&name.startsWith("face_")&&source[k+7]>=-.15){Arrays.fill(output,k,k+24,0);continue;}
                var a=new Vector3f(output[k+8]-output[k],output[k+9]-output[k+1],output[k+10]-output[k+2]);
                var b=new Vector3f(output[k+16]-output[k],output[k+17]-output[k+1],output[k+18]-output[k+2]);a.cross(b);
                if(a.length()>1e-8f){a.normalize();for(int j=0;j<3;j++){output[k+j*8+5]=a.x;output[k+j*8+6]=a.y;output[k+j*8+7]=a.z;}}
            }
            if(smooth)smooth(output,start,end,puff);
        }
        var out=new JsonObject();
        boolean packed = request.has("vertexFormat") && request.get("vertexFormat").getAsString().equals("float32le");
        if (request.has("emote")) {
            var cue = request.getAsJsonObject("emote");
            out.addProperty("emote", EmoteCue.select(cue.get("face").getAsString(), cue.get("sweat").getAsBoolean(),
                    cue.get("elapsed").getAsFloat(), cue.get("duration").getAsFloat(),
                    () -> new EmoteCue.Step(cue.get("stepSeconds").getAsFloat(), cue.get("symbol").getAsString())));
        }
        if (request.has("leakStream") && request.get("leakStream").getAsBoolean())
            out.add("leak_stream", vertexData(leakStream(output, scale), packed));
        if (request.has("scratch")) {
            var spec = request.getAsJsonObject("scratch");
            if (scratchMark == null || !spec.equals(scratchMark.spec))
                scratchMark = new ReviewMarks.Scratch(data.get("id").getAsString(), spec);
            addMark(out, scratchMark.draw(this, expression, scale), scratchMark.texture, 255, packed);
        } else if (request.has("leakAge")) {
            int age = request.get("leakAge").getAsInt();
            int alpha = ExcitedLeak.soleOpacity(ExcitedLeak.opacity(expression, age >= 0, age), puff, false);
            if (alpha > 0) {
                if (wet == null) wet = new ReviewMarks.Wet(data.get("id").getAsString());
                addMark(out, wet.draw(world, scale), SurfaceMark.LEAK_WET_TEXTURE, alpha, packed);
            }
        }
        if (request.has("outlet")) {
            var point = world[root].transformPosition(new Vector3f(Rig.floats(request.getAsJsonArray("outlet")))).mul(scale);
            out.add("outlet", OfflineModel.array(new float[]{point.x, point.y, point.z}));
        }
        if (request.has("held")) {
            var held = request.getAsJsonObject("held");
            Matrix4f matrix = switch (held.get("joint").getAsString()) {
                case "mouth" -> mouthAttachment(held.get("mouthBone").getAsString());
                case "item" -> new Matrix4f().scale(16).mul(Overlays.item(bones, world, itemTrack(clip),
                        null, clips.get(action).time(t), 0)).scale(1 / 16f);
                default -> throw new IllegalArgumentException("Unknown held prop joint");
            };
            out.add("held", Authoring.rows(new Matrix4f().scale(scale).mul(matrix)));
        }
        if (worn != null) out.add("hat", Authoring.rows(new Matrix4f().scale(scale).mul(world[hat])));
        if(wantPaper){
            if (worn != null) {
                var outline = Rig.floats(worn.getAsJsonArray("outline"));
                var point = new Vector3f();
                for (int i = 0; i < outline.length; i += 3) {
                    world[hat].transformPosition(point.set(outline[i], outline[i + 1], outline[i + 2])).mul(scale);
                    pHat.add(point.x); pHat.add(point.y); pHat.add(point.z);
                }
            }
            var grid=PaperTent.build(floatsOf(pBody),pBody.size(),floatsOf(pMesh),pMesh.size(),floatsOf(pHat),pHat.size(),
                    yield?faces:List.of(),yield&&!faces.isEmpty()?localToWall:null,Double.POSITIVE_INFINITY);
            if(grid!=null){
                out.add("paper_top", vertexData(sheetTris(grid.top(),grid.rows(),grid.cols(),false,turn), packed));
                out.add("paper_under", vertexData(sheetTris(grid.under(),grid.rows(),grid.cols(),true,turn), packed));
            }
        }
        if (wantLeash) {
            var loop = new LeashLoop.Builder();
            if (loop.build(floatsOf(pBody), pBody.size(), floatsOf(lHair), lHair.size(), 0f)) {
                float[] verts = Arrays.copyOf(loop.vertices(), loop.vertexCount());
                if (turn != null) {
                    var tp = new Vector3f();
                    var tn = new Vector3f();
                    for (int i = 0; i < verts.length; i += 8) {
                        turn.transformPosition(tp.set(verts[i], verts[i + 1], verts[i + 2]));
                        turn.transformDirection(tn.set(verts[i + 5], verts[i + 6], verts[i + 7])).normalize();
                        verts[i] = tp.x; verts[i + 1] = tp.y; verts[i + 2] = tp.z;
                        verts[i + 5] = tn.x; verts[i + 6] = tn.y; verts[i + 7] = tn.z;
                    }
                }
                out.add("leash_loop", vertexData(verts, packed));
            }
        }
        if (packed) return new Drawn(out,output,true);
        var matrices=new JsonArray();for(var m:world){var flat=new JsonArray();for(var row:Authoring.rows(m))for(var x:row.getAsJsonArray())flat.add(x);matrices.add(flat);}out.add("world",matrices);
        out.add("root_pose",OfflineModel.array(pose[root]));return new Drawn(out,output,false);
    }
    private static JsonElement vertexData(float[] vertices, boolean packed) {
        if (!packed) return OfflineModel.array(vertices);
        var bytes = ByteBuffer.allocate(vertices.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(vertices);
        return new JsonPrimitive(Base64.getEncoder().encodeToString(bytes.array()));
    }

    private static void addMark(JsonObject out, float[] vertices, String texture, int opacity, boolean packed) {
        var mark = new JsonObject();
        mark.add("vertices", vertexData(vertices, packed));
        mark.addProperty("texture", texture);
        mark.addProperty("opacity", opacity);
        out.add("surfaceMark", mark);
    }

    private float[] leakStream(float[] posed, float scale) {
        var parts = data.getAsJsonArray("parts");
        for (int index = 0; index < parts.size(); index++) {
            var part = parts.get(index).getAsJsonObject();
            if (!part.get("name").getAsString().equals("face_effect_mouth")) continue;
            if (puff > 0 && data.has("puff_attachments")) {
                for (var raw : data.getAsJsonArray("puff_attachments")) {
                    var attachment = raw.getAsJsonObject();
                    if (attachment.get("part_index").getAsInt() == index) part = attachment;
                }
            }
            int first = start(part) * 8, end = first + count(part) * 8;
            var card = new Vector3f();
            for (int i = first; i < end; i += 24)
                if (source[i + 7] < -.15f) card.add(triangleNormal(posed, i));
            boolean lifted = !data.get("id").getAsString().startsWith("house_") && card.lengthSquared() > 1e-16f;
            if (lifted) card.normalize();
            var output = new float[end - first];
            int at = 0;
            for (int i = first; i < end; i += 24) {
                if (source[i + 7] >= -.15f) continue;
                var normal = triangleNormal(posed, i);
                if (normal.lengthSquared() < 1e-16f) continue;
                var lift = lifted ? card : normal.normalize();
                float amount = (ExcitedLeak.STREAM_LIFT + (lifted ? Skin.FACE_CARD_LIFT : 0)) * scale;
                for (int vertex = i; vertex < i + 24; vertex += 8) {
                    var point = new Vector3f(posed[vertex], posed[vertex + 1], posed[vertex + 2]).fma(amount, lift);
                    at = emitVert(output, at, point, posed[vertex + 3], posed[vertex + 4], lift);
                }
            }
            return Arrays.copyOf(output, at);
        }
        // The game skips the overlay on rigs without an authored mouth-effect card.
        return new float[0];
    }

    private static Vector3f triangleNormal(float[] v, int i) {
        return new Vector3f(v[i+8]-v[i], v[i+9]-v[i+1], v[i+10]-v[i+2])
                .cross(v[i+16]-v[i], v[i+17]-v[i+1], v[i+18]-v[i+2]);
    }
    private static float[] floatsOf(List<Float> list){float[] r=new float[list.size()];for(int i=0;i<r.length;i++)r[i]=list.get(i);return r;}
    private static float[] sheetTris(float[][][] pts,int rows,int cols,boolean inward,Matrix4f turn){
        float[] out=new float[rows*cols*6*8];int at=0;
        Vector3f pa=new Vector3f(),pb=new Vector3f(),pc=new Vector3f(),pd=new Vector3f(),n=new Vector3f();
        for(int r=0;r<rows;r++){
            float v0=rows==8&&r>6?.75f-.20f*(r-6):.25f+.50f*r/Math.min(6,rows);
            float v1=rows==8&&r+1>6?.75f-.20f*(r+1-6):.25f+.50f*(r+1)/Math.min(6,rows);
            for(int c=0;c<cols;c++){
                float u0=.1875f+.625f*c/cols,u1=.1875f+.625f*(c+1)/cols;
                pa.set(pts[r][c]);pb.set(pts[r+1][c]);pc.set(pts[r+1][c+1]);pd.set(pts[r][c+1]);
                if(turn!=null){turn.transformPosition(pa);turn.transformPosition(pb);turn.transformPosition(pc);turn.transformPosition(pd);}
                n.set(pb).sub(pa).cross(new Vector3f(pd).sub(pa));
                if(inward)n.negate();
                if(n.length()>1e-8f)n.normalize();
                if(!inward){
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pb,u0,v1,n);at=emitVert(out,at,pc,u1,v1,n);
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pc,u1,v1,n);at=emitVert(out,at,pd,u1,v0,n);
                }else{
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pd,u1,v0,n);at=emitVert(out,at,pc,u1,v1,n);
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pc,u1,v1,n);at=emitVert(out,at,pb,u0,v1,n);
                }
            }
        }
        return out;
    }
    static int emitVert(float[] out,int at,Vector3f p,float u,float v,Vector3f n){
        out[at]=p.x;out[at+1]=p.y;out[at+2]=p.z;out[at+3]=u;out[at+4]=v;out[at+5]=n.x;out[at+6]=n.y;out[at+7]=n.z;return at+8;
    }
    private void smooth(float[] output,int start,int end,float amount){
        var groups=new HashMap<String,Vector3f>();
        for(int i=start;i<end;i+=3){int k=i*8;var n=new Vector3f(output[k+8]-output[k],output[k+9]-output[k+1],output[k+10]-output[k+2]).cross(new Vector3f(output[k+16]-output[k],output[k+17]-output[k+1],output[k+18]-output[k+2]));
            for(int j=i;j<i+3;j++)groups.computeIfAbsent(key(j),s->new Vector3f()).add(n);}
        for(int i=start;i<end;i++){var n=new Vector3f(groups.get(key(i)));if(n.length()>1e-8f){n.normalize();int k=i*8;var blended=new Vector3f(output[k+5],output[k+6],output[k+7]).lerp(n,amount);if(blended.length()>1e-8f){blended.normalize();output[k+5]=blended.x;output[k+6]=blended.y;output[k+7]=blended.z;}}}
    }
    private String key(int i){return Math.round(rest[i*8]*1e5)+","+Math.round(rest[i*8+1]*1e5)+","+Math.round(rest[i*8+2]*1e5);}

    SideSurface.Pose cheek(boolean right, int tick) {
        if (snuggle == null) snuggle = new SnugglePose(data);
        return snuggle.at(right, tick);
    }

    JsonObject actor(Path assets) throws IOException {
        if(reviewActor==null)reviewActor=ReviewActor.describe(assets,data.get("id").getAsString(),1);
        return reviewActor;
    }

    static JsonObject run(Path assets,JsonObject request) throws IOException {
        if(request.get("op").getAsString().equals("register")){
            String id=Integer.toString(++serial);
            var bytes=new ByteArrayOutputStream();try(var zip=new GZIPOutputStream(bytes)){zip.write(request.toString().getBytes(StandardCharsets.UTF_8));}
            SOURCES.put(id,bytes.toByteArray());var model=new RenderPose(request);cache(id,model);
            var out=new JsonObject();out.addProperty("id",id);out.add("model",model.data);return out;
        }
        String id=request.get("id").getAsString();
        if(request.get("op").getAsString().equals("release")){MODELS.remove(id);SOURCES.remove(id);return new JsonObject();}
        var model = registered(id);
        var partner = request.getAsJsonObject("partner");
        if (partner == null) return model.frame(request, null);
        if (request.has("vertexFormat")) partner.add("vertexFormat", request.get("vertexFormat"));
        var peer = registered(partner.get("id").getAsString());
        var stage = request.getAsJsonObject("pairStage");
        if (stage != null && !stage.get("kind").getAsString().equals("rub"))
            return ReviewPair.render(assets, model, peer, request, partner, stage);
        return nuzzle(model, peer, request, partner, stage != null);
    }

    private static JsonObject nuzzle(RenderPose model, RenderPose peer, JsonObject request, JsonObject partner, boolean worldSpace) {
        if (!request.get("action").getAsString().equals("snuggle_left")
                || !partner.get("action").getAsString().equals("snuggle_right"))
            throw new IllegalArgumentException("Peer nuzzle requires snuggle_left and snuggle_right");

        // MD-28's established level rub: the game draws both actors at the same published tick.
        int tick = (int) Math.floor(request.get("time").getAsDouble() * 20);
        request.addProperty("time", tick / 20f);
        partner.addProperty("time", tick / 20f);
        float scale = request.get("scale").getAsFloat(), peerScale = partner.get("scale").getAsFloat();
        var left = model.cheek(false, tick);
        var right = peer.cheek(true, tick);
        float spacing = (float) SideSurface.meet(left, scale / 16d, right, peerScale / 16d, 0, 0).separation();
        double leftReach = left.reach(false) * scale / 16, rightReach = right.reach(true) * peerScale / 16;
        float low = -Math.max(scale, peerScale) / 4;
        float high = Math.max(scale * (model.looseTop / 16 + .25f), peerScale * (peer.looseTop / 16 + .25f));
        var leftFace = WallYield.partnerFace(-1, 0, spacing, leftReach, rightReach, low, high);
        var rightFace = WallYield.partnerFace(1, 0, spacing, rightReach, leftReach, low, high);
        var first = model.draw(request, leftFace, null);
        var second = peer.draw(partner, rightFace, null);
        if (worldSpace) ReviewPair.place(first.vertices(), new Matrix4f().translate(spacing * 16, 0, 0));
        return ReviewPair.combine(first, second, worldSpace ? 0 : spacing * 16);
    }
    private static RenderPose registered(String id) throws IOException {
        if(!SOURCES.containsKey(id))throw new IllegalArgumentException("Unknown registered pose: "+id);
        var model=MODELS.get(id);
        if(model==null){try(var zip=new GZIPInputStream(new ByteArrayInputStream(SOURCES.get(id)))){
            model=new RenderPose(JsonParser.parseReader(new InputStreamReader(zip,StandardCharsets.UTF_8)).getAsJsonObject());cache(id,model);
        }}
        return model;
    }
    private static void cache(String id,RenderPose model){
        // Two active actors cover ordinary strips; compressed inputs retain larger scenes without retaining every parsed rig.
        if(MODELS.size()==2)MODELS.remove(MODELS.keySet().iterator().next());MODELS.put(id,model);
    }
}
