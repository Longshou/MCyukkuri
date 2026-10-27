package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.joml.Vector3f;

/** Marisa's own hat turned upside down, its soft crown pressed down (interactions/marisa-hat-vessel, hat_vessel.json):
 *  on the ground until it stands on the squashed crown, afloat a little; its size, where a catcher sets it, and how a
 *  newborn gets in (delivery.py): on a parabola under gravity over the brim, each young in its own place packed by size.
 *  Hat units are the prop's, unscaled (HatBoatEntity.unit() blocks each). */
public final class HatVessel {
    /**
     * Both variants use the same authored hat shape. Aquatic size is applied by the hull unit,
     * so geometry remains available even while the owner is unloaded.
     */
    private static final class Profile {
        final JsonObject data,bowl,delivery;
        final float aquaticScale,freeboard,ownerY,nestHeight,squashNest,squashFloat,ledge,floor;
        final float[] opening,well,brim,anchor;
        final float[][] brims,mask,maskOutline,brimOutline;
        final List<CavitySection> cavity;

        Profile(String resource){
            try(var stream=HatVessel.class.getResourceAsStream(resource)){
                if(stream==null)throw new IllegalStateException("Missing hat vessel metrics "+resource);
                data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            }catch(IOException ex){throw new UncheckedIOException(ex);}
            bowl=data.getAsJsonObject("bowl");delivery=data.getAsJsonObject("delivery");
            aquaticScale=data.get("aquatic_scale").getAsFloat();freeboard=bowl.get("freeboard").getAsFloat();ownerY=bowl.get("owner_y").getAsFloat();
            opening=floats(data.getAsJsonArray("opening"));well=floats(data.getAsJsonArray("well"));brim=floats(data.getAsJsonArray("brim"));
            brims=points(data.getAsJsonArray("brim_rects"));anchor=floats(data.getAsJsonArray("anchor_worn"));
            mask=points(bowl.getAsJsonArray("mask"));maskOutline=bowl.has("mask_outline")?points(bowl.getAsJsonArray("mask_outline")):mask;
            brimOutline=data.has("brim_outline")?points(data.getAsJsonArray("brim_outline")):rectangle(-brim[0],brim[0],-brim[1],brim[1]);
            squashNest=data.getAsJsonObject("squash").get("nest").getAsFloat();squashFloat=data.getAsJsonObject("squash").get("bowl").getAsFloat();
            nestHeight=data.getAsJsonObject("nest").get("height").getAsFloat();ledge=data.get("ledge").getAsFloat();floor=data.get("floor").getAsFloat();
            var sections=new ArrayList<CavitySection>();
            if(data.has("cavity_sections"))for(var value:data.getAsJsonArray("cavity_sections")){
                var section=value.getAsJsonObject();var rect=floats(section.getAsJsonArray("rect"));
                var outline=section.has("outline")?points(section.getAsJsonArray("outline")):rectangle(rect[0],rect[1],rect[2],rect[3]);
                var strips=section.has("strips")?points(section.getAsJsonArray("strips")):new float[][]{rect};
                sections.add(new CavitySection(rect,section.get("depth").getAsDouble(),outline,strips));
            }
            cavity=List.copyOf(sections);
        }
    }
    private static final Profile LAND=new Profile("/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json");
    private static final Profile AQUATIC=LAND;
    private static final JsonObject DATA=LAND.data;
    /** On dry ground (NEST: the crown pressed flat under it) or afloat (a boat, or floated at a bank). */
    public enum Mode {
        NEST(false,false), FLOAT(false,true), AQUATIC_FLOAT(true,true);
        private final boolean aquatic,afloat;
        Mode(boolean aquatic,boolean afloat){this.aquatic=aquatic;this.afloat=afloat;}
        public boolean aquatic(){return aquatic;}
        public boolean afloat(){return afloat;}
    }
    public enum Role { HELPER, SELF }
    public static final float AQUATIC_SCALE=AQUATIC.aquaticScale;
    /** Legacy land-hat values. Geometry tied to a launched hull must use the mode overloads below. */
    public static final float FREEBOARD=LAND.freeboard;
    /** Where the owner rests afloat (on the ledge inside the opening), above the water line. */
    public static final float OWNER_Y=LAND.ownerY;
    /** The hat turned over: the opening and the well below it (x0, x1, z0, z1), the brim's half extents (X across, Z along). */
    public static final float[] OPENING=LAND.opening, WELL=LAND.well, BRIM=LAND.brim;
    private static final float[][] BRIMS=LAND.brims;
    /** How far the crown is pressed along the hat's axis: on the ground (the brim then stands NEST_HEIGHT up) and afloat. */
    public static final float SQUASH_NEST=LAND.squashNest, SQUASH_FLOAT=LAND.squashFloat;
    public static final float NEST_HEIGHT=LAND.nestHeight;
    private static final float LEDGE=LAND.ledge, FLOOR=LAND.floor;
    private record CavitySection(float[] rect,double depth,float[][] outline,float[][] strips){}
    private static final float[] ANCHOR=LAND.anchor;
    private static final float[][] MASK=LAND.mask;
    private static final JsonObject DELIVERY=LAND.delivery;
    /** Minecraft's gravity in model units per tick squared; the fewest and most ticks of a flight; clearance over the brim. */
    public static final double GRAVITY=num("gravity"), CLEAR=num("clear"), OVERSHOOT=num("overshoot"), MAX_SPEED=num("max_speed");
    public static final int TMIN=(int)num("tmin"), TMAX=(int)num("tmax"), HOP_TMIN=(int)num("hop_tmin");
    /** A young's round body (radius, part of its collision width); how close a landing must be to its place to settle. */
    private static final double YOUNG_RADIUS=num("young_radius");
    public static final double SETTLE=num("settle"), OUT_GAP=num("out_gap"), OUT_SPREAD=num("out_spread");
    private static double num(String key){return DELIVERY.get(key).getAsDouble();}
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
    private static float[][] points(JsonArray a){float[][] out=new float[a.size()][];for(int i=0;i<out.length;i++)out[i]=floats(a.get(i).getAsJsonArray());return out;}
    private static float[][] rectangle(float x0,float x1,float z0,float z1){return new float[][]{{x1,z0},{x1,z1},{x0,z1},{x0,z0}};}
    private static Profile profile(Mode mode){return mode!=null&&mode.aquatic()?AQUATIC:LAND;}
    static JsonObject presentation(boolean aquatic){return (aquatic?AQUATIC:LAND).data;}
    /** The worn hat's seat on the head: the larger aquatic hat scales about it. */
    public static Vector3f anchor(){return new Vector3f(ANCHOR[0],ANCHOR[1],ANCHOR[2]);}
    /** The opening at the water line (x, z pairs), where the water is hidden. */
    public static float[][] mask(){return MASK;}
    /** The actual opening below one afloat hull, including its authored rounded polygon when available. */
    public static float[][] mask(Mode mode){return profile(mode).maskOutline;}
    public static float freeboard(Mode mode){return profile(mode).freeboard;}
    public static float ownerY(Mode mode){return profile(mode).ownerY;}
    public static float[] opening(Mode mode){return profile(mode).opening;}
    public static float[] brim(Mode mode){return profile(mode).brim;}
    public static boolean aquatic(YukkuriEntity y){return y.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA;}
    /** Ancestry determines hat size in life and in saved remains; water and boat state never change it. */
    public static float hatMultiplier(int subspeciesFlags){
        if((subspeciesFlags&15)!=SubspeciesLife.Kind.AQUATIC_MARISA.ordinal())return 1;
        return (subspeciesFlags&SubspeciesLife.YOUNG_HAT_FLAG)!=0?YOUNG_HAT:AQUATIC_SCALE;
    }
    /** The aquatic young's hat before its first voyage (hat_deck_layout.json young_hat_multiplier; 1 when absent). */
    private static final float YOUNG_HAT=youngHat();
    private static float youngHat(){
        try(var stream=HatVessel.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_deck_layout.json")){
            if(stream==null)return 1;
            var reader=new com.google.gson.stream.JsonReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
            reader.beginObject();
            while(reader.hasNext()){
                if(reader.nextName().equals("young_hat_multiplier"))return (float)reader.nextDouble();
                reader.skipValue();
            }
        }catch(java.io.IOException|RuntimeException unreadable){/* the land size */}
        return 1;
    }
    /** The size of this Marisa's hat relative to the worn standard. */
    public static float hatScale(YukkuriEntity y){return y.growthScale()*hatMultiplier(y.subspeciesFlags());}
    /** The braid can serve a protected child in the same hat only while their existing close-care boundary holds.
     *  Young shift between the day and night seats, so a rendered braid tip is never a fixed server hit point. */
    public static boolean braidCanServe(YukkuriEntity parent,YukkuriEntity child){
        return parent.getVehicle() instanceof HatBoatEntity boat && child.getVehicle()==boat
                && boat.protects(parent) && boat.protects(child) && parent.hasLineOfSight(child)
                && parent.distanceToSqr(child)<=4;
    }
    /** Where a catcher's braid clips set the hat, relative to the catcher, and where it lies before the mother; on land
     *  also how far beyond the live exit its near brim lies (gap, model units) and how far that brim reaches from it. */
    public record Placement(Mode mode,Vec3 offset,float yaw,Vec3 hatInMother,float hatYawInMother,double gap,double nearBrim){}
    public static Placement placement(boolean aquatic,Role role){
        var profile=aquatic?AQUATIC:LAND;
        var p=profile.data.getAsJsonObject("placements").getAsJsonObject(aquatic?"aquatic":"land").getAsJsonObject(role==Role.HELPER?"helper":"self");
        var o=p.getAsJsonArray("offset");var h=p.getAsJsonObject("hat_in_mother");var a=h.getAsJsonArray("origin");
        return new Placement(aquatic?Mode.AQUATIC_FLOAT:("bowl".equals(p.get("mode").getAsString())?Mode.FLOAT:Mode.NEST),
                new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble()),p.get("yaw").getAsFloat(),
                new Vec3(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()),h.get("yaw").getAsFloat(),
                p.has("gap")?p.get("gap").getAsDouble():0,p.has("near_brim")?p.get("near_brim").getAsDouble():profile.brim[0]);
    }
    /** How far the floated brim slips under the bank. */
    public static final float UNDER_BANK=DATA.getAsJsonObject("float").get("under_bank").getAsFloat();
    /** How far this exact floated brim slips under the bank. */
    public static float underBank(Mode mode){return profile(mode).data.getAsJsonObject("float").get("under_bank").getAsFloat();}

    // ---------------------------------------------------------------- the surface a newborn meets (hat units)
    public static double top(Mode m){var p=profile(m);return m==Mode.NEST?p.nestHeight:p.freeboard;}
    public static double squash(Mode m){var p=profile(m);return m==Mode.NEST?p.squashNest:p.squashFloat;}
    public static double floor(Mode m){var p=profile(m);return top(m)-p.floor*squash(m);}
    /** Largest adjacent tread rise; a deeper multi-tier well must not allow skipping the intervening steps. */
    public static double maxSurfaceStep(Mode mode){
        var profile=profile(mode);if(profile.cavity.isEmpty())return Math.max(profile.ledge,profile.floor-profile.ledge)*squash(mode);
        double previous=0,largest=0;
        for(var section:profile.cavity){largest=Math.max(largest,section.depth()-previous);previous=section.depth();}
        return largest*squash(mode);
    }
    /** A solid cargo envelope in the shared hat frame; seats, routes and crowd movement all respect it. */
    public record ProvisionZone(Vec3 position,double radius,double height){}
    private static boolean in(float[] r,double x,double z){return r[0]<=x&&x<=r[1]&&r[2]<=z&&z<=r[3];}
    /** The exported aquatic outlines are convex and anticlockwise. Boundary contact stays inside. */
    private static boolean inside(float[][] outline,double x,double z){
        if(outline.length<3)return false;
        for(int i=0;i<outline.length;i++){
            var a=outline[i];var b=outline[(i+1)%outline.length];
            if((b[0]-a[0])*(z-a[1])-(b[1]-a[1])*(x-a[0])<-1e-5)return false;
        }
        return true;
    }
    /** The hat's top surface at a point: the well's floor, the ledge round it, the brim; NaN off the hat. */
    public static double surface(Mode m,double x,double z){
        var profile=profile(m);
        for(int i=profile.cavity.size()-1;i>=0;i--){
            var section=profile.cavity.get(i);
            if(inside(section.outline(),x,z))return top(m)-section.depth()*squash(m);
        }
        // Older packs keep the original two-tier data; new aquatic profiles use their exact section polygons above.
        if(profile.cavity.isEmpty()&&in(profile.well,x,z))return floor(m);
        if(profile.cavity.isEmpty()&&in(profile.opening,x,z))return top(m)-profile.ledge*squash(m);
        if(inside(profile.brimOutline,x,z))return top(m);
        return Double.NaN;
    }
    /** The highest surface under a round body of radius r centred at x, z; NaN off the hat. */
    public static double footprint(Mode m,double x,double z,double r){
        double best=Double.NaN;
        for(int i=0;i<9;i++){
            double h=i==8?surface(m,x,z):surface(m,x+r*Math.cos(i*Math.PI/4),z+r*Math.sin(i*Math.PI/4));
            if(!Double.isNaN(h)&&(Double.isNaN(best)||h>best))best=h;
        }
        return best;
    }
    /** How far from x, z along the unit direction dx, dz a body of radius r stays over the hat. */
    public static double extent(Mode m,double x,double z,double dx,double dz,double r){
        double d=0;while(!Double.isNaN(footprint(m,x+dx*d,z+dz*d,r))&&d<64)d+=.125;return d;
    }
    /** A young's round body radius from its collision width (same units). */
    public static double radius(double width){return YOUNG_RADIUS*width;}
    /** Walking raises the full body onto a tread before its shell enters the vertical riser. */
    static double travelSupport(Mode mode,double x,double z,double radius){
        double halfWidth=radius/YOUNG_RADIUS*.5+.025,result=Double.NEGATIVE_INFINITY;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            double y=surface(mode,x+dx*halfWidth,z+dz*halfWidth);
            if(Double.isFinite(y))result=Math.max(result,y);
        }
        return Double.isFinite(result)?result:Double.NaN;
    }
    public static boolean supported(Mode mode,double x,double z,double radius,double height){
        for(int i=0;i<17;i++){
            double px=i==16?x:x+radius*Math.cos(i*Math.PI/8),pz=i==16?z:z+radius*Math.sin(i*Math.PI/8);
            double at=surface(mode,px,pz);
            if(!Double.isFinite(at)||Math.abs(at-height)>1e-4)return false;
        }
        return true;
    }
    // ---------------------------------------------------------------- flights (world, blocks)
    /** Minecraft gravity between two points reached in T ticks: straight across, a parabola up and down. */
    public static Vec3 arc(Vec3 a,Vec3 b,double T,double t){return a.lerp(b,Math.clamp(t/T,0,1)).add(0,GRAVITY/16/2*t*Math.max(0,T-t),0);}
    /** Where a body of radius r at a, bound for b, can leave from: moved toward b, at its height, until it is not inside
     *  the hat (a newborn coming out over the boat's front brim). */
    public static Vec3 takeoff(Vec3 a,Vec3 b,HatBoatEntity hat,double r){
        for(int i=0;i<=40;i++){
            var p=a.lerp(new Vec3(b.x,a.y,b.z),i/40.0);var l=hat.toLocal(p);double h=footprint(hat.mode(),l.x,l.z,r/hat.unit());
            if(Double.isNaN(h)||l.y>=h-1e-6)return p;
        }
        return a;
    }
    /** The fewest ticks (from tmin, and no faster across than MAX_SPEED) for which a body of radius r (blocks) flying
     *  from a to b keeps clear above the hat: over the brim and the ledge by CLEAR, never below the surface it takes off
     *  from or lands on: the whole body over any part higher than both ends, its centre over the rest. */
    public static int solve(Vec3 a,Vec3 b,HatBoatEntity hat,double r,int tmin){
        Mode m=hat.mode();double rr=r/hat.unit();var la=hat.toLocal(a);var lb=hat.toLocal(b);
        tmin=Math.max(tmin,(int)Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/(MAX_SPEED/16)));
        double ea=footprint(m,la.x,la.z,rr),eb=footprint(m,lb.x,lb.z,rr);
        double top=Double.isNaN(ea)?eb:Double.isNaN(eb)?ea:Math.max(ea,eb);
        for(int T=tmin;T<=TMAX;T++){
            boolean ok=true;
            for(int i=1;i<4*T&&ok;i++){
                var p=hat.toLocal(arc(a,b,T,i/4.0));double h=footprint(m,p.x,p.z,rr),need;
                if(Double.isNaN(h))continue;
                if(Double.isNaN(top)||h>top+1e-6)need=h+CLEAR;  // a brim or ledge higher than both ends: the whole body over it
                else{need=surface(m,p.x,p.z);if(Double.isNaN(need))continue;}  // stepping off the ledge, or onto it: what is under it
                if(p.y<need-1e-6)ok=false;
            }
            if(ok)return T;
        }
        return TMAX;
    }
    /** A deliberately planned body curve must actually clear the hull; failing never grants a larger arc. */
    public static boolean clears(List<Vec3> points,HatBoatEntity hat,double radius){
        if(hat==null||points.isEmpty())return true;
        Mode m=hat.mode();double rr=radius/hat.unit();
        var a=hat.toLocal(points.getFirst());var b=hat.toLocal(points.getLast());
        double ea=footprint(m,a.x,a.z,rr),eb=footprint(m,b.x,b.z,rr);
        double top=Double.isNaN(ea)?eb:Double.isNaN(eb)?ea:Math.max(ea,eb);
        for(int i=1;i<points.size()-1;i++){
            var p=hat.toLocal(points.get(i));double h=footprint(m,p.x,p.z,rr);
            if(Double.isNaN(h))continue;
            double need=Double.isNaN(top)||h>top+1e-6?h+CLEAR:surface(m,p.x,p.z);
            if(!Double.isNaN(need)&&p.y<need-1e-6)return false;
        }
        return true;
    }
    /** Where an overstrained push sends a newborn aimed at b (world): on along the same line past the far brim, at the
     *  height of b's ground for the caller to settle. */
    public static Vec3 overshoot(Vec3 a,Vec3 b,HatBoatEntity hat,double r){
        var d=new Vec3(b.x-a.x,0,b.z-a.z);d=d.lengthSqr()<1e-9?new Vec3(0,0,1):d.normalize();
        var lb=hat.toLocal(b);var ld=hat.toLocal(hat.position().add(d)).multiply(1,0,1).normalize();
        double beyond=extent(hat.mode(),lb.x,lb.z,ld.x,ld.z,r/hat.unit())*hat.unit()+r+OVERSHOOT/16;
        return b.add(d.scale(beyond));
    }
    /** Spots (world, at the hat's base height) just past the brim, side by side, on whichever of the hat's four sides
     *  keeps them farthest from the others there (x, z, radius in blocks: the one taking the hat back, the parents); only
     *  toward a direction if given (a floated hat: the bank). */
    public static List<Vec3> outSpots(HatBoatEntity hat,double[] radii,List<double[]> others,Vec3 toward){
        var profile=profile(hat.mode());double cx=(profile.opening[0]+profile.opening[1])/2,cz=(profile.opening[2]+profile.opening[3])/2,r=0;for(double v:radii)r=Math.max(r,v/hat.unit());
        List<double[]> sides=new ArrayList<>(List.of(new double[]{1,0},new double[]{-1,0},new double[]{0,1},new double[]{0,-1}));
        if(toward!=null){var lt=hat.toLocal(hat.position().add(toward.x,0,toward.z));double n=Math.max(1e-6,Math.hypot(lt.x,lt.z));sides=List.of(new double[]{lt.x/n,lt.z/n});}
        List<Vec3> best=null;double bestScore=0;
        for(var d:sides){
            double reach=extent(hat.mode(),cx,cz,d[0],d[1],r)+r+OUT_GAP/16/hat.unit(),width=2*r*OUT_SPREAD;List<Vec3> spots=new ArrayList<>();
            for(int i=0;i<radii.length;i++){double s=(i-(radii.length-1)/2.0)*width;spots.add(hat.toWorld(new Vec3(cx+d[0]*reach-d[1]*s,0,cz+d[1]*reach+d[0]*s)));}
            double score=others.isEmpty()?0:Double.MAX_VALUE;
            for(var s:spots)for(var o:others)score=Math.min(score,Math.hypot(s.x-o[0],s.z-o[1])-o[2]);
            if(best==null||score>bestScore+1e-9){best=spots;bestScore=score;}
        }
        return best;
    }
    /** Which of tos each of froms goes to, the pairing with the least total squared distance across (at most a handful),
     *  so young moving together do not cross one another's way. */
    public static int[] match(List<Vec3> froms,List<Vec3> tos){
        int n=froms.size();int[] best=new int[n],cur=new int[n];double[] bestCost={Double.MAX_VALUE};
        permute(froms,tos,0,cur,new boolean[tos.size()],0,best,bestCost);return best;
    }
    private static void permute(List<Vec3> froms,List<Vec3> tos,int i,int[] cur,boolean[] used,double cost,int[] best,double[] bestCost){
        if(cost>=bestCost[0])return;
        if(i==froms.size()){bestCost[0]=cost;System.arraycopy(cur,0,best,0,cur.length);return;}
        for(int j=0;j<tos.size();j++)if(!used[j]){
            var f=froms.get(i);var t=tos.get(j);used[j]=true;cur[i]=j;
            permute(froms,tos,i+1,cur,used,cost+(f.x-t.x)*(f.x-t.x)+(f.z-t.z)*(f.z-t.z),best,bestCost);used[j]=false;
        }
    }
    private HatVessel(){}
}
