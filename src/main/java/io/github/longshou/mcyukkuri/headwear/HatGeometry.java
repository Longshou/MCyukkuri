package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonArray;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Marisa's own hat turned upside down, its soft crown pressed down (interactions/marisa-hat-vessel, hat_vessel.json):
 *  on the ground until it stands on the squashed crown, and how a newborn gets in (delivery.py): on a parabola under
 *  gravity over the brim, each young in its own place. Hat units are the prop's, unscaled (PlacedHatEntity.unit() blocks each). */
public final class HatGeometry {
    private static final JsonObject DATA=GeometryJson.read("yukkuri_presentation/hat_vessel.json").data();
    /** Species integration hook: choose the form of the worn hat from the owner's synced identity. */
    public static HullProfile wornForm(YukkuriView owner){return HullProfile.of(owner.subspeciesKind().aquatic()
            ?owner.subspeciesKind().hullForm():DATA.getAsJsonObject("forms").get(owner.assetId()).getAsString());}
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    public enum Role { HELPER, SELF }
    private static final float[] ANCHOR=floats(DATA.getAsJsonArray("anchor_worn"));
    private static final JsonObject DELIVERY=DATA.getAsJsonObject("delivery");
    /** Minecraft's gravity in model units per tick squared; the fewest and most ticks of a flight; clearance over the brim. */
    public static final double GRAVITY=num("gravity"), CLEAR=num("clear"), MAX_SPEED=num("max_speed");
    public static final int TMIN=(int)num("tmin"), TMAX=(int)num("tmax");
    /** A young's round body (radius, part of its collision width); how close a landing must be to its place to settle. */
    private static final double YOUNG_RADIUS=num("young_radius");
    private static double num(String key){return DELIVERY.get(key).getAsDouble();}
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
    /** The worn hat's seat on the head: the larger aquatic hat scales about it. */
    public static Vector3f anchor(){return new Vector3f(ANCHOR[0],ANCHOR[1],ANCHOR[2]);}
    /** The authored hat data (hat_vessel.json): its scale table and the afloat physics. */
    public static JsonObject scales(){return DATA.getAsJsonObject("scale");}
    public static JsonObject afloat(){return DATA.getAsJsonObject("afloat");}
    /** Worn-hat clips keep their authored grip, including the larger aquatic hat. */
    public static Vector3f gripWorn(boolean aquatic,boolean self){
        var p=DATA.getAsJsonObject("placements").getAsJsonObject(aquatic?"aquatic":"land").getAsJsonObject(self?"self":"helper").getAsJsonArray("grip_worn");
        return new Vector3f(p.get(0).getAsFloat(),p.get(1).getAsFloat(),p.get(2).getAsFloat());
    }
    /** The worn hat and the forms that sit near it (hat_interactions.json): take-off motion per hatted form, boxes per form. */
    private static final JsonObject WORN=GeometryJson.read("yukkuri_presentation/hat_interactions.json").data();
    /** The worn hat of a hatted form (bone, lift and forward of its take-off motion), or null for a form without one. */
    public static JsonObject worn(String assetId){return WORN.getAsJsonObject("variants").getAsJsonObject(assetId);}
    private static final Map<String,Double> HALF_WIDTHS=new ConcurrentHashMap<>();
    /** Half width of a form's authored boxes, hair and ribbons included (model units); Reimu's when the form has none. */
    public static double formHalfWidth(String assetId){
        return HALF_WIDTHS.computeIfAbsent(assetId,id->{
            var forms=WORN.getAsJsonObject("children");var form=forms.has(id)?forms.getAsJsonObject(id):forms.getAsJsonObject("reimu");
            double half=0;
            for(var part:form.getAsJsonArray("parts")){var b=floats(part.getAsJsonArray());half=Math.max(half,Math.max(Math.abs(b[0]),Math.abs(b[3])));}
            return half;
        });
    }
    /** This Marisa's hat size in every form (HatScale). */
    public static float hatScale(YukkuriEntity y){return HatScale.of(y);}
    /** Where a catcher's braid clips set the hat, relative to the catcher, and where it lies before the mother:
     *  how far beyond the live exit its near brim lies (gap, model units) and how far that brim reaches from it. */
    public record Placement(Vec3 offset,float yaw,float hatYawInMother,double gap){}
    public static Placement placement(Role role,HullProfile profile){
        var p=DATA.getAsJsonObject("placements").getAsJsonObject("land").getAsJsonObject(role==Role.HELPER?"helper":"self");
        var o=p.getAsJsonArray("offset");float yaw=p.get("yaw").getAsFloat();
        var offset=new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble());
        var adjusted=profile.placementOffset(offset.x,offset.z,yaw);
        offset=new Vec3(adjusted.x(),offset.y,adjusted.z());
        return new Placement(offset,yaw,p.getAsJsonObject("hat_in_mother").get("yaw").getAsFloat(),p.get("gap").getAsDouble());
    }

    public static double radius(double width){return YOUNG_RADIUS*width;}
    // ---------------------------------------------------------------- flights (world, blocks)
    /** Minecraft gravity between two points reached in T ticks: straight across, a parabola up and down. */
    public static Vec3 arc(Vec3 a,Vec3 b,double T,double t){return a.lerp(b,Math.clamp(t/T,0,1)).add(0,GRAVITY/16/2*t*Math.max(0,T-t),0);}
    /** The fewest ticks (from tmin, and no faster across than MAX_SPEED) for which a body of radius r (blocks) flying
     *  from a to b keeps clear above the hat: over the brim and the ledge by CLEAR, never below the surface it takes off
     *  from or lands on: the whole body over any part higher than both ends, its centre over the rest. */
    public static int solve(Vec3 a,Vec3 b,PlacedHatEntity hat,double r,int tmin){
        var profile=hat.profile();double rr=r/hat.unit();var la=hat.toLocal(a);var lb=hat.toLocal(b);
        tmin=Math.max(tmin,(int)Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/(MAX_SPEED/16)));
        double ea=profile.footprint(la.x,la.z,rr),eb=profile.footprint(lb.x,lb.z,rr);
        double top=Double.isNaN(ea)?eb:Double.isNaN(eb)?ea:Math.max(ea,eb);
        for(int T=tmin;T<=TMAX;T++){
            boolean ok=true;
            for(int i=1;i<4*T&&ok;i++){
                var p=hat.toLocal(arc(a,b,T,i/4.0));double h=profile.footprint(p.x,p.z,rr),need;
                if(Double.isNaN(h))continue;
                if(Double.isNaN(top)||h>top+1e-6)need=h+CLEAR;  // a brim or ledge higher than both ends: the whole body over it
                else{need=profile.surface(p.x,p.z);if(Double.isNaN(need))continue;}  // stepping off the ledge, or onto it: what is under it
                if(p.y<need-1e-6)ok=false;
            }
            if(ok)return T;
        }
        return TMAX;
    }
    // ---------------------------------------------------------------- the catch (world, blocks)
    /** A hat set down to catch: where, facing (entity yaw), and who set it. */
    public record Pose(Vec3 at,float yaw,Role role,HullProfile profile){}
    /** Where a catcher stands, and her yaw, to set down or take back a hat at a pose. */
    public record Stand(Vec3 at,float yaw){}
    /** Where the hat goes for a mother standing at {@code at} turned to {@code yaw} (hat_vessel.json, the end pose of the braid
     *  clips): a helper presses it upside down just beyond the live outlet, or under the stem's fruit with the opening spanning
     *  every fruit and clear of her body and the helper on the far side; a mother on her own sets it where her own clip puts it. */
    public static Pose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,Role role,Vec3 at,float yaw){
        var form=wornForm(catcher);var place=placement(role,form);
        double k=unit(catcher),body=mother.growthScale()*mother.getScale()/16;
        var forward=Vec3.directionFromRotation(0,yaw);float hatYaw=yaw+place.hatYawInMother();
        if(role==Role.SELF)return new Pose(flat(turned(at,yaw,place.offset().scale(mother.growthScale()/16),mother.getScale()),at),hatYaw,role,form);
        if(brood.kind==FamilyData.BirthKind.STEM){
            // Every fruit's nominal drop point is straight below its socket, whose only fact used here is where it hangs:
            // the hat's long axis follows the stem, so one opening spans the whole line and not merely its mean.
            double low=Double.MAX_VALUE,high=-Double.MAX_VALUE,side=0;int n=Math.max(1,brood.children.size());
            for(int i=0;i<brood.children.size();i++){
                var child=brood.children.get(i);
                var c=FamilyGeometry.childBase(brood.children.size(),i,1,mother.growthScale(),brood.variation,mother.getVariant(),child.variant,GrowthSize.physicalBirth(brood.kind,child));
                low=Math.min(low,c.z);high=Math.max(high,c.z);side+=c.x;
            }
            var under=turned(at,yaw,new Vec3(side/n,0,(low+high)/2),mother.getScale());
            var p=flat(under.add(forward.scale(Math.max(0,stemClearance(mother,catcher)-under.subtract(at).dot(forward)))),at);
            var ahead=new Pose(p,yaw,role,form);var behind=new Pose(p,yaw+180,role,form);
            return stand(ahead,catcher).at().distanceToSqr(at)>=stand(behind,catcher).at().distanceToSqr(at)?ahead:behind;
        }
        int next=0;while(next<brood.children.size()-1&&brood.children.get(next).born)next++;
        double r=radius(LiveBirthGeometry.childDimensions(brood.children.get(next),mother.getScale()).width());
        var outlet=LiveBirthGeometry.position(mother,next,yaw).subtract(mother.position()).add(at);
        return new Pose(flat(outlet.add(forward.scale(r+place.gap()*body+form.reach(0)*k)),at),hatYaw,role,form);
    }
    /** Least distance from the mother's centre to a stem catch hat's centre: the front of her visible body (its model bound is
     *  4.83 of 16 units, 0.4 of her collision width; the rim stays below her widest section) and the opening's near half length
     *  (the hat's long axis follows the stem, so its local z ends are the lips that face her). Her collision box may therefore
     *  overlap the near rim, which the stem catch allows: the coloured brim may lie under her front, the hollow may not. */
    public static double stemClearance(YukkuriEntity mother,YukkuriEntity catcher){
        return mother.getBbWidth()*.4+wornForm(catcher).mouth(Math.PI/2)*unit(catcher);
    }
    /** Blocks a stand beside a hat keeps clear of its brim beyond the body's own footprint. */
    public static final double STAND_MARGIN=Walk.STAND_ARRIVED+.03;
    /** The catcher's place for a hat at this pose: the inverse of the clip's placement, moved out along the same bearing until
     *  her footprint ({@link Occupancy#foot}) clears the real brim by {@link #STAND_MARGIN}. */
    public static Stand stand(Pose pose,YukkuriEntity catcher){
        var authored=authored(pose,catcher);
        return new Stand(outside(pose,unit(catcher),catcher,authored.at(),authored.at().subtract(pose.at()).horizontalDistance()),authored.yaw());
    }
    private static Stand authored(Pose pose,YukkuriEntity catcher){
        var place=placement(pose.role(),pose.profile());float yaw=pose.yaw()-place.yaw();
        var offset=new Vec3(place.offset().x,0,place.offset().z).scale(catcher.growthScale()*catcher.getScale()/16);
        return new Stand(flat(pose.at().subtract(offset.yRot((float)Math.toRadians(180-yaw))),pose.at()),yaw);
    }
    /** The stands for taking a placed hat back: the clip's own stand turned about the hat in eighths (the hat then lies where
     *  the braid reaches for it), each moved out of the brim; index 0 is the clip's own. */
    public static List<Stand> stands(PlacedHatEntity hat,YukkuriEntity catcher){
        var pose=poseOf(hat);var out=new ArrayList<Stand>(8);
        for(int i=0;i<8;i++){
            var turned=authored(new Pose(pose.at(),pose.yaw()+i*45,pose.role(),pose.profile()),catcher);
            out.add(new Stand(outside(pose,hat.unit(),catcher,turned.at(),turned.at().subtract(pose.at()).horizontalDistance()),turned.yaw()));
        }
        return out;
    }
    /** Beside a hat lying at this pose: from its middle toward {@code toward}, at least {@code least} blocks out and far enough
     *  that the body's footprint clears the brim by {@link #STAND_MARGIN} (blocks, at the hat's base height). */
    public static Vec3 outside(Pose pose,double unit,YukkuriEntity body,Vec3 toward,double least){
        var away=toward.subtract(pose.at()).multiply(1,0,1);
        if(away.lengthSqr()<1e-8)away=body.position().subtract(pose.at()).multiply(1,0,1);
        var dir=away.lengthSqr()<1e-8?new Vec3(0,0,1):away.normalize();var local=dir.yRot((float)-Math.toRadians(180-pose.yaw()));
        return pose.at().add(dir.scale(pose.profile().standDistance(local.x,local.z,(Occupancy.foot(body.getBbWidth())+STAND_MARGIN)/unit,least/unit)*unit));
    }
    public static Vec3 outside(PlacedHatEntity hat,YukkuriEntity body,Vec3 toward,double least){return outside(poseOf(hat),hat.unit(),body,toward,least);}
    /** A point (world) lies over the opening of a hat lying at this pose. */
    public static boolean inOpening(Pose pose,double unit,Vec3 at){
        var local=at.subtract(pose.at()).yRot((float)-Math.toRadians(180-pose.yaw())).scale(1/unit);
        return pose.profile().inOpening(local.x,local.z);
    }
    /** A round footprint of {@code radius} blocks at these feet overlaps a hat lying at this pose. */
    public static boolean overlaps(Pose pose,double unit,Vec3 feet,double radius){
        var local=feet.subtract(pose.at()).yRot((float)-Math.toRadians(180-pose.yaw())).scale(1/unit);
        return pose.profile().clearance(local.x,local.z)<(radius+STAND_MARGIN)/unit;
    }
    /** Blocks per hat unit of this Marisa's own hat (HatScale), as it lies set down. */
    public static double unit(YukkuriEntity owner){return hatScale(owner)*owner.getScale()/16;}
    public static Pose poseOf(PlacedHatEntity hat){return new Pose(hat.position(),hat.getYRot(),hat.role(),hat.profile());}
    private static Vec3 turned(Vec3 at,float yaw,Vec3 local,float scale){return at.add(local.yRot((float)Math.toRadians(180-yaw)).scale(scale));}
    private static Vec3 flat(Vec3 p,Vec3 floor){return new Vec3(p.x,floor.y,p.z);}

    /** A body's real envelope (braid_rescue.json): a Marisa's model body bounds turned to her yaw and, while she wears it, her
     *  hat's brim box; anyone else's collision box. The birth launch's first contact and its preview sweep use it. */
    public static List<AABB> envelope(YukkuriEntity y,Vec3 feet){
        var own=y.getBoundingBox().move(feet.subtract(y.position()));
        if(y.getVariant()!=YukkuriVariant.MARISA)return List.of(own);
        double unit=y.growthScale()*y.getScale()/16;
        var out=new ArrayList<AABB>(2);
        out.add(box(feet,y.yBodyRot,vector("body_min").scale(unit),vector("body_max").scale(unit)));
        if(y.life().hasAccessory()){
            double k=hatScale(y)*y.getScale()/16,base=ANCHOR[1]*unit,height=BODY.get("hat_height_model_units").getAsDouble()*k;
            out.add(box(feet,y.yBodyRot,new Vec3(-wornForm(y).brim()[0]*k,base,-wornForm(y).brim()[1]*k),new Vec3(wornForm(y).brim()[0]*k,base+height,wornForm(y).brim()[1]*k)));
        }
        return out;
    }
    private static final JsonObject BODY=GeometryJson.read("yukkuri_presentation/braid_rescue.json").data();
    private static Vec3 vector(String key){var p=BODY.getAsJsonArray(key);return new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());}
    private static AABB box(Vec3 feet,float yaw,Vec3 min,Vec3 max){
        AABB out=null;
        for(int i=0;i<8;i++){
            var p=feet.add(new Vec3((i&1)==0?min.x:max.x,(i&2)==0?min.y:max.y,(i&4)==0?min.z:max.z).yRot((float)Math.toRadians(180-yaw)));
            out=out==null?new AABB(p,p):out.minmax(new AABB(p,p));
        }
        return out;
    }
    private HatGeometry(){}
}
