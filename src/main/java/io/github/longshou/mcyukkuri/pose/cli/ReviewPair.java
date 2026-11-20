package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.motor.geom.ContactStand;
import io.github.longshou.mcyukkuri.pose.Overlays;
import io.github.longshou.mcyukkuri.pose.ReproductionPose;
import io.github.longshou.mcyukkuri.pose.Rig;
import io.github.longshou.mcyukkuri.pose.SideSurface;
import io.github.longshou.mcyukkuri.pose.WallYield;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Stage two neutral, uninjured game actors on a level floor. All stand distances are resolved from their bodies. */
final class ReviewPair {
    private static final float PI=(float)Math.PI;
    private final Actor first, second;
    private final float time, progress;
    private final JsonObject adultStates;

    record Anatomy(String part,float amount,ReproductionPose.MamuPlacement rear) {}

    private record Actor(RenderPose pose,JsonObject data,JsonObject request,float scale) {
        Actor(Path assets, RenderPose pose, JsonObject request) throws IOException {
            this(pose,pose.actor(assets),request,request.get("scale").getAsFloat());
        }
        double width() {return data.get("width").getAsDouble()*scale;}
        float[] anchor() {return Rig.floats(data.getAsJsonArray("mouth"));}
        JsonObject organ(JsonObject states) {
            return states.getAsJsonObject("organ_sites").getAsJsonObject(pose.data.get("id").getAsString());
        }
        RenderPose.Drawn draw(Anatomy anatomy) {return pose.draw(request,null,anatomy);}
    }

    private ReviewPair(Actor first, Actor second, float time, float progress, JsonObject adultStates) {
        this.first=first;
        this.second=second;
        this.time=time;
        this.progress=progress;
        this.adultStates=adultStates;
    }

    static JsonObject render(Path assets,RenderPose first,RenderPose second,JsonObject request,JsonObject peer,
                             JsonObject stage) throws IOException {
        String kind=stage.get("kind").getAsString();
        var a=new Actor(assets,first,request);
        var b=new Actor(assets,second,peer);
        if(kind.startsWith("feed")) return feeding(a,b,kind,stage);
        return new ReviewPair(a,b,stage.get("elapsed").getAsFloat(),stage.get("progress").getAsFloat(),
                ReviewActor.read(assets,"adult_states")).sample(kind);
    }

    private static JsonObject feeding(Actor parent,Actor child,String kind,JsonObject stage) {
        float distance=(float)(16*ContactStand.feeding(parent.width(),child.width()));
        float childYaw=PI;
        if(kind.equals("feed-approach")) distance*=2-stage.get("progress").getAsFloat();
        if(kind.equals("feed-offer")) {
            var target=new Vector3f(child.anchor()).mul(child.scale()).rotateY(childYaw)
                    .sub(0,0,distance).div(parent.scale());
            var care=new JsonObject();
            care.add("anchor",parent.data().get("mouth"));
            care.add("target",OfflineModel.array(new float[]{target.x,target.y,target.z}));
            // One second of admitted offer grows the exact same blend as CARE_PROGRESS.
            care.addProperty("blend",Overlays.careBlend(stage.get("elapsed").getAsFloat()));
            parent.request().add("care",care);
        }
        return positioned(parent.draw(null),child.draw(null),
                new Matrix4f().translate(0,0,distance),new Matrix4f().rotateY(childYaw));
    }

    private JsonObject sample(String kind) {
        double tangent=ContactStand.tangent(first.width(),second.width(),0,1);
        return switch(kind) {
            case "court-greet" -> free(0,PI,0,tangent*2,0,null);
            case "court-aside" -> free(PI/2*progress,PI*(1-progress),0,
                    interpolate(tangent*2,rubStand(0),progress),0,null);
            case "court-rear" -> free(PI/2*(1+progress),PI/2*(float)Math.sin(PI*progress),0,
                    interpolate(rubStand(first.pose().clips.get("snuggle_left").duration),courtStand(tangent),progress),0,null);
            case "court-contact" -> rear(courtStand(tangent),0,true,false);
            case "court-release" -> rear(courtStand(tangent)+tangent*progress,0,true,true);
            case "pin-warn" -> free(PI,0,0,tangent*2,0,new Anatomy("peni",extension(time),null));
            case "pin-chase" -> free(PI,0,0,tangent*(2-progress),-tangent*progress,new Anatomy("peni",1,null));
            case "pin-contact" -> rear(tangent,-tangent,false,false);
            case "pin-release" -> rear(tangent*(1+progress),-tangent,false,true);
            default -> throw new IllegalArgumentException("Unknown performance stance: "+kind);
        };
    }

