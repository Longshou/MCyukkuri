package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.joml.Vector3f;

/** Marisa's own hat turned upside down, its soft crown pressed down (interactions/marisa-hat-vessel, hat_vessel.json):
 *  on the ground until it stands on the squashed crown, and how a newborn gets in (delivery.py): on a parabola under
 *  gravity over the brim, each young in its own place. Hat units are the prop's, unscaled (PlacedHatEntity.unit() blocks each). */
public final class HatGeometry {
    /** The authored hat shape. */
    private static final class Profile {
        final JsonObject data,delivery;
        final float nestHeight,squashNest,ledge,floor;
        final float[] opening,well,brim,anchor;
        final float[][] brimOutline;
        final List<CavitySection> cavity;

        Profile(String resource){
            try(var stream=HatGeometry.class.getResourceAsStream(resource)){
                if(stream==null)throw new IllegalStateException("Missing hat vessel metrics "+resource);
                data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            }catch(IOException ex){throw new UncheckedIOException(ex);}
            delivery=data.getAsJsonObject("delivery");
            opening=floats(data.getAsJsonArray("opening"));well=floats(data.getAsJsonArray("well"));brim=floats(data.getAsJsonArray("brim"));
            anchor=floats(data.getAsJsonArray("anchor_worn"));
            brimOutline=data.has("brim_outline")?points(data.getAsJsonArray("brim_outline")):rectangle(-brim[0],brim[0],-brim[1],brim[1]);
            squashNest=data.getAsJsonObject("squash").get("nest").getAsFloat();
            nestHeight=data.getAsJsonObject("nest").get("height").getAsFloat();ledge=data.get("ledge").getAsFloat();floor=data.get("floor").getAsFloat();
            var sections=new ArrayList<CavitySection>();
            if(data.has("cavity_sections"))for(var value:data.getAsJsonArray("cavity_sections")){
                var section=value.getAsJsonObject();var rect=floats(section.getAsJsonArray("rect"));
                var outline=section.has("outline")?points(section.getAsJsonArray("outline")):rectangle(rect[0],rect[1],rect[2],rect[3]);
                sections.add(new CavitySection(rect,section.get("depth").getAsDouble(),outline));
            }
            cavity=List.copyOf(sections);
        }
    }
    private static final Profile LAND=new Profile("/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json");
    private static final JsonObject DATA=LAND.data;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    public enum Role { HELPER, SELF }
    private static final float LEDGE=LAND.ledge, FLOOR=LAND.floor;
    private record CavitySection(float[] rect,double depth,float[][] outline){}
    private static final float[] ANCHOR=LAND.anchor;
    private static final JsonObject DELIVERY=LAND.delivery;
    /** Minecraft's gravity in model units per tick squared; the fewest and most ticks of a flight; clearance over the brim. */
    public static final double GRAVITY=num("gravity"), CLEAR=num("clear"), MAX_SPEED=num("max_speed");
    public static final int TMIN=(int)num("tmin"), TMAX=(int)num("tmax");
    /** A young's round body (radius, part of its collision width); how close a landing must be to its place to settle. */
    private static final double YOUNG_RADIUS=num("young_radius");
    private static double num(String key){return DELIVERY.get(key).getAsDouble();}
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
    private static float[][] points(JsonArray a){float[][] out=new float[a.size()][];for(int i=0;i<out.length;i++)out[i]=floats(a.get(i).getAsJsonArray());return out;}
    private static float[][] rectangle(float x0,float x1,float z0,float z1){return new float[][]{{x1,z0},{x1,z1},{x0,z1},{x0,z0}};}
    /** The worn hat's seat on the head: the larger aquatic hat scales about it. */
    public static Vector3f anchor(){return new Vector3f(ANCHOR[0],ANCHOR[1],ANCHOR[2]);}
    public static float[] opening(){return LAND.opening;}
    public static float[] brim(){return LAND.brim;}
    /** The authored hat data (hat_vessel.json): its scale table and the afloat physics. */
    public static JsonObject data(){return DATA;}
    /** Where the braid holds the worn hat in a placement mode (hat_vessel.json grip_worn, model units): the hat hangs from the
     *  braid tip by this point while the hat_off / hat_on clips carry it. */
    public static Vector3f gripWorn(boolean aquatic,boolean self){
        var g=floats(DATA.getAsJsonObject("placements").getAsJsonObject(aquatic?"aquatic":"land").getAsJsonObject(self?"self":"helper").getAsJsonArray("grip_worn"));
        return new Vector3f(g[0],g[1],g[2]);
    }
    /** The worn hat and the forms that sit near it (hat_interactions.json): take-off motion per hatted form, boxes per form. */
    private static final JsonObject WORN=read("/assets/mcyukkuri/yukkuri_presentation/hat_interactions.json");
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
    public record Placement(Vec3 offset,float yaw,Vec3 hatInMother,float hatYawInMother,double gap,double nearBrim){}
    public static Placement placement(Role role){
        var p=LAND.data.getAsJsonObject("placements").getAsJsonObject("land").getAsJsonObject(role==Role.HELPER?"helper":"self");
        var o=p.getAsJsonArray("offset");var h=p.getAsJsonObject("hat_in_mother");var a=h.getAsJsonArray("origin");
        return new Placement(new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble()),p.get("yaw").getAsFloat(),
                new Vec3(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()),h.get("yaw").getAsFloat(),
                p.has("gap")?p.get("gap").getAsDouble():0,p.has("near_brim")?p.get("near_brim").getAsDouble():LAND.brim[0]);
    }

    // ---------------------------------------------------------------- the surface a newborn meets (hat units)
    public static double top(){return LAND.nestHeight;}
    public static double squash(){return LAND.squashNest;}
    public static double floor(){return top()-LAND.floor*squash();}
    private static boolean in(float[] r,double x,double z){return r[0]<=x&&x<=r[1]&&r[2]<=z&&z<=r[3];}
    /** The exported outlines are convex and anticlockwise. Boundary contact stays inside. */
    private static boolean inside(float[][] outline,double x,double z){
        if(outline.length<3)return false;
        for(int i=0;i<outline.length;i++){
            var a=outline[i];var b=outline[(i+1)%outline.length];
            if((b[0]-a[0])*(z-a[1])-(b[1]-a[1])*(x-a[0])<-1e-5)return false;
        }
        return true;
    }
    /** The hat's top surface at a point: the well's floor, the ledge round it, the brim; NaN off the hat. */
    public static double surface(double x,double z){
        for(int i=LAND.cavity.size()-1;i>=0;i--){
            var section=LAND.cavity.get(i);
            if(inside(section.outline(),x,z))return top()-section.depth()*squash();
        }
        // Older packs keep the original two-tier data; new profiles use their exact section polygons above.
        if(LAND.cavity.isEmpty()&&in(LAND.well,x,z))return floor();
        if(LAND.cavity.isEmpty()&&in(LAND.opening,x,z))return top()-LAND.ledge*squash();
        if(inside(LAND.brimOutline,x,z))return top();
        return Double.NaN;
    }
    /** The highest surface under a round body of radius r centred at x, z; NaN off the hat. */
    public static double footprint(double x,double z,double r){
        double best=Double.NaN;
        for(int i=0;i<9;i++){
            double h=i==8?surface(x,z):surface(x+r*Math.cos(i*Math.PI/4),z+r*Math.sin(i*Math.PI/4));
            if(!Double.isNaN(h)&&(Double.isNaN(best)||h>best))best=h;
        }
        return best;
    }
    /** How far from x, z along the unit direction dx, dz a body of radius r stays over the hat. */
    public static double extent(double x,double z,double dx,double dz,double r){
        double d=0;while(!Double.isNaN(footprint(x+dx*d,z+dz*d,r))&&d<64)d+=.125;return d;
    }
    /** A young's round body radius from its collision width (same units). */
    public static double radius(double width){return YOUNG_RADIUS*width;}
    /** Walking raises the full body onto a tread before its shell enters the vertical riser. */
    public static double travelSupport(double x,double z,double radius){
        double halfWidth=radius/YOUNG_RADIUS*.5+.025,result=Double.NEGATIVE_INFINITY;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            double y=surface(x+dx*halfWidth,z+dz*halfWidth);
            if(Double.isFinite(y))result=Math.max(result,y);
        }
        return Double.isFinite(result)?result:Double.NaN;
    }
    public static boolean supported(double x,double z,double radius,double height){
        for(int i=0;i<17;i++){
            double px=i==16?x:x+radius*Math.cos(i*Math.PI/8),pz=i==16?z:z+radius*Math.sin(i*Math.PI/8);
            double at=surface(px,pz);
            if(!Double.isFinite(at)||Math.abs(at-height)>1e-4)return false;
        }
        return true;
    }
    // ---------------------------------------------------------------- flights (world, blocks)
    /** Minecraft gravity between two points reached in T ticks: straight across, a parabola up and down. */
    public static Vec3 arc(Vec3 a,Vec3 b,double T,double t){return a.lerp(b,Math.clamp(t/T,0,1)).add(0,GRAVITY/16/2*t*Math.max(0,T-t),0);}
    /** The fewest ticks (from tmin, and no faster across than MAX_SPEED) for which a body of radius r (blocks) flying
     *  from a to b keeps clear above the hat: over the brim and the ledge by CLEAR, never below the surface it takes off
     *  from or lands on: the whole body over any part higher than both ends, its centre over the rest. */
    public static int solve(Vec3 a,Vec3 b,PlacedHatEntity hat,double r,int tmin){
        double rr=r/hat.unit();var la=hat.toLocal(a);var lb=hat.toLocal(b);
        tmin=Math.max(tmin,(int)Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/(MAX_SPEED/16)));
        double ea=footprint(la.x,la.z,rr),eb=footprint(lb.x,lb.z,rr);
        double top=Double.isNaN(ea)?eb:Double.isNaN(eb)?ea:Math.max(ea,eb);
        for(int T=tmin;T<=TMAX;T++){
            boolean ok=true;
            for(int i=1;i<4*T&&ok;i++){
                var p=hat.toLocal(arc(a,b,T,i/4.0));double h=footprint(p.x,p.z,rr),need;
                if(Double.isNaN(h))continue;
                if(Double.isNaN(top)||h>top+1e-6)need=h+CLEAR;  // a brim or ledge higher than both ends: the whole body over it
                else{need=surface(p.x,p.z);if(Double.isNaN(need))continue;}  // stepping off the ledge, or onto it: what is under it
                if(p.y<need-1e-6)ok=false;
            }
            if(ok)return T;
        }
        return TMAX;
    }
    // ---------------------------------------------------------------- the catch (world, blocks)
    /** A hat set down to catch: where, facing (entity yaw), and who set it. */
    public record Pose(Vec3 at,float yaw,Role role){}
    /** Where a catcher stands, and her yaw, to set down or take back a hat at a pose. */
    public record Stand(Vec3 at,float yaw){}
    /** Where the hat goes for a mother standing at {@code at} turned to {@code yaw} (hat_vessel.json, the end pose of the braid
     *  clips): a helper presses it upside down just beyond the live outlet, or under the stem's fruit with the opening clear of
     *  her body and the helper on the far side; a mother on her own sets it where her own clip puts it. */
    public static Pose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,Role role,Vec3 at,float yaw){
        var place=placement(role);
        double k=hatScale(catcher)*catcher.getScale()/16,body=mother.growthScale()*mother.getScale()/16;
        var forward=Vec3.directionFromRotation(0,yaw);float hatYaw=yaw+place.hatYawInMother();
        if(role==Role.SELF)return new Pose(flat(turned(at,yaw,place.offset().scale(mother.growthScale()/16),mother.getScale()),at),hatYaw,role);
        if(brood.kind==FamilyData.BirthKind.STEM){
            double sx=0,sz=0;int n=Math.max(1,brood.children.size());
            for(int i=0;i<brood.children.size();i++){
                var child=brood.children.get(i);
                var c=FamilyGeometry.childBase(brood.children.size(),i,1,mother.growthScale(),brood.variation,mother.getVariant(),child.variant,GrowthSize.physicalBirth(brood.kind,child));
                sx+=c.x;sz+=c.z;
            }
            var under=turned(at,yaw,new Vec3(sx/n,0,sz/n),mother.getScale());
            var p=flat(under.add(forward.scale(Math.max(0,stemClearance(mother,catcher)-under.subtract(at).dot(forward)))),at);
            var ahead=new Pose(p,yaw,role);var behind=new Pose(p,yaw+180,role);
            return stand(ahead,catcher).at().distanceToSqr(at)>=stand(behind,catcher).at().distanceToSqr(at)?ahead:behind;
        }
        int next=0;while(next<brood.children.size()-1&&brood.children.get(next).born)next++;
        double r=radius(LiveBirthGeometry.childDimensions(brood.children.get(next),mother.getScale()).width());
        var outlet=LiveBirthGeometry.position(mother,next,yaw).subtract(mother.position()).add(at);
        return new Pose(flat(outlet.add(forward.scale(r+place.gap()*body+place.nearBrim()*k)),at),hatYaw,role);
    }
    /** Least distance from the mother's centre to a stem catch hat's centre: her half width and the opening's half length. */
    public static double stemClearance(YukkuriEntity mother,YukkuriEntity catcher){
        return mother.getBbWidth()*.5+opening()[3]*hatScale(catcher)*catcher.getScale()/16;
    }
    /** The catcher's place for a hat at this pose: the inverse of the clip's placement. */
    public static Stand stand(Pose pose,YukkuriEntity catcher){
        var place=placement(pose.role());float yaw=pose.yaw()-place.yaw();
        var offset=new Vec3(place.offset().x,0,place.offset().z).scale(catcher.growthScale()*catcher.getScale()/16);
        return new Stand(flat(pose.at().subtract(offset.yRot((float)Math.toRadians(180-yaw))),pose.at()),yaw);
    }
    public static Pose poseOf(PlacedHatEntity hat){return new Pose(hat.position(),hat.getYRot(),hat.role());}
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
            out.add(box(feet,y.yBodyRot,new Vec3(-LAND.brim[0]*k,base,-LAND.brim[1]*k),new Vec3(LAND.brim[0]*k,base+height,LAND.brim[1]*k)));
        }
        return out;
    }
    private static final JsonObject BODY=read("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json");
    private static Vec3 vector(String key){var p=BODY.getAsJsonArray(key);return new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());}
    private static AABB box(Vec3 feet,float yaw,Vec3 min,Vec3 max){
        AABB out=null;
        for(int i=0;i<8;i++){
            var p=feet.add(new Vec3((i&1)==0?min.x:max.x,(i&2)==0?min.y:max.y,(i&4)==0?min.z:max.z).yRot((float)Math.toRadians(180-yaw)));
            out=out==null?new AABB(p,p):out.minmax(new AABB(p,p));
        }
        return out;
    }
    private static JsonObject read(String resource){
        try(var stream=HatGeometry.class.getResourceAsStream(resource)){
            if(stream==null)throw new IllegalStateException("Missing "+resource);
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    private HatGeometry(){}
}
