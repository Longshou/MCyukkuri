package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import io.github.longshou.mcyukkuri.family.RemainsStem;
import java.util.function.IntFunction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws unborn children only. Detached children are real server entities and are never drawn twice. */
final class FamilyRenderer {
    private static final ResourceLocation WHITE=ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    // Hanging fruit swing like pendulums about their sockets (project design), only from real motion: a sway that grows
    // with the mother's actual travel (walking, hopping) and a lag opposite it. A still mother's fruit hang plumb; there is
    // no idle sway. Display only; the fruit's rest point is FamilyGeometry.childBase.
    private static final float SWAY_PERIOD_TICKS=46,SWAY_FORE=.13f,SWAY_SIDE=.05f,TRAVEL_LAG=1.6f,MAX_SWING=.32f;
    static void render(YukkuriView mother,float partialTick,PoseStack pose,MultiBufferSource buffers,int light) {
        int count=mother.broodCount();if(!mother.isAlive()||count<1||mother.profile().houseBody())return;
        float progress=mother.broodProgress(partialTick),scale=mother.growthScale(),variation=mother.broodVariation();
        int types=0;for(int i=0;i<count;i++)types|=mother.broodVariant(i).ordinal()<<(i*4);
        Vec3 travel=mother.position().subtract(mother.getPosition(0));
        // The solid rim is the minimum height of a hanging fruit while the hull tilts.
        var hat=mother.vehicleHat();
        float floor=hat==null?Float.NEGATIVE_INFINITY:(float)((hat.getPosition(partialTick).y
                +hat.profile().rimY()*hat.hullScale()/16-mother.getPosition(partialTick).y)/mother.getScale());
        renderStem(count,progress,scale,variation,mother.getVariant(),mother.broodBornMask(),types,mother.hasSpentStem(),
                mother.ageTicks()+partialTick,travel,1,floor,mother::birthChildScale,pose,buffers,light);
    }
    /** {@code sway} scales the motion pendulum (0 keeps a planted stem plumb); {@code travel} is the mother's last tick of movement. */
    static void renderStem(int count,float progress,float scale,float variation,YukkuriVariant parent,int born,int types,boolean spent,
                           float ticks,Vec3 travel,float sway,float floor,IntFunction<Float> birthSizes,PoseStack pose,MultiBufferSource buffers,int light){
        if(count<1||!Float.isFinite(progress)||!spent&&progress<=0)return;
        // The visible stem grows only from actual gestation progress, never from a fresh reservation: the full stalk is
        // revealed from the root, pedicels follow its tip, and fruit set once both are grown.
        var stalk=Stalk.of(count,spent?1:FamilyGeometry.extension(progress),scale,variation,parent);
        int[] color=spent&&born==(1<<count)-1?new int[]{157,135,79}:new int[]{66,186,57};
        sweep(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE)),stalk.points(),stalk.radii(),light,color);
        var arc=FamilyGeometry.arc(count,scale,variation,parent);
        var linear=pose.last().pose().get3x3(new Matrix3f());
        for(int i=0;i<count;i++) {
            if((born&(1<<i))!=0)continue;
            var variants=YukkuriVariant.values();
            var child=variants[Math.min(variants.length-1,(types>>(i*4))&15)];
            Vec3 socket=FamilyGeometry.socket(count,i,scale,variation,parent);
            float grown=spent?1:FamilyGeometry.pedicelGrowth(progress,stalk.fraction(socket));
            if(grown<=0)continue;
            float birthScale=birthSizes.apply(i),childScale=FamilyGeometry.fruitScale(progress,count,i,birthScale);
            Vec3 anchor=FamilyGeometry.hangingAnchor(child,childScale);
            Vec3 hangPoint=socket.add(FamilyGeometry.hangOffset(count,i,scale).scale(grown));
            // The lower part hangs along world gravity (swung by sway and lag), whatever tilt the mother's root carries.
            Vector3f down=hang(linear,ticks,travel,i,sway);
            double length=childScale>0?FamilyGeometry.pedicel(scale,child,childScale):FamilyGeometry.pendant(scale)*grown;
            if(Float.isFinite(floor)){
                float worldSocketY=linear.transform(new Vector3f((float)hangPoint.x,(float)hangPoint.y,(float)hangPoint.z)).y;
                float worldDown=linear.transform(new Vector3f(down)).y;
                length=Math.min(length,Math.max(0,(-floor+worldSocketY-anchor.y*linear.m11())/-worldDown));
            }
            Vec3 hanging=new Vec3(down.x,down.y,down.z),head=hangPoint.add(hanging.scale(length));
            double[] radius=pedicelRadii(count,i,variation,scale,arc,socket,grown);
            // One continuous twig: it leaves the stalk outward and a little downward, then bends smoothly into the drop.
            double drop=FamilyGeometry.branchDrop();Vec3 heading=FamilyGeometry.branchHeading(count,i);
            Vec3 leave=heading.scale(Math.cos(drop)).add(0,-Math.sin(drop),0);
            double reach=hangPoint.subtract(socket).horizontalDistance();
            sweep(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE)),
                    bezier(socket,socket.add(leave.scale(reach*.75)),head.subtract(hanging.scale(length*.6)),head,PEDICEL_SEGMENTS),
                    radius[0],radius[1],light,color);
            if(childScale<=0)continue;
            pose.pushPose();pose.translate(head.x,head.y,head.z);pose.mulPose(new Quaternionf().rotationTo(0,-1,0,down.x,down.y,down.z));
            pose.translate(-anchor.x,-anchor.y,-anchor.z);pose.scale(childScale,childScale,childScale);
            var skin=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+child.assetId()+"/sleep.png")));
            YukkuriModel.get(child.assetId()).render("idle",0,pose,skin,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
    /** The withered stem a dead mother keeps (art/props/family-life/stem_growth.py remains_pose): the part grown by death,
     *  rooted at {@code root} on the corpse, shrivelled, browned and wilting along its length; pedicels limp and splayed,
     *  unripe fruit shrunk at their death size. */
    static void renderRemains(RemainsStem stem,Vec3 root,float roll,boolean folded,int rot,PoseStack pose,MultiBufferSource buffers,int light) {
        var look=RemainsAssets.stem();
        if(look==null)return;
        float p=stem.progress(),scale=stem.growthScale(),unit=stem.entityScale(),variation=stem.variation();
        int count=stem.count();
        var stalk=Stalk.of(count,FamilyGeometry.extension(p),scale,variation,stem.mother());
        var origin=FamilyGeometry.root(stem.mother()).scale(scale);
        float turn=(float)-Math.toRadians(roll);
        var relative=stalk.points().stream().map(q->q.subtract(origin).scale(unit).zRot(turn)).toList();
        int n=relative.size();
        double[] along=new double[n],radii=new double[n];
        for(int k=0;k<n;k++){along[k]=stalk.along()[k]*unit;radii[k]=stalk.radii()[k]*unit*look.thickness();}
        double total=stalk.length()*unit;
        var curve=folded?fold(root,relative,along,total)
                :wilt(root,relative,along,total,radii,look.droop()*FamilyGeometry.length(count,variation),look.steepest());
        int[] colour=look.colour();
        if(rot>0)colour=new int[]{(colour[0]+105)/2,(colour[1]+105)/2,(colour[2]+105)/2};
        var material=buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        sweep(pose,material,curve,radii,light,colour);
        var arc=FamilyGeometry.arc(count,scale,variation,stem.mother());
        Vec3 shrivel=look.shrivel();
        for(int i=0;i<count;i++){
            if(!stem.attached(i))continue;
            Vec3 living=FamilyGeometry.socket(count,i,scale,variation,stem.mother());
            double fraction=stalk.fraction(living);
            float grown=FamilyGeometry.pedicelGrowth(p,fraction);
            if(grown<=0)continue;
            var child=stem.child(i);
            float size=FamilyGeometry.fruitScale(p,count,i,stem.birthScale(i));
            Vec3 socket=pointAt(curve,along,fraction*total);
            Vec3 anchor=FamilyGeometry.hangingAnchor(child,size).multiply(shrivel);
            double length=(grown*FamilyGeometry.pendant(scale)+(size>0?FamilyGeometry.hangClearance(child,size)*shrivel.y-anchor.y:0))*unit*look.slack();
            anchor=anchor.scale(unit);
            var path=limp(socket,length,anchor.y,(count-1-i)%2==0?1:-1,look.splay());
            double[] radius=pedicelRadii(count,i,variation,scale,arc,living,grown);
            sweep(pose,material,path,radius[0]*unit*look.thickness(),radius[1]*unit*look.thickness(),light,colour);
            if(size<=0)continue;
            Vec3 tip=path.getLast();
            pose.pushPose();
            pose.translate(tip.x-anchor.x,tip.y-anchor.y,tip.z-anchor.z);
            pose.scale((float)(size*unit*shrivel.x),(float)(size*unit*shrivel.y),(float)(size*unit*shrivel.z));
            var skin=buffers.getBuffer(RenderType.entityCutoutNoCull(RemainsAssets.stemFruit(child.assetId())));
            YukkuriModel.get(child.assetId()).render("idle",0,pose,skin,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    /** The drawn part of the full stalk (stem_growth.grow): the smoothed authored line cut at the grown fraction of its
     *  length. Each point keeps its final radius; within the tip taper behind the growing end it narrows to the tip radius. */
    private record Stalk(List<Vec3> points,double[] radii,double[] along,List<Vec3> full,double[] fullAlong){
        static Stalk of(int count,float extension,float scale,float variation,YukkuriVariant parent){
            var full=smooth(FamilyGeometry.arc(count,scale,variation,parent),STALK_SUBDIVISIONS);
            int n=full.size();
            double[] s=new double[n];
            for(int k=1;k<n;k++)s[k]=s[k-1]+full.get(k).distanceTo(full.get(k-1));
            double r0=FamilyGeometry.radius(count,0,variation)*scale,r1=FamilyGeometry.radius(count,1,variation)*scale;
            double end=extension*s[n-1],taper=FamilyGeometry.tipTaper()*scale;
            List<Vec3> points=new ArrayList<>();List<Double> radii=new ArrayList<>(),along=new ArrayList<>();
            for(int k=0;k<n;k++){
                double r=Mth.lerp(k/(double)(n-1),r0,r1);
                if(s[k]<end){points.add(full.get(k));radii.add(r);along.add(s[k]);continue;}
                double w=(end-s[k-1])/(s[k]-s[k-1]);
                points.add(full.get(k-1).lerp(full.get(k),w));radii.add(Mth.lerp(w,radii.getLast(),r));along.add(end);
                break;
            }
            double[] radius=new double[points.size()],length=new double[points.size()];
            for(int k=0;k<radius.length;k++){
                double t=Math.clamp((end-along.get(k))/taper,0,1),r=radii.get(k);
                length[k]=along.get(k);
                radius[k]=r>r1?r1+(r-r1)*t*t*(3-2*t):r;
            }
            return new Stalk(points,radius,length,full,s);
        }
        double length(){return fullAlong[fullAlong.length-1];}
        /** Arc fraction of the full stalk's point closest to {@code q}. */
        double fraction(Vec3 q){
            double best=Double.POSITIVE_INFINITY,at=0;
            for(int k=0;k+1<full.size();k++){
                Vec3 start=full.get(k),edge=full.get(k+1).subtract(start);
                double t=Math.clamp(q.subtract(start).dot(edge)/edge.lengthSqr(),0,1),gap=q.distanceToSqr(start.add(edge.scale(t)));
                if(gap<best){best=gap;at=Mth.lerp(t,fullAlong[k],fullAlong[k+1]);}
            }
            return at/length();
        }
    }
    /** Pedicel radius at the socket and at its end: at most 60% of the local stalk, never thinner than a third of it;
     *  a growing pedicel narrows toward its end. */
    private static double[] pedicelRadii(int count,int index,float variation,float scale,List<Vec3> arc,Vec3 socket,float grown){
        double radius=Math.min(.0075*scale,FamilyGeometry.radius(count,(index+.5)/count,variation)*scale*.6);
        int nearest=0;double best=Double.MAX_VALUE;
        for(int k=0;k<arc.size();k++){double d=arc.get(k).distanceToSqr(socket);if(d<best){best=d;nearest=k;}}
        double stalk=FamilyGeometry.radius(count,nearest/(double)(arc.size()-1),variation)*scale;
        return new double[]{Math.max(radius*1.15,stalk*.55),Math.max(radius*.72,stalk/3)*(.5+.5*grown)};
    }
    /** From the root, each segment keeps its length and heading, its pitch lowered by {@code droop} x its arc fraction of
     *  the full stalk, never steeper than {@code steepest} below level, so sockets along a falling stalk stay spread out;
     *  a segment that would go under the ground lies along it instead. */
    private static List<Vec3> wilt(Vec3 root,List<Vec3> relative,double[] along,double total,double[] radii,double droop,double steepest) {
        List<Vec3> out=new ArrayList<>(relative.size());
        out.add(root.add(relative.getFirst()));
        Vec3 heading=new Vec3(0,0,-1);
        for(int k=0;k+1<relative.size();k++){
            Vec3 d=relative.get(k+1).subtract(relative.get(k)),last=out.getLast();
            double length=d.length(),horizontal=d.horizontalDistance();
            if(horizontal>1e-9)heading=new Vec3(d.x/horizontal,0,d.z/horizontal);
            double pitch=Math.max(-steepest,Math.atan2(d.y,horizontal)-droop*(along[k]+along[k+1])/2/total);
            if(length>1e-12&&last.y+length*Math.sin(pitch)<radii[k+1])pitch=Math.asin(Math.clamp((radii[k+1]-last.y)/length,-1,1));
            out.add(last.add(heading.scale(length*Math.cos(pitch))).add(0,length*Math.sin(pitch),0));
        }
        return out;
    }
    /** Without room in front of the corpse the stalk lies back along the body. */
    private static List<Vec3> fold(Vec3 root,List<Vec3> relative,double[] along,double total) {
        List<Vec3> out=new ArrayList<>(relative.size());
        for(int k=0;k<relative.size();k++){var q=relative.get(k);out.add(root.add(q.x*.35,-root.y*along[k]/total*.8,-q.z*.45));}
        return out;
    }
    private static Vec3 pointAt(List<Vec3> points,double[] along,double at) {
        int k=1;
        while(k<along.length-1&&along[k]<at)k++;
        double w=Math.clamp((at-along[k-1])/Math.max(1e-12,along[k]-along[k-1]),0,1);
        return points.get(k-1).lerp(points.get(k),w);
    }
    /** A slack pedicel of this length from the socket, splayed from plumb to one side across the stalk's vertical plane
     *  (which wilting keeps); when the fruit's base ({@code rest} below the pedicel end) would reach the ground the rest of
     *  its length lays the fruit out beside the stalk. The path sags between them; its last point is the pedicel end. */
    private static List<Vec3> limp(Vec3 socket,double length,double rest,int side,double splay) {
        double drop=Math.min(length*Math.cos(splay),Math.max(0,socket.y-rest)),out=Math.sqrt(Math.max(0,length*length-drop*drop));
        Vec3 tip=socket.add(side*out,-drop,0);
        tip=new Vec3(tip.x,Math.max(tip.y,rest),tip.z);
        double chord=tip.distanceTo(socket);
        Vec3 control=socket.add(tip).scale(.5).add(0,-.2*chord-.5*Math.max(0,length-chord),0);
        control=new Vec3(control.x,Math.max(control.y,0),control.z);
        List<Vec3> path=new ArrayList<>(PEDICEL_SEGMENTS+1);
        for(int k=0;k<=PEDICEL_SEGMENTS;k++){
            double t=k/(double)PEDICEL_SEGMENTS,s=1-t;
            path.add(socket.scale(s*s).add(control.scale(2*s*t)).add(tip.scale(t*t)));
        }
        return path;
    }
    /** Local direction of a hanging pedicel: world down, swung by the motion sway and the travel lag. */
    private static Vector3f hang(Matrix3f linear,float ticks,Vec3 travel,int index,float sway) {
        if(Math.abs(linear.determinant())<1e-9f)return new Vector3f(0,-1,0);
        Vector3f forward=linear.transform(new Vector3f(0,0,-1)).mul(1,0,1),right=linear.transform(new Vector3f(1,0,0)).mul(1,0,1);
        if(forward.lengthSquared()>1e-8f)forward.normalize();
        if(right.lengthSquared()>1e-8f)right.normalize();
        float speed=(float)Math.min(.25,travel.length()),phase=(ticks/SWAY_PERIOD_TICKS+index*.31f)*Mth.TWO_PI,amount=sway*Math.min(1,speed*10);
        Vector3f swing=forward.mul(SWAY_FORE*amount*Mth.sin(phase)).add(right.mul(SWAY_SIDE*amount*Mth.sin(phase*.63f+index)))
                .add((float)-travel.x*TRAVEL_LAG,0,(float)-travel.z*TRAVEL_LAG);
        float horizontal=(float)Math.sqrt(swing.x*swing.x+swing.z*swing.z);
        if(horizontal>MAX_SWING)swing.mul(MAX_SWING/horizontal);
        return new Matrix3f(linear).invert().transform(new Vector3f(swing.x,-1,swing.z)).normalize();
    }
    private static final int PEDICEL_SEGMENTS=10,STALK_SUBDIVISIONS=4;
    /** Uniform Catmull-Rom spline through the points (ends clamped), {@code subdivisions} samples per span. */
    static List<Vec3> smooth(List<Vec3> points,int subdivisions) {
        int n=points.size();if(n<3)return points;
        List<Vec3> result=new ArrayList<>((n-1)*subdivisions+1);
        for(int k=0;k<n-1;k++){
            Vec3 p0=points.get(Math.max(0,k-1)),p1=points.get(k),p2=points.get(k+1),p3=points.get(Math.min(n-1,k+2));
            for(int s=0;s<subdivisions;s++){
                double t=s/(double)subdivisions,t2=t*t,t3=t2*t;
                result.add(p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
                        .add(p1.scale(3).subtract(p0).subtract(p2.scale(3)).add(p3).scale(t3)).scale(.5));
            }
        }
        result.add(points.get(n-1));
        return result;
    }
    static List<Vec3> bezier(Vec3 p0,Vec3 p1,Vec3 p2,Vec3 p3,int segments) {
        List<Vec3> points=new ArrayList<>(segments+1);
        for(int k=0;k<=segments;k++){
            double t=k/(double)segments,s=1-t;
            points.add(p0.scale(s*s*s).add(p1.scale(3*s*s*t)).add(p2.scale(3*s*t*t)).add(p3.scale(t*t*t)));
        }
        return points;
    }
    /** A continuous tapering tube along a polyline, with rings carried by parallel transport so bends never crease. */
    static void sweep(PoseStack pose,VertexConsumer buffer,List<Vec3> points,double r0,double r1,int light,int[] color) {
        double[] radii=new double[points.size()];
        for(int k=0;k<radii.length;k++)radii[k]=r0+(r1-r0)*k/Math.max(1,radii.length-1);
        sweep(pose,buffer,points,radii,light,color);
    }
    /** As above with a radius per point. */
    static void sweep(PoseStack pose,VertexConsumer buffer,List<Vec3> points,double[] radii,int light,int[] color) {
        int n=points.size();if(n<2)return;
        Vec3[] tangents=new Vec3[n];
        for(int k=0;k<n;k++){
            Vec3 t=points.get(Math.min(n-1,k+1)).subtract(points.get(Math.max(0,k-1)));
            tangents[k]=t.lengthSqr()<1e-14?(k>0?tangents[k-1]:new Vec3(0,-1,0)):t.normalize();
        }
        Vec3 reference=Math.abs(tangents[0].z)<.9?new Vec3(0,0,1):new Vec3(1,0,0);
        Vec3 u=tangents[0].cross(reference).normalize();
        Vec3[][] rings=new Vec3[n][8];Vec3[][] normals=new Vec3[n][8];
        for(int k=0;k<n;k++){
            if(k>0){Vec3 t=tangents[k];Vec3 projected=u.subtract(t.scale(u.dot(t)));if(projected.lengthSqr()>1e-12)u=projected.normalize();}
            Vec3 v=tangents[k].cross(u).normalize();double r=radii[k];
            for(int j=0;j<8;j++){double a=j*Math.PI/4;normals[k][j]=u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));rings[k][j]=points.get(k).add(normals[k][j].scale(r));}
        }
        for(int k=1;k<n;k++)for(int j=0;j<8;j++){
            int m=(j+1)%8;
            vertex(pose,buffer,rings[k-1][j],normals[k-1][j],0,0,light,color);
            vertex(pose,buffer,rings[k][j],normals[k][j],0,1,light,color);
            vertex(pose,buffer,rings[k][m],normals[k][m],1,1,light,color);
            vertex(pose,buffer,rings[k-1][m],normals[k-1][m],1,0,light,color);
        }
        // Closed ends keep the narrow twig solid from close viewpoints.
        for(int j=0;j<8;j++){
            int m=(j+1)%8;Vec3 back=tangents[0].scale(-1),front=tangents[n-1];
            vertex(pose,buffer,points.get(0),back,.5f,.5f,light,color);
            vertex(pose,buffer,rings[0][m],back,1,0,light,color);
            vertex(pose,buffer,rings[0][j],back,0,0,light,color);
            vertex(pose,buffer,points.get(0),back,.5f,.5f,light,color);
            vertex(pose,buffer,points.get(n-1),front,.5f,.5f,light,color);
            vertex(pose,buffer,rings[n-1][j],front,0,0,light,color);
            vertex(pose,buffer,rings[n-1][m],front,1,0,light,color);
            vertex(pose,buffer,points.get(n-1),front,.5f,.5f,light,color);
        }
    }
    private static void vertex(PoseStack pose,VertexConsumer buffer,Vec3 position,Vec3 normal,float u,float v,int light,int[] color) {
        buffer.addVertex(pose.last().pose(),(float)position.x,(float)position.y,(float)position.z)
                .setColor(color[0],color[1],color[2],255).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(),(float)normal.x,(float)normal.y,(float)normal.z);
    }
    private FamilyRenderer() {}
}
