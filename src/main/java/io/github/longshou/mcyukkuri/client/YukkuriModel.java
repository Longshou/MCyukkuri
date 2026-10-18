package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.Behaviors;
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
    private final Map<Part,Integer> staticHairMasks = new java.util.IdentityHashMap<>();
    private final HouseNormals houseNormals;
    private final SkinningCache skinningCache;
    private final int[] bodyFloorVertices;
    private int woundTriangle=-1;
    private boolean drawingWoundedBody;
    private boolean adultStatePose;
    private float pregnancyBelly,pregnancyContraction;
    /** The aquatic Marisa's larger hat: the hat bone scaled about its seat on the head. */
    private float hatScale=1;
    /** Accessory damage drawn this frame (0 whole, 1 frayed, 2 torn, 3 pieced back together) and its size relative to
     *  the body (headwear.Headwear.visual).  Damage keeps the whole mesh: square face cells are omitted and filled
     *  with a local-colour inner cap (client/HatDamageFaces). */
    private int hatDamage;
    private float hatFit=1;
    private int hatMaskGeneration=-1;
    private float[] hatBounds;
    private int[] hatSeatVertices;
    private final java.util.Map<Integer,HatDamageFaces.Face[]> hatFaces=new java.util.HashMap<>();
    private int hatFacesLevel=-1;
    private HatDamageFaces.Sampler hatSampler;
    private VertexConsumer hatCapBuffer;
    private final Vector3f hatSeat=new Vector3f();
    private final Vector3f[] faceCorners={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
    private final Vector3f[] cellCorners={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
    private final Vector3f faceNormal=new Vector3f(),cellTemp=new Vector3f();
    private final float[] faceUv=new float[8];
    private final float[] cellUv=new float[8];
    private void refreshHatMasks(){
        if(hatMaskGeneration==HatDamageMask.generation()&&hatBounds!=null)return;
        hatMaskGeneration=HatDamageMask.generation();hatFaces.clear();hatFacesLevel=-1;
        var ranges=new java.util.ArrayList<Integer>();
        for(var p:parts)if(accessoryPart(p)){ranges.add(p.start);ranges.add(p.count);}
        int[] r=ranges.stream().mapToInt(Integer::intValue).toArray();
        hatBounds=HatDamageMask.bounds(restVertices,r);
        // The seat: the lowest accessory vertices, about which a small (not yet regrown) hat is drawn.
        float low=hatBounds[1]+(hatBounds[4]-hatBounds[1])*.15f;
        var seat=new java.util.ArrayList<Integer>();
        for(int i=0;i<r.length;i+=2)for(int v=r[i];v<r[i]+r[i+1];v++)if(restVertices[v*8+1]<=low)seat.add(v);
        hatSeatVertices=seat.stream().mapToInt(Integer::intValue).toArray();
    }
    private HatDamageFaces.Face[] hatFaces(int partIndex){
        refreshHatMasks();
        if(hatFacesLevel!=hatDamage){hatFaces.clear();hatFacesLevel=hatDamage;}
        return hatFaces.computeIfAbsent(partIndex,i->{var p=parts.get(i);return HatDamageFaces.build(restVertices,p.start,p.count,p.name,hatBounds,hatDamage,hatSampler);});
    }
    private void poseHatSeat(){
        refreshHatMasks();hatSeat.zero();if(hatSeatVertices.length==0)return;
        var point=new Vector3f();
        for(int v:hatSeatVertices){skin(v,point);hatSeat.add(point);}
        hatSeat.div(hatSeatVertices.length);
    }
    /** Per vertex: 1 accessory, 2 Marisa's braid. On a swollen body they ride rigidly instead of stretching (lazy). */
    private byte[] rigidGroups;
    private int hatFollowBone=-2,braidBone=-2;
    private final Vector3f crownLift=new Vector3f(),braidLift=new Vector3f();
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
    /** The selected model's actual mamu, in its authored fully extended pose. No shared pose is mutated. */
    float[] deliveryMamuVertices(boolean markOnly){
        Clip extend=clips.get("anatomy_mamu_extend");
        if(extend==null)return new float[0];
        float[][] sampled=new float[bones.length][9];
        for(var values:sampled)values[6]=values[7]=values[8]=1;
        // Only anatomy tracks belong here; body/idle motion is supplied by Delivery.follow().
        for(var track:extend.tracks)if(bones[track.bone].name.startsWith("anatomy_"))
            track.sample(extend.duration,sampled[track.bone]);
        Matrix4f[] transforms=new Matrix4f[bones.length];
        for(int i=0;i<bones.length;i++){
            Bone bone=bones[i];float[] v=sampled[i];
            transforms[i]=bone.parent<0?new Matrix4f():new Matrix4f(transforms[bone.parent]);
            transforms[i].translate(bone.x+v[0],bone.y+v[1],bone.z+v[2])
                    // Birth opening is elastic tissue; the inspection clip's hinge rotation
                    // must not turn the entire outlet into a downward-opening flap.
                    .rotateZYX(0,0,0)
                    .scale(v[6],v[7],v[8]).translate(-bone.x,-bone.y,-bone.z);
        }
        var output=new ArrayList<Float>();
        for(var part:parts){
            if(!part.anatomy.equals("mamu")||(markOnly&&!part.name.contains("mamu_mark")))continue;
            for(int vertex=part.start;vertex<part.start+part.count;vertex++){
                int offset=vertex*8;Binding binding=bindings[vertex];
                var point=new Vector3f(restVertices[offset],restVertices[offset+1],restVertices[offset+2]);
                var other=new Vector3f(point);
                transforms[binding.first].transformPosition(point);
                if(binding.weight>0)point.lerp(transforms[binding.second].transformPosition(other),binding.weight);
                output.add(point.x);output.add(point.y);output.add(point.z);
                for(int k=3;k<8;k++)output.add(restVertices[offset+k]);
            }
        }
        float[] result=new float[output.size()];for(int i=0;i<result.length;i++)result[i]=output.get(i);return result;
    }
    private int[] gripBones;
    private Vector3f[] gripPoints;
    private boolean gripping,carrying,mouthHolding;
    private final Matrix4f mouthAttachment=new Matrix4f();
    /**
     * Small presentation-only addition to a contact-safe care clip.  The server has already
     * stopped the two bodies at its real mouth envelope; this uses their synchronized mouth
     * anchors to close the last visible gap without translating a parent below the ground.
     */
    private float careTargetMouthX,careTargetMouthY=Float.NaN,careTargetMouthZ,careBlend;
    private final Vector3f rescueTip=new Vector3f(),rescueJoint=new Vector3f();
    private boolean rescueContact,rescueJointActive,rescueGrip;
    /** Incidental rim touch blends softly; a server-validated rescue contact uses the whole finite braid. */
    private static final float LIGHT_RIM_TOUCH=.5f;
    private boolean braidCarryContact;
    void setBraidCarryContact(boolean active){braidCarryContact=active;}
    private float boardingPitch=Float.NaN,boardingRoll,boardingSquash=1,boardingYaw;
    void setBraidRescueContact(Vector3f point){rescueContact=point!=null;if(point!=null)rescueTip.set(point);}
    void setBraidRescueGrip(boolean active){rescueGrip=active;}
    void setBraidRescueJoint(Vector3f point){rescueJointActive=point!=null;if(point!=null)rescueJoint.set(point);}
    /** Server-owned body angle and squash (1 = none); yawBlend lets the clip's own yaw wriggle show (0..1). */
    void setBoardingPose(float pitch,float roll,float squash,float yawBlend){
        boardingPitch=pitch;boardingRoll=roll;boardingSquash=Float.isFinite(squash)?Math.clamp(squash,.7f,1.3f):1;boardingYaw=Math.clamp(yawBlend,0,1);
    }
    private String hatMotion="";
    private float hatPhase;
    record HatTransfer(float removeTicks,float returnTicks,float rollDegrees,float clearance,float slideBegin,float lowerBegin){}
    private HatTransfer selfHatTransfer;
    private Matrix4f selfHatPose;
    HatTransfer selfHatTransfer(){return selfHatTransfer;}
    void setSelfHatPose(Matrix4f matrix){selfHatPose=matrix==null?null:new Matrix4f(matrix);}
    public void setCarrying(boolean value){carrying=value;}
    public void setMouthHolding(boolean value){mouthHolding=value;}
    void setCareContact(float targetMouthX,float targetMouthY,float targetMouthZ,float progress){
        careTargetMouthX=targetMouthX;careTargetMouthY=targetMouthY;careTargetMouthZ=targetMouthZ;
        careBlend=Math.clamp(progress,0,1);
    }
    private final Map<String, Clip> clips = new HashMap<>();
    private record ExpressionSpan(float start, float end, String expression) {}
    private final Map<String, List<ExpressionSpan>> actionExpressions = new HashMap<>();
    /** Server faces that win over a clip's timeline, such as the injury cry. */
    private final Map<String, java.util.Set<String>> expressionYields = new HashMap<>();
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
        if (clip == null || expressionYields.getOrDefault(action, java.util.Set.of()).contains(fallback)) return fallback;
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
    private final Matrix4f featureParentInverse = new Matrix4f();
    private final float[] triangleUv = new float[6];

    private record Bone(String name, int parent, float x, float y, float z) {}
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
    /** True when the model or its motion corrections ship this clip; otherwise rendering falls back to idle. */
    public boolean hasClip(String name) { return clips.containsKey(name); }
    private int rootBone = -1;
    private int rootBone() {
        if (rootBone < 0) { rootBone = 0; for (int i = 0; i < bones.length; i++) if (bones[i].name().equals("root")) { rootBone = i; break; } }
        return rootBone;
    }
    /** Root bone transform of the last posed render, in model units (16 per block). Attachments drawn
     *  outside the skinned mesh (held food, stem, delivery) follow it so whole-body motion does not detach them. */
    public Matrix4f rootPose() { return new Matrix4f(world[rootBone()]); }
    /** Captured from the rendered lip surface before its expression patch is restored. */
    public Matrix4f mouthAttachmentPose() { return new Matrix4f(mouthAttachment); }
    private void captureMouthAttachment() {
        Vector3f low=new Vector3f(Float.POSITIVE_INFINITY),high=new Vector3f(Float.NEGATIVE_INFINITY),point=new Vector3f();
        int mouthBone=rootBone();boolean found=false;
        for(int i=0;i<bones.length;i++)if(bones[i].name().equals(LivingPresentation.mouthBone)){mouthBone=i;break;}
        var rotation=new org.joml.Quaternionf().setFromUnnormalized(world[mouthBone]);
        var inverse=new org.joml.Quaternionf(rotation).conjugate();
        for(var part:parts)if(part.name.equals("face_mouth"))
            for(int i=part.start;i<part.start+part.count;i++){skin(i,point);inverse.transform(point);low.min(point);high.max(point);found=true;}
        Vector3f center=mouthPoint().div(16);
        if(found){
            // Keep the bite on the outer lip plane, not halfway inside the curved cheeks.
            float lip=low.z;center.set(low).add(high).mul(.5f);center.z=lip;
            rotation.transform(center);center.div(16);
        }
        // A rigid object turns with the mouth, but lip closing must not squash that object.
        mouthAttachment.translationRotate(center,rotation);
    }
    /** The actual braid-tip bone in the current pose, in block units, for a real rowing stick. */
    public Matrix4f paddlePose() {
        for (int i=0; i<bones.length; i++) if (bones[i].name().equals("hand_right_tip")) {
            var matrix = new Matrix4f(world[i]).translate(bones[i].x(), bones[i].y(), bones[i].z());
            matrix.m30(matrix.m30()/16); matrix.m31(matrix.m31()/16); matrix.m32(matrix.m32()/16);
            return matrix;
        }
        return null;
    }
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
    /** Skinned vertices (x, y, z in blocks) of the body surface in the pose of the last render, for attachments that are derived from the body itself (CareWearRenderer). */
    float[] posedBody() {
        int count = 0;
        for (var part : parts) if (bodySurface(part.name)) count += part.count;
        float[] out = new float[count * 3];
        var point = new Vector3f(); int k = 0;
        for (var part : parts) if (bodySurface(part.name))
            for (int i = part.start; i < part.start + part.count; i++) { skin(i, point); out[k++] = point.x / 16; out[k++] = point.y / 16; out[k++] = point.z / 16; }
        return out;
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
        var mount=detachedBadges.get(name);if(mount==null||tier<1||tier>3)return;
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
        var buffer=buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(MCYukkuri.id("textures/item/"+(tier>=3?"gold":tier==2?"silver":"bronze")+"_yukkuri_badge.png")));
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
        JsonObject houseEcology;
        try (var reader = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/house_ecology.json")).openAsReader()) {
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            if(data.get("format").getAsInt()!=1)throw new IllegalArgumentException("Unsupported house ecology motions");
            houseEcology=data.getAsJsonObject("models");
        } catch(IOException error) { throw new UncheckedIOException("Cannot load house ecology motions",error); }
        JsonObject toiletContact = ToiletPresentation.reload(manager);
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
                JsonObject houseRevision=houseEcology.getAsJsonObject(id);
                if(houseRevision!=null)applyRevision(id,data,houseRevision);
                JsonObject toiletRevision=toiletContact.getAsJsonObject(id);
                if(toiletRevision!=null)applyRevision(id,data,toiletRevision);
                // Hash still identifies unchanged base geometry for wounds and badges.
                var model=new YukkuriModel(data,hash);
                try{model.hatSampler=HatDamageFaces.load(manager,MCYukkuri.id("textures/entity/"+id+"/neutral.png"));}
                catch(Exception ignored){model.hatSampler=null;}
                loaded.put(id, model);
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
        // Parsing below only reads clip trees; only the top-level behavior overrides need a copy.
        JsonObject allClips=new JsonObject();
        for(var entry:data.getAsJsonObject("clips").entrySet())allClips.add(entry.getKey(),entry.getValue());
        if(data.has("behavior_clips"))for(var entry:data.getAsJsonObject("behavior_clips").entrySet())allClips.add(entry.getKey(),entry.getValue());
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
            if(entry.getKey().equals("water_hat_flip")&&clip.has("hat_transfer")){
                var transfer=clip.getAsJsonObject("hat_transfer");
                selfHatTransfer=new HatTransfer(transfer.get("remove_ticks").getAsFloat(),transfer.get("return_ticks").getAsFloat(),transfer.get("deploy_roll_degrees").getAsFloat(),transfer.get("clearance_model_units").getAsFloat(),transfer.get("slide_begin").getAsFloat(),transfer.get("lower_begin").getAsFloat());
            }
            if (clip.has("puff_envelope")) puffEnvelopes.put(entry.getKey(),floats(clip.getAsJsonArray("puff_envelope")));
            if (clip.has("expression_spans")) {
                List<ExpressionSpan> spans = new ArrayList<>();
                for (var raw : clip.getAsJsonArray("expression_spans")) {
                    JsonArray span = raw.getAsJsonArray();
                    spans.add(new ExpressionSpan(span.get(0).getAsFloat(),span.get(1).getAsFloat(),span.get(2).getAsString()));
                }
                actionExpressions.put(entry.getKey(),List.copyOf(spans));
            }
            if (clip.has("expression_yield")) {
                var yields = new java.util.HashSet<String>();
                for (var raw : clip.getAsJsonArray("expression_yield")) yields.add(raw.getAsString());
                expressionYields.put(entry.getKey(), java.util.Set.copyOf(yields));
            }
        }
        if (!clips.containsKey("idle")) throw new IllegalArgumentException("Missing idle defaults");
        houseNormals=id.startsWith("house_")?new HouseNormals(restVertices,parts):null;
        if(puffSurface!=null)puffNormals=new HouseNormals(restVertices,List.of(puffSurface),true);
        boolean[] expressionVertices=new boolean[bindings.length];
        for(var patches:expressionPatches.values())for(var patch:patches)
            java.util.Arrays.fill(expressionVertices,patch.start/8,(patch.start+patch.values.length)/8,true);
        skinningCache=new SkinningCache(bindings.length,index->{
            if(expressionVertices[index])return null;
            int offset=index*8;Binding binding=bindings[index];
            return new SkinningCache.Key(restVertices[offset],restVertices[offset+1],restVertices[offset+2],
                    binding.first,binding.second,binding.weight,featureBones[index],
                    puffOffsets==null?0:puffOffsets[index*3],puffOffsets==null?0:puffOffsets[index*3+1],
                    puffOffsets==null?0:puffOffsets[index*3+2]);
        });
        boolean[] bodyVertices=new boolean[bindings.length];
        for(var part:parts)if(bodySurface(part.name))
            java.util.Arrays.fill(bodyVertices,part.start,part.start+part.count,true);
        bodyFloorVertices=skinningCache.uniqueIndices(index->bodyVertices[index]);
        for(var part:parts)cacheHairVisibility(part,expressionVertices);
        for(var part:puffAttachments.values())cacheHairVisibility(part,expressionVertices);
        if(puffSurface!=null)cacheHairVisibility(puffSurface,expressionVertices);
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
        render(action, elapsed, stack, buffer, light, overlay, true, Behaviors.IDLE, 0);
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
            if(behavior.equals(Behaviors.HOUSE_PICKUP)||behavior.equals(Behaviors.HOUSE_DEPOSIT)||behavior.equals(Behaviors.HOUSE_CARRY))selected=behavior;
            else if(behavior.equals(Behaviors.HOUSE_CRAWL))selected="house_crawl";
        }
        // House work has an authored, synchronized pause before the next crawl.
        // A moving body keeps its locomotion; these clips never move the real entity.
        if(gripBones!=null&&!action.equals("waddle")&&behavior.startsWith(Behaviors.HOUSE_PREFIX)&&clips.containsKey(behavior))selected=behavior;
        if(gripBones!=null&&(behavior.equals(Behaviors.HOUSE_SLEEP)||behavior.equals(Behaviors.HOUSE_HIDE))&&action.equals("idle"))selected="sleep";
        // Ecology state is more specific than the shared base action: care must never collapse
        // into ordinary nuzzling, and early learning keeps its low crawl/short-hop rig.
        if (behavior.equals(Behaviors.FEED_CHILD) || behavior.equals(Behaviors.FIRST_TOILET_ASSIST)
                || behavior.equals(Behaviors.NEWBORN_CRAWL) || behavior.equals(Behaviors.LEARN_JUMP)
                || ToiletPresentation.authored(behavior)) selected=behavior;
        if(behavior.equals(Behaviors.HAT_WALK)||behavior.equals(Behaviors.LEARN_WATCH)||behavior.equals(Behaviors.CARE_FINISH)||behavior.equals(Behaviors.BOAT_CARRY_YOUNG)||behavior.equals(Behaviors.BOAT_BE_CARRIED)||behavior.equals(Behaviors.BOAT_PREPARE_CARRY)||behavior.equals(Behaviors.BOAT_RELEASE_CARRY)
                ||behavior.equals(Behaviors.NEST_HARVEST)||behavior.equals(Behaviors.NEST_PLACE)||behavior.equals(Behaviors.NEST_SPREAD)||behavior.equals(Behaviors.NEST_PRESS))selected=behavior;
        if(carrying&&gripBones!=null&&(selected.equals("idle")||selected.equals("waddle")))selected="house_carry";
        gripping=carrying||selected.equals("house_pickup")||selected.equals("house_deposit")||selected.equals("house_carry");
        // The exported adult-state cue owns the complete body pose. Do not add a second
        // nervous/look-around/droop root animation beneath it and invalidate grounding.
        // The act's held anatomy clip is the exception: it drives only the peni bones and must
        // keep sampling its own time, or the part stays retracted inside the body.
        boolean anatomyAction = action.startsWith("anatomy_");
        if (adultStatePose && !anatomyAction) selected="idle";
        float adultTime = adultStatePose ? (anatomyAction ? elapsed : 0)
                : selected.equals(action) ? elapsed : behaviorTicks/20f;
        clips.getOrDefault(selected, clips.get("idle")).sample(adultTime, pose);
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
        if (behavior.equals(Behaviors.CHEW) || behavior.equals(Behaviors.FAMILY_CHEW) || behavior.equals(Behaviors.SING)) {
            float wave = (float)(.5 + .5 * Math.sin(behaviorTicks * Math.PI * 2 / LivingPresentation.chewPeriod));
            for (int i=0; i<bones.length; i++) if (bones[i].name.equals(LivingPresentation.mouthBone))
                pose[i][7] *= LivingPresentation.mouthMinimum + (LivingPresentation.mouthMaximum - LivingPresentation.mouthMinimum) * wave;
        }
        else if(mouthHolding){
            for(int i=0;i<bones.length;i++)if(bones[i].name.equals(LivingPresentation.mouthBone))pose[i][7]*=.45f;
        }
        var hat=io.github.longshou.mcyukkuri.family.HatCarry.shape(id);
        if(hat!=null&&!hatMotion.isEmpty()){
            float q=Math.clamp(hatPhase,0,1);if(hatMotion.equals("HAT_RETURN"))q=1-q;
            float rise=Math.min(1,q/.55f);rise=rise*rise*(3-2*rise);
            float forward=Math.max(0,(q-.4f)/.6f);forward=forward*forward*(3-2*forward);
            var root=pose[hat.get("bone").getAsInt()];root[1]+=hat.get("lift").getAsFloat()*rise;
            root[2]+=hat.get("forward").getAsFloat()*forward;
        }
        boolean careContact=Float.isFinite(careTargetMouthY)&&(behavior.equals(Behaviors.FEED_CHILD)||behavior.equals(Behaviors.BOAT_FEED_YOUNG)||behavior.equals(Behaviors.FIRST_TOILET_ASSIST));
        if(Float.isFinite(boardingPitch)){
            // The server owns position, pitch, roll and squash; only a small authored yaw wriggle may show.
            var root=pose[rootBone()];
            root[4]=Math.clamp(root[4],-8,8)*boardingYaw;
            root[0]=root[1]=root[2]=0;root[3]=Math.clamp(boardingPitch,-12,12);root[5]=Math.clamp(boardingRoll,-12,12);
            float wide=1+(1-boardingSquash)*.5f;
            root[6]=wide;root[7]=boardingSquash;root[8]=wide;
        }
        composeWorld();
        if(careContact){
            // Source and contact tracking share one total inclination budget. Adding a separate
            // 50-degree reach on top of the authored feeding lean made the parent tip forward.
            // A tiny child is expressed through proximity, face changes and speech, not a dive.
            float floor=bodyMinimumY();float[] root=pose[rootBone()];
            boolean feeding=!behavior.equals(Behaviors.FIRST_TOILET_ASSIST);
            float maximumPitch=feeding?10:12;
            root[3]=Math.max(-maximumPitch,root[3]);composeWorld();
            float basePitch=root[3],baseMouth=mouthY();
            float target=baseMouth+(careTargetMouthY+(feeding?0:.035f*16)-baseMouth)*careBlend;
            float low=0,high=Math.max(0,maximumPitch+basePitch)*careBlend;
            if(baseMouth>target)for(int i=0;i<7;i++){
                float pitch=(low+high)*.5f;root[3]=basePitch-pitch;composeWorld();
                float lifted=Math.max(0,floor-bodyMinimumY());
                if(mouthY()+lifted>target)low=pitch;else high=pitch;
            }
            root[3]=basePitch-(baseMouth>target?high:0);composeWorld();
            // Pitch changes the mouth's depth. Align the composed anchor, rather than estimating
            // reach from the upright face mesh; keep the extra lean small and tied to real contact.
            var mouth=mouthPoint();
            float lateral=feeding?.02f:.04f,forward=feeding?.03f:.18f;
            root[0]+=Math.clamp((careTargetMouthX-mouth.x)*careBlend,-lateral*16,lateral*16);
            root[2]+=Math.clamp((careTargetMouthZ+(feeding?0:.07f*16)-mouth.z)*careBlend,-forward*16,forward*16);
            composeWorld();float lowered=bodyMinimumY();
            if(lowered<floor)raiseWorld(floor-lowered);
        }
        if(rescueContact)poseRescueBraid();
        if(pregnancyBelly>.001f)rigidLifts();
        if (delivery != null) delivery.follow(world[rootBone()]);
        featureTransforms.forEach((bone,matrix)->{matrix.set(world[bone]);if(bones[bone].parent>=0)matrix.mul(featureParentInverse.set(world[bones[bone].parent]).invert());});
        // Contact solvers above change the bones repeatedly. Cache only after the final pose.
        skinningCache.begin();
        try {
            renderPosed(action,stack,buffer,light,overlay,accessory,hairLoss);
        } finally {
            skinningCache.end();
        }
    }

    private void renderPosed(String action,PoseStack stack,VertexConsumer buffer,int light,int overlay,
                             boolean accessory,int hairLoss) {
        PoseStack.Pose transform=stack.last();
        if(accessory&&hatFit<.999f)poseHatSeat();
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
        var pregnantSurface=pregnancyBelly>.001f?new PregnancyFaces.Surface():null;
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
            if (!accessory && accessoryPart(part)) continue;
            boolean hatPart=(hatDamage>0||hatFit<.999f)&&accessoryPart(part);
            // Delivery draws and opens the same authored mamu itself; do not draw it twice here.
            // Explicit visibility also covers interruption, idle and resource reload.
            if (!part.anatomy.isEmpty() && (delivery!=null || !action.startsWith("anatomy_" + part.anatomy + "_"))) continue;
            if (hatPart && hatDamage > 0) {
                var source=parts.get(partIndex);
                if(source.start==part.start&&source.count==part.count){
                    renderHatFaces(buffer,transform,part,hatFaces(partIndex),light,overlay);
                    continue;
                }
            }
            for (int first = part.start; first < part.start + part.count; first += 3) {
                // House expression cards overlap on the curved face. Their opaque
                // backs can hide neighbouring eyes with the no-cull entity material.
                // Keep the authored front ink; the head already supplies the skin.
                if(id.startsWith("house_")&&part.name.startsWith("face_")&&vertices[first*8+7]>=-.15f)continue;
                if(pregnancyBelly>.001f&&part.name.startsWith("face_")){
                    // The thin sprite boxes have opaque skin-coloured backs. On a curved face those backs
                    // receive inverted lighting through transparent pixels; the underlying head supplies skin.
                    if(vertices[first*8+7]>=-.15f)continue;
                    float[] uv=triangleUv;
                    for(int corner=0;corner<3;corner++){skinBase(first+corner,triangle[corner]);uv[corner*2]=vertices[(first+corner)*8+3];uv[corner*2+1]=vertices[(first+corner)*8+4];}
                    PregnancyFaces.render(triangle,uv,pregnancyBelly,pregnancyContraction,delivery,stack,buffer,light,overlay,pregnantSurface);continue;
                }
                for (int corner = 0; corner < 3; corner++) skin(first + corner, triangle[corner]);
                if(hatPart&&hatFit<.999f)for(int corner=0;corner<3;corner++)triangle[corner].sub(hatSeat).mul(hatFit).add(hatSeat);
                normal.set(triangle[1]).sub(triangle[0]);
                edge.set(triangle[2]).sub(triangle[0]);
                normal.cross(edge);
                if (normal.lengthSquared() < 1.0e-16f) continue;
                normal.normalize();
                if(delivery!=null&&bodySurface(part.name)&&normal.z<-.15f){
                    float[] uv=triangleUv;for(int corner=0;corner<3;corner++){uv[corner*2]=vertices[(first+corner)*8+3];uv[corner*2+1]=vertices[(first+corner)*8+4];}
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

    /** Draw the square face cells and close-colour inner caps of one accessory part. */
    private void renderHatFaces(VertexConsumer buffer,PoseStack.Pose transform,Part part,HatDamageFaces.Face[] faces,int light,int overlay){
        VertexConsumer cap=hatCapBuffer!=null?hatCapBuffer:buffer;
        for(var face:faces){
            if(face.original()){
                for(int first=face.originalStart();first<face.originalStart()+face.originalCount();first+=3){
                    for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);
                    if(hatFit<.999f)for(int corner=0;corner<3;corner++)triangle[corner].sub(hatSeat).mul(hatFit).add(hatSeat);
                    normal.set(triangle[1]).sub(triangle[0]);edge.set(triangle[2]).sub(triangle[0]);normal.cross(edge);
                    if(normal.lengthSquared()<1.0e-16f)continue;
                    normal.normalize();
                    for(int corner=0;corner<4;corner++){
                        int index=Math.min(corner,2),uv=(first+index)*8+3;
                        buffer.addVertex(transform.pose(),triangle[index].x/16,triangle[index].y/16,triangle[index].z/16)
                                .setColor(255,255,255,255).setUv(vertices[uv],vertices[uv+1])
                                .setOverlay(overlay).setLight(light).setNormal(transform,normal.x,normal.y,normal.z);
                    }
                }
                continue;
            }
            int[] corners=face.corners();
            for(int k=0;k<4;k++){
                skin(corners[k],faceCorners[k]);
                if(hatFit<.999f)faceCorners[k].sub(hatSeat).mul(hatFit).add(hatSeat);
                faceUv[k*2]=vertices[corners[k]*8+3];faceUv[k*2+1]=vertices[corners[k]*8+4];
            }
            int n=face.n(),m=face.m();
            for(int i=0;i<n;i++)for(int j=0;j<m;j++){
                int cell=i*m+j;byte code=face.codes()[cell];
                float u0=i/(float)n,u1=(i+1f)/n,v0=j/(float)m,v1=(j+1f)/m;
                bilerp(cellCorners[0],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u0,v0);
                bilerp(cellCorners[1],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u1,v0);
                bilerp(cellCorners[2],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u1,v1);
                bilerp(cellCorners[3],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u0,v1);
                cellUv[0]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u0,v0);
                cellUv[1]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u0,v0);
                cellUv[2]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u1,v0);
                cellUv[3]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u1,v0);
                cellUv[4]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u1,v1);
                cellUv[5]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u1,v1);
                cellUv[6]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u0,v1);
                cellUv[7]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u0,v1);
                faceNormal.set(cellCorners[1]).sub(cellCorners[0]);
                cellTemp.set(cellCorners[3]).sub(cellCorners[0]);
                faceNormal.cross(cellTemp);
                if(faceNormal.lengthSquared()<1.0e-16f)continue;
                faceNormal.normalize();
                if(face.flip())faceNormal.negate();
                if(code==HatDamageMask.HIDDEN){
                    for(int k=0;k<4;k++)cellCorners[k].sub(faceNormal.x*.16f,faceNormal.y*.16f,faceNormal.z*.16f);
                    emitCell(cap,transform,cellCorners,WHITE_UV,face.cap()[cell],light,overlay,faceNormal);
                }else if(code==HatDamageMask.EDGE){
                    emitCell(cap,transform,cellCorners,WHITE_UV,face.rim()[cell],light,overlay,faceNormal);
                }else{
                    emitCell(buffer,transform,cellCorners,cellUv,0xFFFFFF,light,overlay,faceNormal);
                }
            }
        }
    }
    private static final float[] WHITE_UV={.5f,.5f,.5f,.5f,.5f,.5f,.5f,.5f};
    private static float bilerp(float a,float b,float c,float d,float u,float v){
        return a+(b-a)*u+(d-a)*v+(a-b+c-d)*u*v;
    }
    private static void bilerp(Vector3f out,Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u,float v){
        out.set(bilerp(a.x,b.x,c.x,d.x,u,v),bilerp(a.y,b.y,c.y,d.y,u,v),bilerp(a.z,b.z,c.z,d.z,u,v));
    }
    private void emitCell(VertexConsumer buffer,PoseStack.Pose transform,Vector3f[] points,float[] uv,int color,int light,int overlay,Vector3f normal){
        emitTriangle(buffer,transform,points[0],points[1],points[2],uv,0,1,2,color,light,overlay,normal);
        emitTriangle(buffer,transform,points[0],points[2],points[3],uv,0,2,3,color,light,overlay,normal);
    }
    private void emitTriangle(VertexConsumer buffer,PoseStack.Pose transform,Vector3f a,Vector3f b,Vector3f c,float[] uv,
                              int ia,int ib,int ic,int color,int light,int overlay,Vector3f normal){
        int r=color>>16&255,g=color>>8&255,bl=color&255;
        Vector3f[] points={a,b,c};
        int[] indices={ia,ib,ic};
        for(int corner=0;corner<4;corner++){
            int k=Math.min(corner,2);Vector3f point=points[k];int u=indices[k]*2;
            buffer.addVertex(transform.pose(),point.x/16,point.y/16,point.z/16).setColor(r,g,bl,255)
                    .setUv(uv[u],uv[u+1]).setOverlay(overlay).setLight(light)
                    .setNormal(transform,normal.x,normal.y,normal.z);
        }
    }

    /** Bend the two existing braid sections about their real pivots; no stretching or detached tip. */
    private void poseRescueBraid(){
        int joint=-1,tip=-1;
        for(int i=0;i<bones.length;i++){
            if(bones[i].name.equals("hand_right"))joint=i;
            if(bones[i].name.equals("hand_right_tip"))tip=i;
        }
        if(joint<0||tip<0||bones[joint].parent<0)return;
        var base=bones[joint];var end=bones[tip];
        Matrix4f parent=world[base.parent];
        var desired=new Matrix4f(parent).invert().transformPosition(new Vector3f(rescueJointActive?rescueJoint:rescueTip)).sub(base.x,base.y,base.z);
        var authored=io.github.longshou.mcyukkuri.family.BraidRescueGeometry.modelTip();
        var rest=rescueJointActive?new Vector3f(end.x-base.x,end.y-base.y,end.z-base.z)
                :new Vector3f((float)authored.x-base.x,(float)authored.y-base.y,(float)authored.z-base.z);
        if(desired.lengthSquared()<1e-10f||rest.lengthSquared()<1e-10f)return;
        var rotation=new org.joml.Quaternionf().rotationTo(rest.normalize(),desired.normalize());
        // A real rescue target must reach its contact; incidental touch still blends with the paddle pose.
        var held=new org.joml.Quaternionf().rotateZYX(pose[joint][5]*DEGREES,pose[joint][4]*DEGREES,pose[joint][3]*DEGREES);
        rotation=held.slerp(rotation,(rescueGrip||braidCarryContact)?1f:LIGHT_RIM_TOUCH);
        world[joint].set(parent).translate(base.x,base.y,base.z).rotate(rotation).translate(-base.x,-base.y,-base.z);
        for(int i=joint+1;i<bones.length;i++){
            boolean follows=false;for(int at=bones[i].parent;at>=0;at=bones[at].parent)if(at==joint){follows=true;break;}
            if(!follows)continue;
            Bone bone=bones[i];float[] v=pose[i];
            if(i==tip){
                Matrix4f upper=world[bone.parent];
                if(rescueJointActive){
                    var distal=new Vector3f((float)authored.x-end.x,(float)authored.y-end.y,(float)authored.z-end.z);
                    var toward=new Matrix4f(upper).invert().transformPosition(new Vector3f(rescueTip)).sub(end.x,end.y,end.z);
                    if(distal.lengthSquared()>1e-10f&&toward.lengthSquared()>1e-10f){
                        var bend=new org.joml.Quaternionf().slerp(new org.joml.Quaternionf().rotationTo(distal.normalize(),toward.normalize()),(rescueGrip||braidCarryContact)?1f:LIGHT_RIM_TOUCH);
                        world[i].set(upper).translate(end.x,end.y,end.z).rotate(bend).translate(-end.x,-end.y,-end.z);
                        continue;
                    }
                }
                world[i].set(upper);continue;
            }
            if(bone.name.equals("hand_right_ribbon")){
                world[i].set(rescueJointActive?world[tip]:world[bone.parent]);continue;
            }
            world[i].set(world[bone.parent]).translate(bone.x+v[0],bone.y+v[1],bone.z+v[2])
                    .rotateZYX(v[5]*DEGREES,v[4]*DEGREES,v[3]*DEGREES)
                    .scale(v[6],v[7],v[8]).translate(-bone.x,-bone.y,-bone.z);
        }
        // The ribbon can be a sibling before the tip in the authored bone list.
        for(int i=joint+1;i<bones.length;i++)if(bones[i].name.equals("hand_right_ribbon"))world[i].set(world[tip]);
    }

    private void composeWorld(){
        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];float[] v = pose[i];Matrix4f matrix = world[i];
            if (bone.parent < 0) matrix.identity(); else matrix.set(world[bone.parent]);
            matrix.translate(bone.x + v[0], bone.y + v[1], bone.z + v[2])
                    .rotateZYX(v[5] * DEGREES, v[4] * DEGREES, v[3] * DEGREES)
                    .scale(v[6], v[7], v[8]).translate(-bone.x, -bone.y, -bone.z);
            if(hatScale!=1&&i==hatFollowBone()){
                var a=io.github.longshou.mcyukkuri.subspecies.HatVessel.anchor();matrix.translate(a).scale(hatScale).translate(a.negate());
            }
            if(i==hatFollowBone()&&selfHatPose!=null)matrix.set(selfHatPose);
        }
    }

    /** Minimum y of the true body skin after the current root pose, in model units. */
    private float bodyMinimumY(){
        float minimum=Float.MAX_VALUE;
        // Recompute each distinct body point for every candidate pose; no stale contact cache.
        for(int index:bodyFloorVertices){
            skinBase(index,triangle[0]);minimum=Math.min(minimum,triangle[0].y);
        }
        return minimum==Float.MAX_VALUE?0:minimum;
    }

    /** Current rendered mouth height in the same model units as the root and body floor. */
    private float mouthY(){
        return mouthPoint().y;
    }
    Vector3f mouthPoint(){
        var mouth=LivingPresentation.variants.get(id);if(mouth==null)return new Vector3f();
        var point=new Vector3f(mouth.x()*16,mouth.y()*16,mouth.z()*16);
        io.github.longshou.mcyukkuri.family.LiveBirthGeometry.deform(point,pregnancyBelly,0);
        world[rootBone()].transformPosition(point);return point;
    }

    /** Apply a world-space model-unit lift to every bone so child attachments follow the landed body. */
    private void raiseWorld(float lift){for(var matrix:world)matrix.m31(matrix.m31()+lift);}

    public boolean woundGeometryCompatible(){return sourceHash.matches(io.github.longshou.mcyukkuri.body.WoundSurface.get(id).runtimeSourceHash);}
    public void renderLiving(String action,float elapsed,PoseStack stack,VertexConsumer buffer,int light,int overlay,
            boolean accessory,String behavior,float behaviorTicks,int hairLoss,io.github.longshou.mcyukkuri.entity.YukkuriEntity entity,
            net.minecraft.client.renderer.MultiBufferSource buffers){
        drawingWoundedBody=WoundRenderer.active(entity);
        if(drawingWoundedBody&&!woundGeometryCompatible()){
            drawingWoundedBody=false;if(!warnedWoundMismatch){org.slf4j.LoggerFactory.getLogger(YukkuriModel.class).warn("Wound attachment disabled for incompatible resource-pack geometry: {}",id);warnedWoundMismatch=true;}
        }
        String expression=AgitationPresentation.expression(entity,expressionFor(ToiletPresentation.authored(behavior)?behavior:action,
                ToiletPresentation.authored(behavior)?behaviorTicks/20f:elapsed,entity.getExpressionId()));
        setExpression(expression);
        adultStatePose=AgitationPresentation.selected(entity);
        hatMotion=entity.contactPose().equals("HAT_REMOVE")||entity.contactPose().equals("HAT_RETURN")?entity.contactPose():"";
        hatPhase=entity.contactTurn();
        hatScale=io.github.longshou.mcyukkuri.subspecies.HatVessel.hatMultiplier(entity.subspeciesFlags());
        int headwear=entity.visibleHeadwear();
        hatDamage=io.github.longshou.mcyukkuri.headwear.Headwear.visualLevel(headwear);hatFit=io.github.longshou.mcyukkuri.headwear.Headwear.visualFit(headwear);
        // Pieces licked together sit on the head as a cracked hat before they knit, though the owner is not yet recognised.
        boolean pieces=hatDamage==3;
        hatCapBuffer=buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(HatDamageFaces.INNER_TEXTURE));
        try{render(action,elapsed,stack,buffer,light,overlay,accessory||pieces||hatMotion.equals("HAT_REMOVE"),behavior,behaviorTicks,hairLoss);
            if(!entity.getMouthItem().isEmpty()||!entity.getCarriedItem().isEmpty()||!entity.aquaticServing().isEmpty())captureMouthAttachment();
            if(drawingWoundedBody)WoundRenderer.render(entity,expression,world,stack,buffer,buffers,light,overlay);
        }finally{adultStatePose=false;drawingWoundedBody=false;mouthHolding=false;hatMotion="";hatScale=1;hatDamage=0;hatFit=1;hatCapBuffer=null;setCareContact(0,Float.NaN,0,0);setBraidRescueContact(null);setBraidRescueJoint(null);setBraidRescueGrip(false);setBraidCarryContact(false);setBoardingPose(Float.NaN,0,1,0);setSelfHatPose(null);setExpression("");}
    }
    public void renderRemains(Object key,net.minecraft.nbt.CompoundTag data,PoseStack stack,VertexConsumer buffer,
                              net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay){
        var body=data.getCompound("Body");
        var mesh=io.github.longshou.mcyukkuri.body.WoundSurface.get(id);
        var wounds=io.github.longshou.mcyukkuri.body.BodyWound.read(body).stream()
                .map(w->io.github.longshou.mcyukkuri.body.WoundMigration.restore(id,mesh,body,w))
                .filter(java.util.Objects::nonNull).filter(mesh::valid).toList();
        // The remains keep the body signs (mould, baked sole, bare or broken core) of the moment of death.
        var seed=data.hasUUID("Identity")?data.getUUID("Identity"):new java.util.UUID(0,0);
        // Decay shows as mould patches (stale: middle tier, rotten: terminal) over the discoloured, sagging body (RemainsRenderer squashes it).
        int rotStage=io.github.longshou.mcyukkuri.body.RemainsRot.stage(data);
        int packedSigns=io.github.longshou.mcyukkuri.body.BodySigns.pack(Math.max(body.getInt("Mold"),rotStage==2?8:rotStage==1?4:0),body.getBoolean("FootBurned"),body.getBoolean("CoreHurt"),false);
        var signs=WoundRenderer.signs(key,seed,id,packedSigns,data.getInt("Filling"),wounds);
        if(!signs.isEmpty())wounds=java.util.stream.Stream.concat(wounds.stream(),signs.stream()).toList();
        drawingWoundedBody=!wounds.isEmpty()&&woundGeometryCompatible();
        setCarrying(false);setPregnancy(0,0);setDelivery(null);setExpression("sleep");
        hatScale=io.github.longshou.mcyukkuri.subspecies.HatVessel.hatMultiplier(data.getInt("SubspeciesFlags"));
        boolean accessory=!data.contains("Accessory")||data.getBoolean("Accessory");
        try{
            render("idle",0,stack,buffer,light,overlay,accessory,Behaviors.IDLE,0,body.getInt("HairLoss"));
            if(drawingWoundedBody)WoundRenderer.render(key,id,"sleep",wounds,0,body.getInt("Bandage")>0,data.getInt("Filling"),id.startsWith("house_"),world,stack,buffer,buffers,light,overlay);
            if(accessory)renderBadge(data.getInt("Medal"),stack,buffers,light,overlay);
        }finally{drawingWoundedBody=false;hatScale=1;setExpression("");}
    }
    private void setExpression(String name){
        // A revision may carry its own named patch; otherwise borrow the alias's geometry.
        if(!expressionPatches.containsKey(name))name=geometryExpression(name);
        for(var patch:activePatches)System.arraycopy(restVertices,patch.start,vertices,patch.start,patch.values.length);
        activePatches=expressionPatches.getOrDefault(name,List.of());
        for(var patch:activePatches)System.arraycopy(patch.values,0,vertices,patch.start,patch.values.length);
    }

    private void cacheHairVisibility(Part part,boolean[] expressionVertices){
        // Resource-pack expression patches can move any part. Keep their original live scan.
        for(int index=part.start;index<part.start+part.count;index++)if(expressionVertices[index])return;
        int mask=(hairHidden(part,1)?1:0)|(hairHidden(part,2)?2:0)|(hairHidden(part,4)?4:0);
        staticHairMasks.put(part,mask);
    }
    private boolean accessoryPart(Part part){
        // This configuration reloads after model construction, so read the current prefixes.
        for(String prefix:LivingPresentation.variants.get(id).accessoryPrefixes())if(part.name.startsWith(prefix))return true;
        return false;
    }
    private boolean hairHidden(Part part,int loss){
        if(loss==0)return false;
        Integer mask=staticHairMasks.get(part);if(mask!=null)return (loss&mask)!=0;
        if(!(part.name.contains("hair")||part.name.contains("bang")||part.name.contains("braid")||part.name.startsWith("wasa_")))return false;
        if((loss&4)!=0)return true;
        if(part.name.contains("bang")||part.name.contains("crown")||part.name.contains("wrap")||part.name.startsWith("wasa_scalp"))return false;
        float x=0;for(int v=part.start;v<part.start+part.count;v++)x+=vertices[v*8];
        return x<0?(loss&1)!=0:(loss&2)!=0;
    }
    /** Ground the shared adult-state rotation and scale, including visible hair and accessories. */
    float presentationLift(float[] value, boolean accessory, int loss) {
        var transform = new Matrix4f().rotateZYX(value[2]*DEGREES, value[1]*DEGREES, value[0]*DEGREES)
                .scale(value[3], value[4], value[5]);
        var point = new Vector3f();
        float minimum = Float.MAX_VALUE;
        for (var part : parts) {
            if (!part.anatomy.isEmpty() || part.name.contains("puff") || hairHidden(part, loss)
                    || !accessory && accessoryPart(part)) continue;
            for (int v = part.start; v < part.start+part.count; v++) {
                point.set(restVertices[v*8], restVertices[v*8+1], restVertices[v*8+2]);
                minimum = Math.min(minimum, transform.transformPosition(point).y / 16);
            }
        }
        return minimum == Float.MAX_VALUE ? 0 : Math.max(0, .006f-minimum);
    }

    /** Place the lowest visible source vertex above the floor during rigid contact poses. */
    public float contactLift(float angle,boolean accessory,int loss){
        float minimum=Float.MAX_VALUE,c=(float)Math.cos(angle*DEGREES),s=(float)Math.sin(angle*DEGREES);
        for(var part:parts){
            if(!part.anatomy.isEmpty()||part.name.contains("puff")||hairHidden(part,loss))continue;
            if(!accessory&&accessoryPart(part))continue;
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
        if(pregnancyBelly<=0)return;
        int group=rigidGroups==null?0:rigidGroups[index];
        if(group==1)target.add(crownLift);else if(group==2)target.add(braidLift);
        else io.github.longshou.mcyukkuri.family.LiveBirthGeometry.deform(target,pregnancyBelly,pregnancyContraction);
    }
    private int hatFollowBone(){
        if(hatFollowBone==-2){hatFollowBone=-1;braidBone=-1;for(int i=0;i<bones.length;i++){if(bones[i].name().equals("hat_follow"))hatFollowBone=i;if(bones[i].name().equals("hand_right"))braidBone=i;}}
        return hatFollowBone;
    }
    /** The hat and the braid ride the swollen head rigidly, the crown's and the braid root's own lift, fading out as a hat
     *  clip lifts the hat away or a braid clip swings the braid off its rest (so they reach where the clip puts them). */
    private void rigidLifts(){
        int hat=hatFollowBone(),braid=braidBone;
        if(rigidGroups==null){
            rigidGroups=new byte[bindings.length];var prefixes=LivingPresentation.variants.get(id).accessoryPrefixes();
            for(var part:parts){
                byte group=prefixes.stream().anyMatch(part.name::startsWith)?(byte)1:part.name.startsWith("braid_lock")&&braid>=0
                        &&(bindings[part.start].first==braid||bones[bindings[part.start].first].parent==braid)?(byte)2:0;
                for(int i=part.start;i<part.start+part.count;i++)rigidGroups[i]=group;
            }
        }
        float s=1+io.github.longshou.mcyukkuri.family.LiveBirthGeometry.SWELL*pregnancyBelly;
        float hatWeight=1;
        if(hat>=0){var v=pose[hat];hatWeight=Math.max(0,1-(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])/3-Math.max(Math.abs(v[3]),Math.max(Math.abs(v[4]),Math.abs(v[5])))/30);}
        crownLift.set(0,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.modelHeight(id)*(s-1)*hatWeight,0);
        braidLift.zero();
        if(braid>=0){
            var v=pose[braid];float weight=Math.max(0,1-Math.max(Math.abs(v[3]),Math.max(Math.abs(v[4]),Math.abs(v[5])))/30);
            var root=new Vector3f(bones[braid].x(),bones[braid].y(),bones[braid].z());var moved=new Vector3f(root);
            io.github.longshou.mcyukkuri.family.LiveBirthGeometry.deform(moved,pregnancyBelly,0);braidLift.set(moved.sub(root).mul(weight));
        }
    }
    private void skinBase(int index, Vector3f target) {
        if(skinningCache.read(index,target))return;
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
        // Pregnancy and rigid accessory lifts happen in skin(), after this cached base position.
        skinningCache.write(index,target);
    }
}