    private double courtStand(double tangent) {
        // Courtship aims at its rear point. On level ground the square footprints can stop at tangency.
        return Math.max(ContactStand.courtRear(first.width(),second.width()),tangent);
    }

    private double rubStand(float seconds) {
        int tick=(int)(seconds*20);
        return SideSurface.meet(first.pose().cheek(false,tick),first.scale()/16d,
                second.pose().cheek(true,tick),second.scale()/16d,0,0).separation();
    }

    private JsonObject free(float bearing,float yaw,float otherYaw,double distance,double travel,Anatomy anatomy) {
        var a=first.draw(anatomy);
        var b=second.draw(null);
        float x=(float)Math.sin(bearing),z=-(float)Math.cos(bearing);
        var rotation=new Matrix4f().rotateY(yaw);
        var peerRotation=new Matrix4f().rotateY(otherYaw);
        // Route around the solid and worn envelopes before beginning reciprocal contact.
        double feet=ContactStand.tangent(first.width(),second.width(),x,z);
        double visible=reach(first,a.vertices(),rotation,-x,-z,false)+reach(second,b.vertices(),peerRotation,x,z,false);
        float spacing=(float)Math.max(distance,Math.max(feet,visible))*16;
        return positioned(a,b,rotation.setTranslation(x*spacing,0,z*spacing+(float)travel*16),
                peerRotation.setTranslation(0,0,(float)travel*16));
    }

    private JsonObject rear(double distance,double travel,boolean court,boolean release) {
        float amount=release?Math.clamp(1-time*20/ReproductionPose.RETRACT_TICKS,0,1)
                : court?extension(time):1;
        float peerAmount=release?amount:extension(time);
        var a=first.draw(new Anatomy("peni",amount,null));
        var b=second.draw(new Anatomy("mamu",peerAmount,rearPlacement()));
        var mainPose=release?new Matrix4f():adultPose(first,court?"pair_peni":"act");
        var peerPose=release?new Matrix4f():adultPose(second,court?"pair_mamu":"pinned");
        double rest=reach(first,first.pose().rest,new Matrix4f().scale(first.scale()),0,-1,true)
                +reach(second,second.pose().rest,new Matrix4f().scale(second.scale()),0,1,true);
        double front=reach(first,a.vertices(),mainPose,0,-1,true);
        double back=reach(second,b.vertices(),peerPose,0,1,true);
        double close=release?0:ReproductionPose.close(distance,rest,front+back)*ReproductionPose.mix(time*20,true);
        if(court) {
            double ornaments=ornaments(first,a.vertices(),mainPose,-1)+ornaments(second,b.vertices(),peerPose,1);
            close=Math.min(close,ReproductionPose.ornamentLimit(distance,ornaments));
        }
        mainPose.m32((float)((travel+distance-close)*16));
        peerPose.m32((float)((travel+close)*16));
        place(a.vertices(),mainPose);
        place(b.vertices(),peerPose);
        if(!court) {
            // Use the body's separating face for loose hair and headwear. The collision-box stand is unchanged.
            double drawDistance=distance-2*close;
            float low=0,high=0;
            for(var vertices:List.of(a.vertices(),b.vertices()))for(int i=1;i<vertices.length;i+=8) {
                low=Math.min(low,vertices[i]/16); high=Math.max(high,vertices[i]/16);
            }
            press(first,a.vertices(),mainPose.m32(),WallYield.partnerFace(0,-1,drawDistance,front,back,low,high));
            press(second,b.vertices(),peerPose.m32(),WallYield.partnerFace(0,1,drawDistance,back,front,low,high));
        }
        return combine(a,b,0);
    }

    private ReproductionPose.MamuPlacement rearPlacement() {
        var site=second.organ(adultStates);
        var values=site.getAsJsonArray("rear");
        float[][] rear=new float[values.size()][];
        for(int i=0;i<rear.length;i++)rear[i]=Rig.floats(values.get(i).getAsJsonArray());
        float target=first.organ(adultStates).get("meet").getAsFloat()*first.scale()/second.scale();
        return ReproductionPose.rearPlacement(site.get("turn").getAsBoolean(),site.get("x").getAsFloat(),
                site.get("y").getAsFloat(),site.get("outer").getAsFloat(),site.get("proud").getAsFloat(),rear,target);
    }

