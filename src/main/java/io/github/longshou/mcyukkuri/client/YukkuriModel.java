package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.body.WoundMigration;
import io.github.longshou.mcyukkuri.body.WoundKind;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.HatCarry;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.family.PregnancyAttachments;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.status.Marks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/** Numeric authoring rig with triangle geometry and two-bone skinning. Client only. */
public final class YukkuriModel {
    public static final ResourceManagerReloadListener RELOAD = YukkuriModelLoader::reload;
    private static Map<String, YukkuriModel> models = Map.of();
    private static Map<String, JsonObject> detachedBadges = Map.of();
    /** Null preserves that table, retaining the original staged reload publication points. */
    static void install(Map<String,YukkuriModel> loaded,Map<String,JsonObject> badges) {
        if(loaded!=null)models=loaded;
        if(badges!=null)detachedBadges=badges;
    }
    void hatSampler(HatDamageFaces.Sampler sampler){hatDamageDraw.sampler(sampler);}
    boolean installBadgeMount(JsonObject mount){
        int first=mount.get("triangle").getAsInt();
        boolean belongs=parts.stream().anyMatch(p->p.name.equals(mount.get("part").getAsString())&&first>=p.start&&first+3<=p.start+p.count);
        if(sourceHash.matches(mount.get("model_sha256").getAsString())&&belongs){badgeMount=mount;return true;}
        return false;
    }
    static final float DEGREES = (float) (Math.PI / 180);
    private final float[] vertices;
    private final float[] restVertices;
    private final int[] featureBones;
    private final Map<Integer,Matrix4f> featureTransforms=new HashMap<>();
    private record ExpressionPatch(int start,float[] values) {}
    private final Map<String,List<ExpressionPatch>> expressionPatches=new HashMap<>();
    private List<ExpressionPatch> activePatches=List.of();
    private final String id;
    private final ModelFingerprint sourceHash;
    private boolean warnedWoundMismatch;
    private JsonObject badgeMount;
    private final BadgeDraw badgeDraw=new BadgeDraw(this);
    private final Binding[] bindings;
    private final Bone[] bones;
    private final List<Part> parts = new ArrayList<>();
    private final Map<Part,Integer> staticHairMasks = new IdentityHashMap<>();
    private final HouseNormals houseNormals;
    /** Angle-thresholded smooth skin normals of the body surface and of its puff mesh; null on house models. */
    private AngleNormals skinNormals,puffSkinNormals;
    private final SkinningCache skinningCache;
    private final int[] bodyFloorVertices;
    private final float[][] defaultPose, lastLocalPose;
    private final Matrix4f[] localMatrices;
    private final int[] quadFourth;
    private final byte[] quadRotation;
    private final Vector3f fourthCorner=new Vector3f();
    private final PregnancyFaces.Surface pregnancySurface=new PregnancyFaces.Surface();
    private int woundTriangle=-1;
    private boolean drawingWoundedBody, drawingSurfaceMarks;
    private HairWoundRenderer.Prepared woundDisplay;
    private final Vector3f[] woundHatCorners={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
    private boolean[] woundAccessoryVertices;
    private boolean adultStatePose;
    private float pregnancyBelly,pregnancyContraction;
    /** The aquatic Marisa's larger hat: the hat bone scaled about its seat on the head. */
    private float hatScale=1;
    private final HatDamageDraw hatDamageDraw=new HatDamageDraw(this);
    /** Per vertex: 1 accessory, 2 Marisa's braid. On a swollen body they ride rigidly instead of stretching (lazy). */
    private byte[] rigidGroups;
    private int hatFollowBone=-2,braidBone=-2;
    private final Vector3f crownLift=new Vector3f(),braidLift=new Vector3f();
    private LiveBirthRenderer.Delivery delivery;
    private String reproductiveAnatomy;
    private float reproductiveAmount;
    /** An independent visible anatomy layer; it never selects or advances a business action. */
    void setReproductiveAnatomy(String part,float amount){reproductiveAnatomy=part;reproductiveAmount=Math.clamp(amount,0,1);}
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
    private final BraidPose braidPose=new BraidPose(this);
    void setBraidCarryContact(boolean active){braidPose.setBraidCarryContact(active);}
    private float boardingPitch=Float.NaN,boardingRoll,boardingSquash=1,boardingYaw;
    void setBraidRescueContact(Vector3f point){braidPose.setBraidRescueContact(point);}
    void setBraidRescueGrip(boolean active){braidPose.setBraidRescueGrip(active);}
    void setBraidRescueJoint(Vector3f point){braidPose.setBraidRescueJoint(point);}
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
    /** Parent carry (ParentCarryPresentation): load damps the walk sway, lean bows the body (0..1), curls bend the two tails. */
    private float carryLoad,carryLean,carryCurlLeft,carryCurlRight;
    private CarryTailCurl tailCurl;private boolean tailCurlChecked;
    /** Walking under a load keeps this share less of the authored sway (project design). */
    private static final float CARRY_GAIT_DAMP=.3f;
    /** Full pick-up / put-down bow, degrees of forward pitch about the root (within the 12 degree body budget). */
    private static final float CARRY_LEAN_DEGREES=9;
    void setCarryPose(float load,float lean,float curlLeft,float curlRight){
        carryLoad=Math.clamp(load,0,1);carryLean=Math.clamp(lean,0,1);carryCurlLeft=Math.clamp(curlLeft,0,1);carryCurlRight=Math.clamp(curlRight,0,1);
    }
    /** The posed hat bone of the last render (model units), or null for a hatless form. */
    Matrix4f hatFollowPose(){int bone=hatFollowBone();return bone<0?null:new Matrix4f(world[bone]);}
    /** The seat on one curled tail in the last render (model units), or null. */
    Matrix4f carryCradle(int side){return tailCurl==null?null:tailCurl.cradle(side);}
    void setCareContact(float targetMouthX,float targetMouthY,float targetMouthZ,float progress){
        careTargetMouthX=targetMouthX;careTargetMouthY=targetMouthY;careTargetMouthZ=targetMouthZ;
        careBlend=Math.clamp(progress,0,1);
    }
    private final Map<String, Clip> clips = new HashMap<>();
    private record ExpressionSpan(float start, float end, String expression) {}
    private final Map<String, List<ExpressionSpan>> actionExpressions = new HashMap<>();
    /** Server faces that win over a clip's timeline, such as the injury cry. */
    private final Map<String, Set<String>> expressionYields = new HashMap<>();
    private final Map<String, float[]> puffEnvelopes = new HashMap<>();
    /** Clip-authored prop keys (clip props.<slot>): [t, px, py, pz, rx, ry, rz] in the bone frame at its pivot (aquatic_tools.py). */
    private record PropTrack(int bone, float[][] keys) {}
    private final Map<String, Map<String, PropTrack>> clipProps = new HashMap<>();
    /** Named clip seconds (clip markers), such as the paddle's power_start and power_end. */
    private final Map<String, Map<String, Float>> clipMarkers = new HashMap<>();
    /** One rest-space morph target: refined skin, blush carriers and swung side locks. */
    private float[] puffOffsets;
    private float puffAmount;
    private Part puffSurface;
    private int puffPartIndex=-1;
    private final Map<Integer,Part> puffAttachments=new HashMap<>();
    private final Map<String,String> expressionAliases=new HashMap<>();
    private HouseNormals puffNormals;
    /** One shared lighting normal per expression card, so its flat strips do not shade as separate pieces. */
    private final Map<String,Vector3f> cardNormals=new HashMap<>();
    /** The shared normal of an expression card part, or null when the part keeps per-triangle normals. */
    Vector3f woundCardNormal(String part){return cardNormals.get(part);}
    private final Vector3f puffBlendedNormal=new Vector3f();
    String geometryExpression(String expression) { return expressionAliases.getOrDefault(expression,expression); }
    /** Vertices any expression patch can move; the wound renderer reuses everything else across expressions. */
    private boolean[] woundExpressionVertices=new boolean[0];
    /** The patch list setExpression would apply: equal keys mean identical vertex data. */
    Object woundExpressionKey(String name){
        if(!expressionPatches.containsKey(name))name=geometryExpression(name);
        return expressionPatches.getOrDefault(name,List.of());
    }
    boolean woundExpressionVertex(int vertex){return woundExpressionVertices[vertex];}
    boolean woundExpressionPart(Part part){
        for(int v=part.start;v<part.start+part.count;v++)if(woundExpressionVertices[v])return true;
        return false;
    }

    private static float smooth(float x) { x=Math.clamp(x,0,1); return x*x*(3-2*x); }

    /** Inflate, hold (with an optional tremor that only shrinks the cheeks) and release. */
    static float puffEnvelope(float[] e,float time){
        float u=(time-e[0])/(e[1]-e[0]),base=smooth(u/e[2])*smooth((1-u)/e[2]);
        if(base<=0||e.length<5)return base;
        float hold=smooth((u-e[2])/e[2])*smooth((1-e[2]-u)/e[2]);
        return base*(1-e[3]*hold*(float)(.5-.5*Math.cos(2*Math.PI*e[4]*(time-e[0]))));
    }

    // Rendering is serial on the render thread; these buffers are not entity state.
    private final Matrix4f[] world;
    private final float[][] pose;
    private final Vector3f[] triangle = {new Vector3f(), new Vector3f(), new Vector3f()};
    private final Vector3f second = new Vector3f(), normal = new Vector3f(), edge = new Vector3f();
    private final Matrix4f featureParentInverse = new Matrix4f();
    private final float[] triangleUv = new float[6];

    record Bone(String name, int parent, float x, float y, float z) {}
    private record Binding(int first, int second, float weight) {}
    record Part(String name, int start, int count, String anatomy) {}
    /** Share light across a deforming skin plane, while preserving authored hard edges. */
    private static final class HouseNormals {
        final int[] groups;
        final List<Integer> triangles=new ArrayList<>();
        final Vector3f[] normals;
        HouseNormals(float[] vertices,List<Part> parts){this(vertices,parts,false);}
        HouseNormals(float[] vertices,List<Part> parts,boolean smooth){
            groups=new int[vertices.length/8];Arrays.fill(groups,-1);
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
    /**
     * Smooth the flat skin triangles across slice seams: a vertex takes the posed face normals of every triangle at its position
     * whose rest normal lies within {@link #SMOOTH_ANGLE_COS} of its own triangle's, so rounded seams (up to 44 degrees on the
     * shared body model) blend while real corners (50 degrees and more) stay sharp. Adjacency is built once; per frame only sums.
     */
    private static final class AngleNormals {
        final int[] firsts;
        final float[] faces;
        final int[] neighbourStart;
        final int[] neighbours;
        final int[] vertices;
        final Vector3f[] normals;
        AngleNormals(float[] rest,List<Part> parts){
            int total=rest.length/8,count=0;
            for(var part:parts)count+=part.count/3;
            firsts=new int[count];faces=new float[count*3];
            float[] rn=new float[count*3];
            int t=0;
            for(var part:parts)for(int first=part.start;first<part.start+part.count;first+=3,t++){
                firsts[t]=first;
                float ax=rest[(first+1)*8]-rest[first*8],ay=rest[(first+1)*8+1]-rest[first*8+1],az=rest[(first+1)*8+2]-rest[first*8+2];
                float bx=rest[(first+2)*8]-rest[first*8],by=rest[(first+2)*8+1]-rest[first*8+1],bz=rest[(first+2)*8+2]-rest[first*8+2];
                float x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx,length=(float)Math.sqrt(x*x+y*y+z*z);
                if(length>1e-9f){rn[t*3]=x/length;rn[t*3+1]=y/length;rn[t*3+2]=z/length;}
            }
            var byPosition=new HashMap<String,List<Integer>>();
            for(t=0;t<count;t++)for(int c=0;c<3;c++){
                int o=(firsts[t]+c)*8;
                byPosition.computeIfAbsent(Math.round(rest[o]*100000)+","+Math.round(rest[o+1]*100000)+","+Math.round(rest[o+2]*100000),k->new ArrayList<>()).add(t);
            }
            neighbourStart=new int[total+1];
            var list=new ArrayList<Integer>();var used=new ArrayList<Integer>();
            int[] owner=new int[total];Arrays.fill(owner,-1);
            for(t=0;t<count;t++)for(int c=0;c<3;c++)owner[firsts[t]+c]=t;
            for(int v=0;v<total;v++){
                neighbourStart[v]=list.size();
                int own=owner[v];if(own<0)continue;
                int o=v*8;
                for(int other:byPosition.get(Math.round(rest[o]*100000)+","+Math.round(rest[o+1]*100000)+","+Math.round(rest[o+2]*100000))){
                    float dot=rn[own*3]*rn[other*3]+rn[own*3+1]*rn[other*3+1]+rn[own*3+2]*rn[other*3+2];
                    if(other==own||dot>=SMOOTH_ANGLE_COS)list.add(other);
                }
                used.add(v);
            }
            neighbourStart[total]=list.size();
            neighbours=list.stream().mapToInt(Integer::intValue).toArray();
            vertices=used.stream().mapToInt(Integer::intValue).toArray();
            normals=new Vector3f[total];for(int v:vertices)normals[v]=new Vector3f();
        }
        boolean has(int vertex){return normals[vertex]!=null;}
    }
    /** cos(47 degrees): above the 44 degree rounded seams and below the 50 degree corners of the shared body model. */
    private static final float SMOOTH_ANGLE_COS=(float)Math.cos(Math.toRadians(47));
    /** Model units the expression cards sit in front of their own plane, over the head skin they were measured against (about 0.02 deep at the mouth). */
    static final float CARD_LIFT=.04f;
    private static final float REMAINS_LIFT=.015f;
    private void updateAngleNormals(AngleNormals smooth){
        for(int t=0;t<smooth.firsts.length;t++){
            int first=smooth.firsts[t];
            for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);
            normal.set(triangle[1]).sub(triangle[0]);edge.set(triangle[2]).sub(triangle[0]);normal.cross(edge);
            smooth.faces[t*3]=normal.x;smooth.faces[t*3+1]=normal.y;smooth.faces[t*3+2]=normal.z;
        }
        for(int v:smooth.vertices){
            var sum=smooth.normals[v].zero();
            for(int i=smooth.neighbourStart[v];i<smooth.neighbourStart[v+1];i++){int face=smooth.neighbours[i]*3;sum.add(smooth.faces[face],smooth.faces[face+1],smooth.faces[face+2]);}
            if(sum.lengthSquared()>1e-16f)sum.normalize();else sum.set(0,0,-1);
        }
    }
    private static boolean bodySurface(String name){return name.startsWith("soft_head_slice_")||name.equals("house_body_surface")||
            name.equals("predator_body_surface")||name.equals("deficient_body_surface")||name.equals("rounded_body_surface");}
    private static boolean wornBody(String name){return bodySurface(name)||name.endsWith("_rump_mark");}
    /**
     * Parts that lie over a swaddle (CareWearRenderer.clearHair): every hair, sidelock, braid, tail and accessory part. The body skin,
     * its rump and rear marks (covered by the wrap), the face cards, anatomy and puff meshes are not.
     */
    private static boolean overWear(Part part){String name=part.name;return !bodySurface(name)&&!name.startsWith("face_")&&part.anatomy.isEmpty()
            &&!name.contains("puff")&&!name.contains("rump")&&!name.contains("rear_mark");}
    private float[] wearBuffer = new float[0];
    private int wearBody;
    /** Wear bits (YukkuriView.visibleWear) of the body being drawn, set by the renderer until renderLiving ends. */
    private int wear;
    /** The posed skin of a worn wrap is prepared: overWear vertices are lifted over it as they are skinned. */
    private boolean wearClear;
    /** Per vertex: lies over a swaddle (overWear), lazy. */
    private boolean[] overWearVertices;
    public void setWear(int bits){wear=bits;}
    private boolean overWearVertex(int index){
        if(overWearVertices==null){
            var flags=new boolean[bindings.length];
            for(var part:parts)if(overWear(part))Arrays.fill(flags,part.start,part.start+part.count,true);
            // A puffed face swaps some hair parts for their puff copies under the same name.
            for(var part:puffAttachments.values())if(overWear(part))Arrays.fill(flags,part.start,part.start+part.count,true);
            overWearVertices=flags;
        }
        return index>=0&&index<overWearVertices.length&&overWearVertices[index];
    }
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
        var rotation=new Quaternionf().setFromUnnormalized(world[mouthBone]);
        var inverse=new Quaternionf(rotation).conjugate();
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

    public void renderSurfaceProp(String name,int units,float scale,PoseStack target,MultiBufferSource buffers,int light,int overlay) {
        locateSurface();
        if(woundTriangle<0)return;
        for(int i=0;i<3;i++)skin(woundTriangle+i,triangle[i]);
        var origin=new Vector3f(triangle[0]).add(triangle[1]).add(triangle[2]).div(48);
        var normal=new Vector3f(triangle[1]).sub(triangle[0]).cross(new Vector3f(triangle[2]).sub(triangle[0]));
        if(normal.lengthSquared()<1e-10)return;normal.normalize();if(normal.z>0)normal.negate();
        var right=new Vector3f(normal).cross(0,1,0);if(right.lengthSquared()<1e-10)return;right.normalize();
        var up=new Vector3f(right).cross(normal).normalize();
        var basis=new Matrix3f(right,normal,up);var rotation=new Quaternionf().setFromNormalized(basis);
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

    public Vector3f surfacePoint(){
        locateSurface();
        if(woundTriangle<0)return new Vector3f(0,.2f,-.3f);
        var point=new Vector3f();var vertex=new Vector3f();
        for(int i=0;i<3;i++){skin(woundTriangle+i,vertex);point.add(vertex);}return point.div(48);
    }
    /**
     * Skins the worn-shell surface of the last render pose into one reused buffer (x, y, z in blocks), for attachments derived from the
     * body itself (CareWearRenderer): the body surface with its rump mark (a raised patch that shells must close over); the first
     * {@link #wearBodyLength()} floats are valid. The hair is not part of it: it lies over the wrap. Render thread only.
     */
    float[] posedWear() {
        int count = 0;
        for (var part : parts) if (wornBody(part.name)) count += part.count;
        if (wearBuffer.length < count * 3) wearBuffer = new float[count * 3];
        var point = new Vector3f(); int k = 0;
        for (var part : parts) if (wornBody(part.name))
            for (int i = part.start; i < part.start + part.count; i++) { skin(i, point); wearBuffer[k++] = point.x / 16; wearBuffer[k++] = point.y / 16; wearBuffer[k++] = point.z / 16; }
        wearBody = k;
        return wearBuffer;
    }
    int wearBodyLength() { return wearBody; }
    /** Pose the same three accessory vertices used by the authored mount, including body stretch. */
    public void renderBadge(int tier, PoseStack stack, MultiBufferSource buffers, int light, int overlay) { badgeDraw.renderBadge(tier,stack,buffers,light,overlay); }
    public static void renderDetachedBadge(String name,int tier,boolean cut,PoseStack stack,MultiBufferSource buffers,int light,int overlay) {
            BadgeDraw.renderDetachedBadge(name,tier,cut,stack,buffers,light,overlay); }

    /** Splice placement-fit meshes into the base model they were authored on: a part is overwritten
     *  in place (same triangle order) or appended and redirected; flat normals are rebuilt. */
    static void applyFit(String id,JsonObject data,JsonObject fit,ModelFingerprint hash) { YukkuriModelLoader.applyFit(id,data,fit,hash); }

    /** Merge selected motion clips and the puff morph into a base model whose skeleton matches. */
    static void applyRevision(String id,JsonObject data,JsonObject revision) { YukkuriModelLoader.applyRevision(id,data,revision); }

    YukkuriModel(JsonObject data,ModelFingerprint hash) {
        sourceHash=hash;
        if(data.has("expression_aliases"))data.getAsJsonObject("expression_aliases").entrySet().forEach(e->expressionAliases.put(e.getKey(),e.getValue().getAsString()));
        id = data.get("id").getAsString();
        if (data.get("format").getAsInt() != 1 || data.get("units_per_block").getAsInt() != 16)
            throw new IllegalArgumentException("Unsupported Yukkuri model format");
        vertices = floats(data.getAsJsonArray("vertices"));
        restVertices=vertices.clone();featureBones=new int[vertices.length/8];Arrays.fill(featureBones,-1);
        JsonArray sourceBones = data.getAsJsonArray("bones");
        bones = new Bone[sourceBones.size()];
        world = new Matrix4f[bones.length];
        localMatrices=new Matrix4f[bones.length];lastLocalPose=new float[bones.length][9];
        defaultPose=new float[bones.length][9];
        pose = new float[bones.length][9];
        for (int i = 0; i < bones.length; i++) {
            JsonObject bone = sourceBones.get(i).getAsJsonObject();
            float[] pivot = floats(bone.getAsJsonArray("pivot"));
            int parent = bone.get("parent").getAsInt();
            if (parent < -1 || parent >= i) throw new IllegalArgumentException("Unordered bone hierarchy");
            bones[i] = new Bone(bone.get("name").getAsString(), parent, pivot[0], pivot[1], pivot[2]);
            world[i] = new Matrix4f();localMatrices[i]=new Matrix4f();
            Arrays.fill(lastLocalPose[i],Float.NaN);defaultPose[i][6]=defaultPose[i][7]=defaultPose[i][8]=1;
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
                Arrays.fill(featureBones,start,start+count,bone);featureTransforms.putIfAbsent(bone,new Matrix4f());
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
                selfHatTransfer=new HatTransfer(transfer.get("remove_ticks").getAsFloat(),transfer.get("return_ticks").getAsFloat(),
                        transfer.get("deploy_roll_degrees").getAsFloat(),transfer.get("clearance_model_units").getAsFloat(),
                        transfer.get("slide_begin").getAsFloat(),transfer.get("lower_begin").getAsFloat());
            }
            if (clip.has("puff_envelope")) puffEnvelopes.put(entry.getKey(),floats(clip.getAsJsonArray("puff_envelope")));
            if (clip.has("props")) {
                Map<String, PropTrack> slots = new HashMap<>();
                for (var slot : clip.getAsJsonObject("props").entrySet()) {
                    var prop = slot.getValue().getAsJsonObject();String boneName = prop.get("bone").getAsString();int bone = -1;
                    for (int i = 0; i < bones.length; i++) if (bones[i].name().equals(boneName)) { bone = i; break; }
                    var keys = prop.getAsJsonArray("keys");float[][] samples = new float[keys.size()][];
                    for (int i = 0; i < samples.length; i++) { samples[i] = floats(keys.get(i).getAsJsonArray()); if (samples[i].length != 7) throw new IllegalArgumentException("Invalid prop key"); }
                    if (bone < 0 || samples.length == 0) throw new IllegalArgumentException("Invalid clip prop: " + entry.getKey());
                    slots.put(slot.getKey(), new PropTrack(bone, samples));
                }
                clipProps.put(entry.getKey(), Map.copyOf(slots));
            }
            if (clip.has("markers")) {
                Map<String, Float> markers = new HashMap<>();
                for (var marker : clip.getAsJsonObject("markers").entrySet()) if (marker.getValue().isJsonPrimitive()) markers.put(marker.getKey(), marker.getValue().getAsFloat());
                clipMarkers.put(entry.getKey(), Map.copyOf(markers));
            }
            if (clip.has("expression_spans")) {
                List<ExpressionSpan> spans = new ArrayList<>();
                for (var raw : clip.getAsJsonArray("expression_spans")) {
                    JsonArray span = raw.getAsJsonArray();
                    spans.add(new ExpressionSpan(span.get(0).getAsFloat(),span.get(1).getAsFloat(),span.get(2).getAsString()));
                }
                actionExpressions.put(entry.getKey(),List.copyOf(spans));
            }
            if (clip.has("expression_yield")) {
                var yields = new HashSet<String>();
                for (var raw : clip.getAsJsonArray("expression_yield")) yields.add(raw.getAsString());
                expressionYields.put(entry.getKey(), Set.copyOf(yields));
            }
        }
        if (!clips.containsKey("idle")) throw new IllegalArgumentException("Missing idle defaults");
        clips.get("idle").sample(0,defaultPose);
        houseNormals=id.startsWith("house_")?new HouseNormals(restVertices,parts):null;
        if(puffSurface!=null)puffNormals=new HouseNormals(restVertices,List.of(puffSurface),true);
        if(!id.startsWith("house_")){
            var skin=parts.stream().filter(part->bodySurface(part.name)).toList();
            if(!skin.isEmpty())skinNormals=new AngleNormals(restVertices,skin);
            if(puffSurface!=null)puffSkinNormals=new AngleNormals(restVertices,List.of(puffSurface));
        }
        boolean[] expressionVertices=new boolean[bindings.length];
        for(var patches:expressionPatches.values())for(var patch:patches)
            Arrays.fill(expressionVertices,patch.start/8,(patch.start+patch.values.length)/8,true);
        woundExpressionVertices=expressionVertices;
        skinningCache=new SkinningCache(bindings.length,index->{
            if(expressionVertices[index])return null;
            int offset=index*8;Binding binding=bindings[index];
            return new SkinningCache.Key(restVertices[offset],restVertices[offset+1],restVertices[offset+2],
                    binding.first,binding.second,binding.weight,featureBones[index],
                    puffOffsets==null?0:puffOffsets[index*3],puffOffsets==null?0:puffOffsets[index*3+1],
                    puffOffsets==null?0:puffOffsets[index*3+2]);
        });
        quadFourth=new int[bindings.length];Arrays.fill(quadFourth,-1);quadRotation=new byte[bindings.length];
        for(var part:parts){
            if(bodySurface(part.name)||part.name.startsWith("face_")||!part.anatomy.isEmpty()||part.name.contains("puff"))continue;
            for(int first=part.start;first+5<part.start+part.count;first+=3){
                boolean rigid=true;
                for(int i=first;i<first+6;i++){
                    if(expressionVertices[i]||!bindings[first].equals(bindings[i])||featureBones[first]!=featureBones[i]){rigid=false;break;}
                    for(int k=5;k<8;k++)if(restVertices[first*8+k]!=restVertices[i*8+k])rigid=false;
                }
                if(!rigid)continue;
                int shared=0,missing=-1,fourth=-1;
                for(int i=0;i<3;i++){
                    boolean found=false;for(int j=3;j<6;j++)if(sameCorner(first+i,first+j)){found=true;break;}
                    if(found)shared++;else missing=i;
                }
                if(shared!=2)continue;
                for(int j=3;j<6;j++)if(!sameCorner(first,first+j)&&!sameCorner(first+1,first+j)&&!sameCorner(first+2,first+j))fourth=first+j;
                if(fourth<0||!coplanarPair(first,fourth))continue;
                quadFourth[first]=fourth;quadRotation[first]=(byte)((missing+2)%3);first+=3;
            }
        }
        boolean[] bodyVertices=new boolean[bindings.length];
        for(var part:parts)if(bodySurface(part.name))
            Arrays.fill(bodyVertices,part.start,part.start+part.count,true);
        bodyFloorVertices=skinningCache.uniqueIndices(index->bodyVertices[index]);
        for(var part:parts)cacheHairVisibility(part,expressionVertices);
        for(var part:puffAttachments.values())cacheHairVisibility(part,expressionVertices);
        if(puffSurface!=null)cacheHairVisibility(puffSurface,expressionVertices);
    }

    private boolean coplanarPair(int first,int fourth){
        int a=first*8,b=(first+1)*8,c=(first+2)*8,d=fourth*8;
        var ab=new Vector3f(restVertices[b]-restVertices[a],restVertices[b+1]-restVertices[a+1],restVertices[b+2]-restVertices[a+2]);
        var ac=new Vector3f(restVertices[c]-restVertices[a],restVertices[c+1]-restVertices[a+1],restVertices[c+2]-restVertices[a+2]);
        var n=new Vector3f(ab).cross(ac);float length=n.length();if(length<1e-8f)return false;
        float distance=n.x*(restVertices[d]-restVertices[a])+n.y*(restVertices[d+1]-restVertices[a+1])+n.z*(restVertices[d+2]-restVertices[a+2]);
        if(Math.abs(distance)>length*1e-5f)return false;
        int e=(first+3)*8,f=(first+4)*8,g=(first+5)*8;
        ab.set(restVertices[f]-restVertices[e],restVertices[f+1]-restVertices[e+1],restVertices[f+2]-restVertices[e+2]);
        ac.set(restVertices[g]-restVertices[e],restVertices[g+1]-restVertices[e+1],restVertices[g+2]-restVertices[e+2]);
        return ab.cross(ac).dot(n)>0;
    }

    private boolean sameCorner(int a,int b){
        for(int k=0;k<8;k++)if(restVertices[a*8+k]!=restVertices[b*8+k])return false;
        return true;
    }

    private Part puffPart(JsonObject source,Part original,int vertexCount){
        int start=source.get("start").getAsInt(),count=source.get("count").getAsInt();
        float[] offsets=floats(source.getAsJsonArray("offsets"));
        if(start<0||count<0||count%3!=0||start+count>vertexCount||offsets.length!=count*3)throw new IllegalArgumentException("Invalid puff mesh range");
        System.arraycopy(offsets,0,puffOffsets,start*3,offsets.length);
        return new Part(original.name,start,count,original.anatomy);
    }

    JsonObject badgeMount(){return badgeMount;}
    static JsonObject detachedBadge(String name){return detachedBadges.get(name);}
    String id(){return id;}
    List<Part> parts(){return parts;}
    float[] restVertices(){return restVertices;}
    Vector3f normal(){return normal;}
    Vector3f edge(){return edge;}
    float[] vertices(){return vertices;}
    Vector3f[] triangle(){return triangle;}
    Bone[] bones(){return bones;}
    Matrix4f[] world(){return world;}
    float[][] pose(){return pose;}
    static float[] floats(JsonArray values) {
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
        for(int i=0;i<pose.length;i++)System.arraycopy(defaultPose[i],0,pose[i],0,9);
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
        // Leaf-swaddle care reuses the first-toilet rear contact; harvesting and folding the leaves reuse the bedding clips.
        if (ToiletPresentation.rearCare(behavior)) selected=Behaviors.FIRST_TOILET_ASSIST;
        else if (behavior.equals(Behaviors.LEAF_SWADDLE_HARVEST)) selected=Behaviors.NEST_HARVEST;
        else if (behavior.equals(Behaviors.LEAF_SWADDLE_FOLD)) selected=Behaviors.NEST_PRESS;
        // A transfer already chose its clip and clock. The old carry pose folds both locks by 52 degrees
        // (Marisa's left lock is ordinary hair) and tilts the child independently of the live contact.
        boolean liftClip=action.equals(YukkuriAction.BRAID_CRADLE_LIFT.id())||action.equals(YukkuriAction.MOUTH_BITE_HOLD.id())
                ||action.equals(YukkuriAction.BE_CRADLED.id());
        if(behavior.equals(Behaviors.HAT_WALK)||behavior.equals(Behaviors.LEARN_WATCH)||behavior.equals(Behaviors.CARE_FINISH)||
                !liftClip&&(behavior.equals(Behaviors.BOAT_CARRY_YOUNG)||behavior.equals(Behaviors.BOAT_BE_CARRIED)||behavior.equals(Behaviors.BOAT_PREPARE_CARRY)||behavior.equals(Behaviors.BOAT_RELEASE_CARRY))
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
        if(reproductiveAnatomy!=null&&reproductiveAmount>0&&delivery==null) {
            Clip extend=clips.get("anatomy_"+reproductiveAnatomy+"_extend");
            if(extend!=null)for(var track:extend.tracks)if(bones[track.bone].name.startsWith("anatomy_"))
                track.sample(extend.duration*reproductiveAmount,pose[track.bone]);
        }
        if(carryLoad>0&&selected.equals("waddle")){
            // A carrier walks steadier: the same step with less sway, so riders are not flung about.
            float keep=1-CARRY_GAIT_DAMP*carryLoad;
            for(int i=0;i<pose.length;i++)for(int k=0;k<9;k++)pose[i][k]=defaultPose[i][k]+(pose[i][k]-defaultPose[i][k])*keep;
        }
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
        if(gripping||drawingSurfaceMarks||woundDisplay!=null)puffAmount=0;
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
        var hat=HatCarry.shape(id);
        if(hat!=null&&!hatMotion.isEmpty()){
            float q=Math.clamp(hatPhase,0,1);if(hatMotion.equals("HAT_RETURN"))q=1-q;
            float rise=Math.min(1,q/.55f);rise=rise*rise*(3-2*rise);
            float forward=Math.max(0,(q-.4f)/.6f);forward=forward*forward*(3-2*forward);
            var root=pose[hat.get("bone").getAsInt()];root[1]+=hat.get("lift").getAsFloat()*rise;
            root[2]+=hat.get("forward").getAsFloat()*forward;
        }
        boolean careContact=Float.isFinite(careTargetMouthY)&&(behavior.equals(Behaviors.FEED_CHILD)||behavior.equals(Behaviors.BOAT_FEED_YOUNG)||ToiletPresentation.rearCare(behavior));
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
            boolean feeding=!ToiletPresentation.rearCare(behavior);
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
        if(carryLean>0&&!careContact&&!Float.isFinite(boardingPitch)){
            // Bow to take up or set down a child; the lowest body point stays on the ground.
            float floor=bodyMinimumY();pose[rootBone()][3]-=carryLean*CARRY_LEAN_DEGREES;composeWorld();
            float lowered=bodyMinimumY();if(lowered<floor)raiseWorld(floor-lowered);
        }
        if(!tailCurlChecked){tailCurlChecked=true;tailCurl=CarryTailCurl.of(this);}
        if(tailCurl!=null){if(carryCurlLeft>0||carryCurlRight>0)tailCurl.pose(world,carryCurlLeft,carryCurlRight);else tailCurl.clear();}
        if(braidPose.active())braidPose.poseRescueBraid();
        if(pregnancyBelly>.001f)rigidLifts();
        if (delivery != null) delivery.follow(world[rootBone()]);
        featureTransforms.forEach((bone,matrix)->{matrix.set(world[bone]);if(bones[bone].parent>=0)matrix.mul(featureParentInverse.set(world[bones[bone].parent]).invert());});
        // Contact solvers above change the bones repeatedly. Cache only after the final pose.
        skinningCache.begin();
        try {
            // A swaddle or leaf lies under the hair: read the final posed skin before any hair is drawn (CareWearRenderer.clearHair).
            wearClear=false;
            if(wear!=0)wearClear=CareWearRenderer.prepareClearance(this,wear);
            renderPosed(action,stack,buffer,light,overlay,accessory,hairLoss);
        } finally {
            skinningCache.end();
        }
    }

    private void renderPosed(String action,PoseStack stack,VertexConsumer buffer,int light,int overlay,
                             boolean accessory,int hairLoss) {
        PoseStack.Pose transform=stack.last();
        if(accessory&&hatDamageDraw.fit()<.999f)hatDamageDraw.poseHatSeat();
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
        if(skinNormals!=null)updateAngleNormals(skinNormals);
        if(puffSkinNormals!=null&&puffAmount>0)updateAngleNormals(puffSkinNormals);
        cardNormals.clear();
        if(!id.startsWith("house_"))for(var card:parts)if(card.name.startsWith("face_")&&card.anatomy.isEmpty()){
            var sum=cardNormals.computeIfAbsent(card.name,ignored->new Vector3f()).zero();
            for(int first=card.start;first<card.start+card.count;first+=3){
                if(vertices[first*8+7]>=-.15f)continue;
                for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);
                normal.set(triangle[1]).sub(triangle[0]);edge.set(triangle[2]).sub(triangle[0]);sum.add(normal.cross(edge));
            }
            if(sum.lengthSquared()>1e-16f)sum.normalize();else cardNormals.remove(card.name);
        }
        var pregnantSurface=pregnancyBelly>.001f?pregnancySurface.begin():null;
        if(pregnancyBelly>.001f)for(int index=0;index<parts.size();index++)if(bodySurface(parts.get(index).name)){
            Part surface=puffAmount>0&&index==puffPartIndex?puffSurface:parts.get(index);
            for(int first=surface.start;first<surface.start+surface.count;first+=3){
                if(woundDisplay!=null&&woundDisplay.pregnancySurface(first,pregnantSurface))continue;
                for(int corner=0;corner<3;corner++)skin(first+corner,triangle[corner]);pregnantSurface.add(triangle);
            }
        }
        if(pregnantSurface!=null)pregnantSurface.finish();
        for (int partIndex=0;partIndex<parts.size();partIndex++) {
            Part part=puffAmount>0?(puffPartIndex==partIndex?puffSurface:puffAttachments.getOrDefault(partIndex,parts.get(partIndex))):parts.get(partIndex);
            if(drawingWoundedBody&&WoundSurface.get(id).replaced.contains(part.name))continue;
            if(hairHidden(part,hairLoss))continue;
            if (!accessory && accessoryPart(part)) continue;
            boolean hatPart=(hatDamageDraw.level()>0||hatDamageDraw.fit()<.999f)&&accessoryPart(part);
            // Delivery draws and opens the same authored mamu itself; do not draw it twice here.
            // Explicit visibility also covers interruption, idle and resource reload.
            boolean explicitAnatomy=action.startsWith("anatomy_" + part.anatomy + "_");
            boolean layeredAnatomy=part.anatomy.equals(reproductiveAnatomy)&&reproductiveAmount>.001f;
            if (!part.anatomy.isEmpty() && (delivery!=null || !explicitAnatomy&&!layeredAnatomy)) continue;
            if (hatPart && hatDamageDraw.level() > 0) {
                var source=parts.get(partIndex);
                if(source.start==part.start&&source.count==part.count){
                    hatDamageDraw.renderHatFaces(buffer,transform,part,hatDamageDraw.hatFaces(partIndex),light,overlay,
                            woundDisplay==null?0xFFFFFF:woundDisplay.tint(part.name));
                    continue;
                }
            }
            int tint=woundDisplay==null?0xFFFFFF:woundDisplay.tint(part.name);
            var curled=tailCurl==null?null:tailCurl.part(partIndex);
            for (int first = part.start; first < part.start + part.count; first += 3) {
                if(woundDisplay!=null&&woundDisplay.triangle(first,stack,buffer,light,overlay))continue;
                // House expression cards overlap on the curved face. Their opaque
                // backs can hide neighbouring eyes with the no-cull entity material.
                // Keep the authored front ink; the head already supplies the skin.
                // Back and side faces of box cards are skipped on every model for the same reason.
                if(part.name.startsWith("face_")&&vertices[first*8+7]>=-.15f)continue;
                if(pregnancyBelly>.001f&&part.name.startsWith("face_")){
                    // The thin sprite boxes have opaque skin-coloured backs. On a curved face those backs
                    // receive inverted lighting through transparent pixels; the underlying head supplies skin.
                    if(vertices[first*8+7]>=-.15f)continue;
                    float[] uv=triangleUv;
                    for(int corner=0;corner<3;corner++){skinBase(first+corner,triangle[corner]);uv[corner*2]=vertices[(first+corner)*8+3];uv[corner*2+1]=vertices[(first+corner)*8+4];}
                    PregnancyFaces.render(triangle,uv,pregnancyBelly,pregnancyContraction,delivery,stack,buffer,light,overlay,pregnantSurface);continue;
                }
                for (int corner = 0; corner < 3; corner++) skin(first + corner, triangle[corner]);
                if(layeredAnatomy&&part.anatomy.equals("mamu"))for(int corner=0;corner<3;corner++){
                    // The birth outlet stays at the front. Only the reproductive layer turns the
                    // same authored part to the rear, including its skin and mark.
                    triangle[corner].x=-triangle[corner].x;triangle[corner].z=-triangle[corner].z+AgitationPresentation.mamuRearOffset();
                }
                if(curled!=null)for(int corner=0;corner<3;corner++)curled.transformPosition(triangle[corner]);
                var lifted=cardNormals.get(part.name);
                if(lifted!=null)for(int corner=0;corner<3;corner++)triangle[corner].fma(CARD_LIFT,lifted);
                if(hatPart&&hatDamageDraw.fit()<.999f)for(int corner=0;corner<3;corner++)triangle[corner].sub(hatDamageDraw.seat()).mul(hatDamageDraw.fit()).add(hatDamageDraw.seat());
                normal.set(triangle[1]).sub(triangle[0]);
                edge.set(triangle[2]).sub(triangle[0]);
                normal.cross(edge);
                if (normal.lengthSquared() < 1.0e-16f) continue;
                normal.normalize();
                if(delivery!=null&&bodySurface(part.name)&&normal.z<-.15f){
                    float[] uv=triangleUv;for(int corner=0;corner<3;corner++){uv[corner*2]=vertices[(first+corner)*8+3];uv[corner*2+1]=vertices[(first+corner)*8+4];}
                    if(delivery.drawMotherTriangle(stack,buffer,light,overlay,triangle,uv))continue;
                }
                // An affine, flat pair has the same source diagonal as a native quad.
                // Keep nonlinear pregnancy/puff/delivery and damaged hats on their original triangles.
                int fourth=pregnancyBelly<=0&&puffAmount<=0&&delivery==null&&!hatPart&&woundDisplay==null?quadFourth[first]:-1;
                if(fourth>=0){skin(fourth,fourthCorner);if(curled!=null)curled.transformPosition(fourthCorner);
                    if(layeredAnatomy&&part.anatomy.equals("mamu")){fourthCorner.x=-fourthCorner.x;fourthCorner.z=-fourthCorner.z+AgitationPresentation.mamuRearOffset();}}
                int rotation=fourth>=0?quadRotation[first]:0;
                for (int corner = 0; corner < 4; corner++) {
                    int index=fourth>=0?(rotation+corner)%3:Math.min(corner,2);
                    boolean last=fourth>=0&&corner==3;
                    Vector3f point=last?fourthCorner:triangle[index];
                    int vertex=last?fourth:first+index;
                    int uv = vertex * 8 + 3;
                    Vector3f vertexNormal=houseNormals!=null&&houseNormals.groups[vertex]>=0
                            ?houseNormals.normals[houseNormals.groups[vertex]]:normal;
                    var angled=puffAmount>0&&puffSkinNormals!=null&&puffSkinNormals.has(vertex)?puffSkinNormals
                            :skinNormals!=null&&skinNormals.has(vertex)?skinNormals:null;
                    Vector3f smoothBase=angled==null?normal:angled.normals[vertex];
                    if(angled!=null)vertexNormal=smoothBase;
                    if(puffNormals!=null&&puffAmount>0&&puffNormals.groups[vertex]>=0)
                        vertexNormal=puffBlendedNormal.set(smoothBase).lerp(puffNormals.normals[puffNormals.groups[vertex]],puffAmount).normalize();
                    var card=cardNormals.get(part.name);if(card!=null)vertexNormal=card;
                    buffer.addVertex(transform.pose(), point.x / 16, point.y / 16, point.z / 16)
                            .setColor(tint>>16&255, tint>>8&255, tint&255, 255).setUv(vertices[uv], vertices[uv + 1])
                            .setOverlay(overlay).setLight(light)
                            .setNormal(transform, vertexNormal.x, vertexNormal.y, vertexNormal.z);
                }
                if(fourth>=0)first+=3;
            }
        }
    }

    private void composeWorld(){
        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];float[] v = pose[i];Matrix4f matrix = world[i];
            var local=localMatrices[i];
            if(!Arrays.equals(v,lastLocalPose[i])){
                local.identity().translate(bone.x+v[0],bone.y+v[1],bone.z+v[2])
                        .rotateZYX(v[5]*DEGREES,v[4]*DEGREES,v[3]*DEGREES)
                        .scale(v[6],v[7],v[8]).translate(-bone.x,-bone.y,-bone.z);
                System.arraycopy(v,0,lastLocalPose[i],0,9);
            }
            if(bone.parent<0)matrix.set(local);else matrix.set(world[bone.parent]).mul(local);
            if(hatScale!=1&&i==hatFollowBone()){
                var a=HatGeometry.anchor();matrix.translate(a).scale(hatScale).translate(a.negate());
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
        LiveBirthGeometry.deform(point,pregnancyBelly,0);
        world[rootBone()].transformPosition(point);return point;
    }

    /** Apply a world-space model-unit lift to every bone so child attachments follow the landed body. */
    private void raiseWorld(float lift){for(var matrix:world)matrix.m31(matrix.m31()+lift);}

    /** Squared distance from the camera to the entity origin: the render pose is camera-relative. */
    private static float cameraDistanceSquared(PoseStack stack){var pose=stack.last().pose();return pose.m30()*pose.m30()+pose.m31()*pose.m31()+pose.m32()*pose.m32();}

    public boolean woundGeometryCompatible(){return sourceHash.matches(WoundSurface.get(id).runtimeSourceHash);}
    public void renderLiving(String action,float elapsed,PoseStack stack,VertexConsumer buffer,int light,int overlay,
            boolean accessory,String behavior,float behaviorTicks,int hairLoss,YukkuriView entity,
            MultiBufferSource buffers){
        try{
            drawingSurfaceMarks=WoundRenderer.active(entity);
            if(drawingSurfaceMarks&&!woundGeometryCompatible()){
                drawingSurfaceMarks=false;if(!warnedWoundMismatch){
                        LoggerFactory.getLogger(YukkuriModel.class).warn("Wound attachment disabled for incompatible resource-pack geometry: {}",id);warnedWoundMismatch=true;}
            }
            drawingWoundedBody=false;
            String expression=entity.getExpressionId();
            setExpression(expression);
            float health=entity.getMaxHealth()<=0?1:Math.clamp(entity.getHealth()/entity.getMaxHealth(),0,1);
            var wounds=new ArrayList<>(entity.visibleWounds());
            for(var mark:entity.visibleMarks())if(mark.kind()==WoundKind.SCAR)wounds.add(mark);
            adultStatePose=AgitationPresentation.selected(entity);
            hatMotion=entity.contactPose().equals("HAT_REMOVE")||entity.contactPose().equals("HAT_RETURN")?entity.contactPose():"";
            hatPhase=entity.contactTurn();
            hatScale=HatScale.factor(entity.subspeciesFlags());
            int headwear=entity.visibleHeadwear();
            hatDamageDraw.visual(headwear);
            // Pieces licked together sit on the head as a cracked hat before they knit, though the owner is not yet recognised.
            boolean pieces=hatDamageDraw.level()==3;
            long perfStart=WoundLod.start();
            if(woundGeometryCompatible()&&HairWoundAssets.model(id)!=null&&(!wounds.isEmpty()||(entity.visibleSigns()&BodySigns.FOOT_BURNED)!=0))
                woundDisplay=HairWoundRenderer.prepare(entity,this,id,expression,wounds,entity.visibleSigns(),health,hairLoss,
                        accessory||pieces||hatMotion.equals("HAT_REMOVE"),headwear,true,cameraDistanceSquared(stack));
            if(woundDisplay!=null)WoundLod.time(WoundLod.PREPARE,perfStart);
            // Taking the cap buffer here would end the body's buffer before any body vertices are written.
            hatDamageDraw.deferCaps();
            long bodyStart=woundDisplay==null?0:WoundLod.start();
            render(action,elapsed,stack,buffer,light,overlay,accessory||pieces||hatMotion.equals("HAT_REMOVE"),behavior,behaviorTicks,hairLoss);
            if(woundDisplay!=null)WoundLod.time(WoundLod.BODY,bodyStart);
            // The shared per-asset model keeps the last capture; every mouth-held prop, including a Myon's
            // free-mouth stick sword, must refresh it or it renders at a stale/root origin under the body.
            if(!entity.getMouthItem().isEmpty()||!entity.getMouthBodyFood().isEmpty()||!entity.getCarriedItem().isEmpty()
                    ||entity.visibleKanroken()||mouthHolding)captureMouthAttachment();
            long surfaceStart=woundDisplay==null?0:WoundLod.start();
            if(drawingSurfaceMarks)WoundRenderer.render(entity,expression,world,stack,buffer,buffers,light,overlay);
            if(woundDisplay!=null){WoundLod.time(WoundLod.SURFACE,surfaceStart);long displayStart=WoundLod.start();
                woundDisplay.render(world,pregnancyBelly,stack,buffers,light,overlay);WoundLod.time(WoundLod.DISPLAY,displayStart);WoundLod.drawn(!woundDisplay.simple);}
            hatDamageDraw.drawCaps(buffers);
        }finally{wear=0;wearClear=false;woundDisplay=null;adultStatePose=false;drawingWoundedBody=false;drawingSurfaceMarks=false;mouthHolding=false;carryLoad=carryLean=carryCurlLeft=carryCurlRight=0;hatMotion="";hatScale=1;hatDamageDraw.reset();setCareContact(0,
                Float.NaN,0,0);setBraidRescueContact(null);setBraidRescueJoint(null);setBraidRescueGrip(false);setBraidCarryContact(false);
                setBoardingPose(Float.NaN,0,1,0);setSelfHatPose(null);setExpression("");setReproductiveAnatomy(null,0);}
    }
    /** The remains data a frame reads, derived once per synced snapshot (RemainsRenderer caches it per entity). */
    record RemainsInputs(List<BodyWound> wounds,UUID seed,int rotStage,int packedSigns,float fullness,int subspeciesFlags,boolean accessory,
                         int headwear,int hairLoss,boolean coreKept,int medal){}
    RemainsInputs remainsInputs(CompoundTag data){
        var body=data.getCompound("Body");
        var mesh=WoundSurface.get(id);
        var injuries=(BodyWound.read(body).stream()
                .map(w->WoundMigration.restore(id,mesh,body,w))
                .filter(Objects::nonNull).filter(mesh::valid).toList());
        var wounds=new ArrayList<>(injuries);
        wounds.addAll(Marks.read(data.getCompound("Marks")).stream().filter(mesh::valid).toList());
        // The remains keep the body signs (mould, baked sole, bare or broken core) of the moment of death.
        var seed=data.hasUUID("Identity")?data.getUUID("Identity"):new UUID(0,0);
        // Decay shows as mould patches (stale: middle tier, rotten: terminal) over the discoloured, sagging body (RemainsRenderer squashes it).
        int rotStage=RemainsRot.stage(data);
        int packedSigns=BodySigns.pack(Math.max(body.getInt("Mold"),rotStage==2?8:rotStage==1?4:0),body.getBoolean("FootBurned"),body.getBoolean("CoreHurt"),false);
        float foodCapacity=CorpseFood.capacity(data);
        float fullness=foodCapacity<=0?0:Math.clamp(CorpseFood.remaining(data)/foodCapacity,0,1);
        return new RemainsInputs(wounds,seed,rotStage,packedSigns,fullness,data.getInt("SubspeciesFlags"),
                !data.contains("Accessory")||data.getBoolean("Accessory"),data.getInt("Headwear"),body.getInt("HairLoss"),!data.getBoolean("CoreTaken"),data.getInt("Medal"));
    }
    void renderRemains(Object key,RemainsInputs in,RemainsAssets.Look look,PoseStack stack,VertexConsumer buffer,
                              MultiBufferSource buffers,int light,int overlay){
        String face=look.face();
        boolean house=id.startsWith("house_");
        var wounds=in.wounds();int packedSigns=in.packedSigns();float fullness=in.fullness();
        var signs=WoundRenderer.signs(key,in.seed(),id,packedSigns,0,wounds);
        drawingSurfaceMarks=(!wounds.isEmpty()||!signs.isEmpty())&&woundGeometryCompatible();
        drawingWoundedBody=false;
        setCarrying(false);setPregnancy(0,0);setDelivery(null);setExpression(face);
        hatScale=HatScale.factor(in.subspeciesFlags());
        boolean accessory=in.accessory();
        int headwear=in.headwear();hatDamageDraw.visual(headwear);
        try{
            long perfStart=WoundLod.start();
            if(woundGeometryCompatible()&&HairWoundAssets.model(id)!=null&&(!wounds.isEmpty()||(packedSigns&BodySigns.FOOT_BURNED)!=0))
                woundDisplay=HairWoundRenderer.prepare(key,this,id,face,wounds,packedSigns,0,in.hairLoss(),accessory,headwear,in.coreKept(),cameraDistanceSquared(stack));
            if(woundDisplay!=null)WoundLod.time(WoundLod.PREPARE,perfStart);
            long bodyStart=woundDisplay==null?0:WoundLod.start();
            render("idle",0,stack,buffer,light,overlay,accessory,Behaviors.IDLE,0,in.hairLoss());
            if(woundDisplay!=null)WoundLod.time(WoundLod.BODY,bodyStart);
            long surfaceStart=woundDisplay==null?0:WoundLod.start();
            if(drawingSurfaceMarks)WoundRenderer.render(key,id,face,wounds,signs,0,fullness,house,in.coreKept(),world,stack,buffer,buffers,light,overlay);
            if(woundDisplay!=null){WoundLod.time(WoundLod.SURFACE,surfaceStart);long displayStart=WoundLod.start();
                woundDisplay.render(world,0,stack,buffers,light,overlay);WoundLod.time(WoundLod.DISPLAY,displayStart);WoundLod.drawn(!woundDisplay.simple);}
            // Taken last: the body and wound passes above still write into their own buffers.
            if(look.overlay()!=null)renderRemainsSkin(stack,buffers.getBuffer(RenderType.entityCutoutNoCullZOffset(look.overlay())),light,overlay);
            if(accessory)renderBadge(in.medal(),stack,buffers,light,overlay);
        }finally{woundDisplay=null;drawingWoundedBody=false;drawingSurfaceMarks=false;hatScale=1;hatDamageDraw.reset();setExpression("");}
    }
    private float[] remainsSkinFrame;
    /**
     * The remains skin overlay: a cylindrical map over the rest skin (u around the body with the face at .5, v from the top to
     * the sole), drawn just outside the posed skin. Triangles an injury has replaced keep their own wound art.
     */
    private void renderRemainsSkin(PoseStack stack,VertexConsumer buffer,int light,int overlay){
        if(remainsSkinFrame==null){
            float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
            for(var part:parts)if(bodySurface(part.name))for(int v=part.start;v<part.start+part.count;v++){
                float x=restVertices[v*8],y=restVertices[v*8+1],z=restVertices[v*8+2];
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
            }
            if(minX>maxX)return;
            remainsSkinFrame=new float[]{(minX+maxX)/2,(minZ+maxZ)/2,Math.max(.001f,(maxX-minX)/2),Math.max(.001f,(maxZ-minZ)/2),minY,Math.max(.001f,maxY-minY)};
        }
        float[] f=remainsSkinFrame;float[] uv=new float[6];
        Vector3f n=new Vector3f(),e=new Vector3f(),a=new Vector3f(),b=new Vector3f(),c=new Vector3f();
        Vector3f na=new Vector3f(),nb=new Vector3f(),nc=new Vector3f();
        for(var part:parts){
            if(!bodySurface(part.name))continue;
            for(int first=part.start;first<part.start+part.count;first+=3){
                if(woundDisplay!=null&&woundDisplay.replaced(first))continue;
                float minU=1,maxU=0;
                for(int corner=0;corner<3;corner++){
                    int offset=(first+corner)*8;
                    float u=(float)((Math.atan2((restVertices[offset]-f[0])/f[2],-(restVertices[offset+2]-f[1])/f[3])+Math.PI)/(2*Math.PI));
                    uv[corner*2]=u;uv[corner*2+1]=Math.clamp(1-(restVertices[offset+1]-f[4])/f[5],0,1);
                    minU=Math.min(minU,u);maxU=Math.max(maxU,u);
                    skin(first+corner,triangle[corner]);
                }
                // The back seam wraps like the sole char band (WoundRenderer.soleTexture).
                if(maxU-minU>.5f)for(int corner=0;corner<3;corner++)if(uv[corner*2]<.5f)uv[corner*2]+=1;
                n.set(triangle[1]).sub(triangle[0]).cross(e.set(triangle[2]).sub(triangle[0]));
                if(n.lengthSquared()<1e-16f)continue;n.normalize();
                // Smooth vertex normals keep neighbouring triangles on shared offset vertices (no seams); the polygon-offset render type
                // settles the fight with the skin, and the small lift stays below CARD_LIFT so the face cards win over the overlay.
                if(!woundVertexNormal(first,na))na.set(n);if(!woundVertexNormal(first+1,nb))nb.set(n);if(!woundVertexNormal(first+2,nc))nc.set(n);
                a.set(triangle[0]).fma(REMAINS_LIFT,na);b.set(triangle[1]).fma(REMAINS_LIFT,nb);c.set(triangle[2]).fma(REMAINS_LIFT,nc);
                WoundRenderer.emit(stack,buffer,light,overlay,a,b,c,uv[0],uv[1],uv[2],uv[3],uv[4],uv[5],na,nb,nc);
            }
        }
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
    boolean accessoryPart(Part part){
        // This configuration reloads after model construction, so read the current prefixes.
        for(String prefix:LivingPresentation.variants.get(id).accessoryPrefixes())if(part.name.startsWith(prefix))return true;
        return false;
    }
    private boolean hairHidden(Part part,int loss){
        if(loss==0)return false;
        Integer mask=staticHairMasks.get(part);if(mask!=null)return (loss&mask)!=0;
        var selected=HairWoundAssets.model(id);
        if(!(part.name.contains("hair")||part.name.contains("bang")||part.name.contains("braid")||part.name.startsWith("wasa_")
                ||selected!=null&&selected.hair.contains(part.name)))return false;
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
    void skin(int index, Vector3f target) {
        skinBase(index,target);
        if(pregnancyBelly>0){
            int group=rigidGroups==null?0:rigidGroups[index];
            if(group==1)target.add(crownLift);else if(group==2)target.add(braidLift);
            else LiveBirthGeometry.deform(target,pregnancyBelly,pregnancyContraction);
        }
        if(wearClear&&overWearVertex(index))CareWearRenderer.clearHair(target);
    }
    private int hatFollowBone(){
        if(hatFollowBone==-2){hatFollowBone=-1;braidBone=-1;for(int i=0;i<bones.length;i++){if(bones[i].name().equals("hat_follow"))hatFollowBone=i;
                if(bones[i].name().equals("hand_right"))braidBone=i;}}
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
        crownLift.set(PregnancyAttachments.crownLift(id,pregnancyBelly,hat>=0?pose[hat]:null));
        braidLift.zero();
        if(braid>=0){
            var root=new Vector3f(bones[braid].x(),bones[braid].y(),bones[braid].z());
            braidLift.set(PregnancyAttachments.braidLift(root,pregnancyBelly,pose[braid]));
        }
    }
    private void skinBase(int index, Vector3f target) {
        // Only vertices a wound actually moved leave the shared cache; the rest still match their rest-point key.
        boolean moved=woundDisplay!=null&&woundDisplay.moved(index);
        if(!moved&&skinningCache.read(index,target))return;
        int offset = index * 8;
        Binding binding = bindings[index];
        target.set(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
        if(woundDisplay!=null)woundDisplay.deform(index,target);
        if(puffAmount>0)target.add(puffAmount*puffOffsets[index*3],puffAmount*puffOffsets[index*3+1],puffAmount*puffOffsets[index*3+2]);
        if (binding.weight > 0) {
            second.set(target);
            world[binding.second].transformPosition(second);
        }
        world[binding.first].transformPosition(target);
        if (binding.weight > 0) target.lerp(second, binding.weight);
        int feature=featureBones[index];if(feature>=0)featureTransforms.get(feature).transformPosition(target);
        // Pregnancy and rigid accessory lifts happen in skin(), after this cached base position.
        if(!moved)skinningCache.write(index,target);
    }
    float[] woundVertices(){return vertices;}
    List<Part> woundParts(){return parts;}
    boolean woundVisible(Part part,int hairLoss,boolean accessory){return !hairHidden(part,hairLoss)&&(accessory||!accessoryPart(part));}
    /** Reprojected fragment retains the original triangle's exact blended bone binding. */
    void woundSkin(int triangle,float a,float b,float c,Vector3f rest,Vector3f target){
        woundSkin(triangle,a,b,c,rest,target,true);
    }
    void woundSkin(int triangle,float a,float b,float c,Vector3f rest,Vector3f target,boolean pregnancy){
        target.zero();
        for(int corner=0;corner<3;corner++){
            int index=triangle+corner;Binding binding=bindings[index];
            float bary=corner==0?a:corner==1?b:c;
            second.set(rest);world[binding.first].transformPosition(second);
            target.fma(bary*(1-binding.weight),second);
            if(binding.weight>0){second.set(rest);world[binding.second].transformPosition(second);target.fma(bary*binding.weight,second);}
        }
        int feature=featureBones[triangle];if(feature>=0)featureTransforms.get(feature).transformPosition(target);
        if(pregnancy&&pregnancyBelly>0){int group=rigidGroups==null?0:rigidGroups[triangle];
            if(group==1)target.add(crownLift);else if(group==2)target.add(braidLift);else LiveBirthGeometry.deform(target,pregnancyBelly,pregnancyContraction);}
        if(hatDamageDraw.fit()<.999f&&woundAccessoryVertex(triangle))target.sub(hatDamageDraw.seat()).mul(hatDamageDraw.fit()).add(hatDamageDraw.seat());
        if(wearClear&&overWearVertex(triangle))CareWearRenderer.clearHair(target);
    }
    private boolean woundAccessoryVertex(int vertex){
        if(woundAccessoryVertices==null){woundAccessoryVertices=new boolean[bindings.length];
            for(var part:parts)if(accessoryPart(part))Arrays.fill(woundAccessoryVertices,part.start,part.start+part.count,true);}
        return woundAccessoryVertices[vertex];
    }
    boolean woundDamagedHat(Part part){return hatDamageDraw.level()>0&&accessoryPart(part);}
    void woundHatQuad(int[] corners,float u,float v,Vector3f target){
        for(int i=0;i<4;i++){skin(corners[i],woundHatCorners[i]);if(hatDamageDraw.fit()<.999f)
            woundHatCorners[i].sub(hatDamageDraw.seat()).mul(hatDamageDraw.fit()).add(hatDamageDraw.seat());}
        target.set(woundHatCorners[0]).mul((1-u)*(1-v)).fma(u*(1-v),woundHatCorners[1])
                .fma(u*v,woundHatCorners[2]).fma((1-u)*v,woundHatCorners[3]);
    }
    /** The smooth normal the body loop draws this skin vertex with; false for non-skin parts and cards, which keep their own rules. */
    boolean woundVertexNormal(int vertex,Vector3f out){
        if(vertex<0||vertex>=bindings.length)return false;
        var angled=puffAmount>0&&puffSkinNormals!=null&&puffSkinNormals.has(vertex)?puffSkinNormals
                :skinNormals!=null&&skinNormals.has(vertex)?skinNormals:null;
        if(angled!=null)out.set(angled.normals[vertex]);
        else if(houseNormals!=null&&houseNormals.groups[vertex]>=0)out.set(houseNormals.normals[houseNormals.groups[vertex]]);
        else return false;
        if(puffNormals!=null&&puffAmount>0&&puffNormals.groups[vertex]>=0)out.lerp(puffNormals.normals[puffNormals.groups[vertex]],puffAmount).normalize();
        return true;
    }
    boolean woundPregnancyFace(String part){return pregnancyBelly>.001f&&part.startsWith("face_");}
    /** Replacement triangles retain the same pregnancy projection and real birth outlet subtraction. */
    boolean woundPatch(String part,Vector3f[] points,float[] uv,PoseStack stack,VertexConsumer buffer,int light,int overlay,
                       int red,int green,int blue,float alphaA,float alphaB,float alphaC){
        if(woundPregnancyFace(part)){
            PregnancyFaces.render(points,uv,pregnancyBelly,pregnancyContraction,delivery,stack,buffer,light,overlay,pregnancySurface,
                    red,green,blue,alphaA,alphaB,alphaC);return true;
        }
        return delivery!=null&&bodySurface(part)&&delivery.drawMotherTriangle(stack,buffer,light,overlay,points,uv,
                red,green,blue,alphaA,alphaB,alphaC);
    }
}
