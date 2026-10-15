package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Marisa's own hat turned upside down, its soft crown pressed down (interactions/marisa-hat-vessel, hat_vessel.json):
 *  on the ground until it stands on the squashed crown, afloat a little; its size, where a catcher sets it, and how a
 *  newborn gets in (delivery.py): on a parabola under gravity over the brim, each young in its own place packed by size.
 *  Hat units are the prop's, unscaled (HatBoatEntity.unit() blocks each). */
public final class HatVessel {
    private static final JsonObject DATA=read();
    private static JsonObject read(){
        try(var stream=HatVessel.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json")){
            if(stream==null)throw new IllegalStateException("Missing hat vessel metrics");
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    /** On dry ground (NEST: the crown pressed flat under it) or afloat (a boat, or floated at a bank). */
    public enum Mode { NEST, FLOAT }
    public enum Role { HELPER, SELF }
    public static final float AQUATIC_SCALE=DATA.get("aquatic_scale").getAsFloat();
    private static final JsonObject BOWL=DATA.getAsJsonObject("bowl");
    public static final float FREEBOARD=BOWL.get("freeboard").getAsFloat();
    /** Where the owner rests afloat (on the ledge inside the opening), above the water line. */
    public static final float OWNER_Y=BOWL.get("owner_y").getAsFloat();
    /** The hat turned over: the opening and the well below it (x0, x1, z0, z1), the brim's half extents (X across, Z along). */
    public static final float[] OPENING=floats(DATA.getAsJsonArray("opening")), WELL=floats(DATA.getAsJsonArray("well")), BRIM=floats(DATA.getAsJsonArray("brim"));
    private static final float[][] BRIMS=points(DATA.getAsJsonArray("brim_rects"));
    /** How far the crown is pressed along the hat's axis: on the ground (the brim then stands NEST_HEIGHT up) and afloat. */
    public static final float SQUASH_NEST=DATA.getAsJsonObject("squash").get("nest").getAsFloat(), SQUASH_FLOAT=DATA.getAsJsonObject("squash").get("bowl").getAsFloat();
    public static final float NEST_HEIGHT=DATA.getAsJsonObject("nest").get("height").getAsFloat();
    private static final float LEDGE=DATA.get("ledge").getAsFloat(), FLOOR=DATA.get("floor").getAsFloat();
    private static final float[] ANCHOR=floats(DATA.getAsJsonArray("anchor_worn"));
    private static final float[][] MASK=points(BOWL.getAsJsonArray("mask"));
    private static final JsonObject DELIVERY=DATA.getAsJsonObject("delivery");
    /** Minecraft's gravity in model units per tick squared; the fewest and most ticks of a flight; clearance over the brim. */
    public static final double GRAVITY=num("gravity"), CLEAR=num("clear"), OVERSHOOT=num("overshoot"), MAX_SPEED=num("max_speed");
    public static final int TMIN=(int)num("tmin"), TMAX=(int)num("tmax"), HOP_TMIN=(int)num("hop_tmin");
    /** A young's round body (radius, part of its collision width); how close a landing must be to its place to settle. */
    private static final double YOUNG_RADIUS=num("young_radius"), OVERLAP=num("overlap"), LEDGE_COST=num("ledge_cost"), PATH_COST=num("path_cost"), STEP=num("step");
    public static final double SETTLE=num("settle"), OUT_GAP=num("out_gap"), OUT_SPREAD=num("out_spread");
    private static double num(String key){return DELIVERY.get(key).getAsDouble();}
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
    private static float[][] points(JsonArray a){float[][] out=new float[a.size()][];for(int i=0;i<out.length;i++)out[i]=floats(a.get(i).getAsJsonArray());return out;}
    /** The worn hat's seat on the head: the larger aquatic hat scales about it. */
    public static org.joml.Vector3f anchor(){return new org.joml.Vector3f(ANCHOR[0],ANCHOR[1],ANCHOR[2]);}
    /** The opening at the water line (x, z pairs), where the water is hidden. */
    public static float[][] mask(){return MASK;}
    public static boolean aquatic(YukkuriEntity y){return y.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA;}
    /** Ancestry determines hat size in life and in saved remains; water and boat state never change it. */
    public static float hatMultiplier(int subspeciesFlags){return (subspeciesFlags&15)==SubspeciesLife.Kind.AQUATIC_MARISA.ordinal()?AQUATIC_SCALE:1;}
    /** The size of this Marisa's hat relative to the worn standard. */
    public static float hatScale(YukkuriEntity y){return y.growthScale()*hatMultiplier(y.subspeciesFlags());}
    /** Where a catcher's braid clips set the hat, relative to the catcher, and where it lies before the mother; on land
     *  also how far beyond the live exit its near brim lies (gap, model units) and how far that brim reaches from it. */
    public record Placement(Mode mode,Vec3 offset,float yaw,Vec3 hatInMother,float hatYawInMother,double gap,double nearBrim){}
    public static Placement placement(boolean aquatic,Role role){
        var p=DATA.getAsJsonObject("placements").getAsJsonObject(aquatic?"aquatic":"land").getAsJsonObject(role==Role.HELPER?"helper":"self");
        var o=p.getAsJsonArray("offset");var h=p.getAsJsonObject("hat_in_mother");var a=h.getAsJsonArray("origin");
        return new Placement("bowl".equals(p.get("mode").getAsString())?Mode.FLOAT:Mode.NEST,
                new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble()),p.get("yaw").getAsFloat(),
                new Vec3(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()),h.get("yaw").getAsFloat(),
                p.has("gap")?p.get("gap").getAsDouble():0,p.has("near_brim")?p.get("near_brim").getAsDouble():BRIM[0]);
    }
    /** How far the floated brim slips under the bank. */
    public static final float UNDER_BANK=DATA.getAsJsonObject("float").get("under_bank").getAsFloat();

    // ---------------------------------------------------------------- the surface a newborn meets (hat units)
    public static double top(Mode m){return m==Mode.NEST?NEST_HEIGHT:FREEBOARD;}
    private static double squash(Mode m){return m==Mode.NEST?SQUASH_NEST:SQUASH_FLOAT;}
    public static double floor(Mode m){return top(m)-FLOOR*squash(m);}
    private static boolean in(float[] r,double x,double z){return r[0]<=x&&x<=r[1]&&r[2]<=z&&z<=r[3];}
    /** The hat's top surface at a point: the well's floor, the ledge round it, the brim; NaN off the hat. */
    public static double surface(Mode m,double x,double z){
        if(in(WELL,x,z))return floor(m);
        if(in(OPENING,x,z))return top(m)-LEDGE*squash(m);
        for(var b:BRIMS)if(in(b,x,z))return top(m);
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
    /** A place (hat units: x, surface y, z) for a young of radius r (hat units) among those taken (x, y, z, r): the free
     *  spot nearest the preferred point (where it landed; null for the region's), on the floor rather than the ledge,
     *  overlapping the others as little as possible and, coming from start (null: anywhere), not across them. Regions:
     *  "catch" (a hat set down), "day" (before the owner afloat), "night" (under her). */
    public static Vec3 place(Mode m,double r,String region,Vec3 prefer,List<double[]> taken,Vec3 start){
        var R=DELIVERY.getAsJsonObject("regions").getAsJsonObject(region);var p=R.getAsJsonArray("prefer");
        double px=prefer!=null?prefer.x:p.get(0).getAsDouble(),pz=prefer!=null?prefer.z:p.get(1).getAsDouble();
        double zmin=R.has("z_min")?R.get("z_min").getAsDouble():-1e9,zmax=R.has("z_max")?R.get("z_max").getAsDouble():1e9;
        double x0=OPENING[0],x1=OPENING[1],z0=OPENING[2],z1=OPENING[3];double[] best=null;
        int nx=(int)Math.floor((x1-x0-2*r)/STEP+1e-9),nz=(int)Math.floor((z1-z0-2*r)/STEP+1e-9);
        for(int i=0;i<=Math.max(0,nx);i++){
            double x=nx>=0?x0+r+i*STEP:(x0+x1)/2;
            for(int j=0;j<=Math.max(0,nz);j++){
                double z=nz>=0?z0+r+j*STEP:(z0+z1)/2;
                if(z<zmin||z>zmax)continue;
                double h=footprint(m,x,z,r),over=0,across=0;
                for(var s:taken){
                    over+=Math.max(0,(r+s[3])*OVERLAP-Math.hypot(x-s[0],z-s[2]));
                    if(start!=null)across+=Math.max(0,(r+s[3])*OVERLAP-segment(s[0],s[2],start.x,start.z,x,z));
                }
                double cost=100*over+PATH_COST*across+(h>floor(m)+1e-6?LEDGE_COST:0)+Math.hypot(x-px,z-pz);
                if(best==null||cost<best[0]-1e-9)best=new double[]{cost,x,h,z};
            }
        }
        return best==null?new Vec3(px,floor(m),pz):new Vec3(best[1],best[2],best[3]);
    }
    /** Distance from a point to the segment a-b (x, z). */
    private static double segment(double px,double pz,double ax,double az,double bx,double bz){
        double dx=bx-ax,dz=bz-az,l=dx*dx+dz*dz,q=l<1e-12?0:Math.clamp(((px-ax)*dx+(pz-az)*dz)/l,0,1);
        return Math.hypot(px-ax-q*dx,pz-az-q*dz);
    }
    /** Places for young of these radii taken one after another (after a day/night change, or before the server says). */
    public static List<Vec3> pack(Mode m,double[] radii,String region){
        List<double[]> taken=new ArrayList<>();List<Vec3> out=new ArrayList<>();
        for(double r:radii){var s=place(m,r,region,null,taken,null);taken.add(new double[]{s.x,s.y,s.z,r});out.add(s);}
        return out;
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
        double cx=(OPENING[0]+OPENING[1])/2,cz=(OPENING[2]+OPENING[3])/2,r=0;for(double v:radii)r=Math.max(r,v/hat.unit());
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