    private Matrix4f adultPose(Actor actor,String branch) {
        // Each configured contact branch holds a rigid cue over the authored clip.
        float[] key=Rig.floats(adultStates.getAsJsonArray(branch).get(0).getAsJsonArray());
        var transform=Overlays.presentation(Arrays.copyOfRange(key,1,key.length));
        float floor=Float.POSITIVE_INFINITY;
        for(var raw:actor.pose().data.getAsJsonArray("parts")) {
            var part=raw.getAsJsonObject();
            if(!part.get("anatomy").getAsString().isEmpty()||part.get("name").getAsString().contains("puff")) continue;
            int start=part.get("start").getAsInt()*8,end=start+part.get("count").getAsInt()*8;
            var vertices=actor.pose().rest;
            for(int i=start;i<end;i+=8)
                floor=Math.min(floor,transform.m01()*vertices[i]+transform.m11()*vertices[i+1]+transform.m21()*vertices[i+2]);
        }
        return transform.m31(Math.max(0,ReproductionPose.GROUND_MARGIN*16-floor)*actor.scale());
    }

    private static double reach(Actor actor,float[] vertices,Matrix4f pose,float x,float z,boolean bodyOnly) {
        double reach=0;
        for(var raw:actor.pose().data.getAsJsonArray("parts")) {
            var part=raw.getAsJsonObject();
            String name=part.get("name").getAsString();
            if(bodyOnly?!OfflineModel.group(name).equals("body"):name.startsWith("face_")||!part.get("anatomy").getAsString().isEmpty())
                continue;
            reach=Math.max(reach,project(vertices,pose,part,x,z));
        }
        return reach;
    }

    private static double ornaments(Actor actor,float[] vertices,Matrix4f pose,float z) {
        double reach=0;
        for(var raw:actor.pose().data.getAsJsonArray("parts")) {
            var part=raw.getAsJsonObject();
            String name=part.get("name").getAsString();
            for(var prefix:actor.data().getAsJsonArray("paperAccessories")) if(name.startsWith(prefix.getAsString())) {
                reach=Math.max(reach,project(vertices,pose,part,0,z));
                break;
            }
        }
        return reach;
    }

    private static double project(float[] vertices,Matrix4f pose,JsonObject part,float x,float z) {
        double reach=0;
        int start=part.get("start").getAsInt()*8,end=start+part.get("count").getAsInt()*8;
        var point=new Vector3f();
        for(int i=start;i<end;i+=8) {
            pose.transformPosition(point.set(vertices[i],vertices[i+1],vertices[i+2]));
            reach=Math.max(reach,point.x*x+point.z*z);
        }
        return reach/16;
    }

    private static double interpolate(double a,double b,float q) { return a+(b-a)*q; }
    private static float extension(float time) {return ReproductionPose.extend(time*20);}

    private static void press(Actor actor,float[] vertices,float stand,WallYield.Face face) {
        var faces=List.of(face);
        for(var raw:actor.pose().data.getAsJsonArray("parts")) {
            var part=raw.getAsJsonObject();
            if(!WallYield.loose(part.get("name").getAsString())) continue;
            int start=part.get("start").getAsInt()*8,end=start+part.get("count").getAsInt()*8;
            for(int i=start;i<end;i+=8) {
                float z=vertices[i+2]-stand;
                float keep=WallYield.press(faces,vertices[i]/16,vertices[i+1]/16,z/16);
                vertices[i]*=keep;
                vertices[i+2]=stand+z*keep;
            }
        }
    }

    static void place(float[] vertices,Matrix4f pose) {
        var point=new Vector3f();
        var normal=new Matrix4f(pose).invert().transpose();
        for(int i=0;i<vertices.length;i+=8) {
            pose.transformPosition(point.set(vertices[i],vertices[i+1],vertices[i+2]));
            vertices[i]=point.x; vertices[i+1]=point.y; vertices[i+2]=point.z;
            normal.transformDirection(point.set(vertices[i+5],vertices[i+6],vertices[i+7]));
            vertices[i+5]=point.x; vertices[i+6]=point.y; vertices[i+7]=point.z;
        }
    }

    private static JsonObject positioned(RenderPose.Drawn first,RenderPose.Drawn second,Matrix4f a,Matrix4f b) {
        place(first.vertices(),a);
        place(second.vertices(),b);
        return combine(first,second,0);
    }

    static JsonObject combine(RenderPose.Drawn first,RenderPose.Drawn second,float spacing) {
        var result=first.encode();
        result.add("partnerVertices",second.encode().get("vertices"));
        result.addProperty("partnerSpacing",spacing);
        return result;
    }
}
