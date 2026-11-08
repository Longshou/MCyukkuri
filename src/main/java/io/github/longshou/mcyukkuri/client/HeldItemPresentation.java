package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.client.capture.PoseTap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import io.github.longshou.mcyukkuri.motor.Held;
import io.github.longshou.mcyukkuri.motor.Held.Place;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Presentation of a real synchronized stack; never creates an item or grants inventory. The stack she holds is drawn exactly where
 * the server's record says it is ({@link Held}): in her grip (a braid's tuft, or the mouth of a body without a braid), crosswise in
 * her mouth, or lying in her own set-down hat; while a beat moves it, it rides the tuft or the lips to the beat's handover and glides
 * into its new place across it, so it is never in two places and never in none.
 */
final class HeldItemPresentation {
    /** Ticks either side of a beat's handover over which the stack glides from where it was to where it goes (project design). */
    private static final float GLIDE = 3;
    /** The braid tuft's target for a hand-over at her lips: their braid-side corner, a little in front (model units, project design). */
    private static final Vector3f LIPS_CORNER = new Vector3f(1.6f, .6f, -.9f);

    static boolean closedGrip(YukkuriView entity) {
        String behavior=entity.getBehaviorId();
        return !entity.profile().houseBody()&&!entity.getCarriedItem().isEmpty()
                &&entity.getMouthItem().isEmpty()&&entity.getMouthBodyFood().isEmpty()&&!behavior.equals(Behaviors.FAMILY_CHEW)
                &&!behavior.equals(Behaviors.BOAT_FEED_YOUNG);
    }

    /** The stick is shown while the mouth is free: not while eating, feeding, carrying by mouth or asleep. */
    static boolean kanroken(YukkuriView entity,String behavior) {
        return entity.visibleKanroken()&&!ParentCarry.mouthCarrying(entity)&&entity.getMouthItem().isEmpty()&&entity.getMouthBodyFood().isEmpty()&&entity.getCarriedItem().isEmpty()
                &&!behavior.startsWith(Behaviors.SLEEP)&&!behavior.equals(Behaviors.WAKE)&&!behavior.equals(Behaviors.FAMILY_CHEW)&&!behavior.equals(Behaviors.FEED_CHILD)
                &&!behavior.equals(Behaviors.BOAT_FEED_YOUNG)&&PropModels.has("kanroken");
    }

    static void mouth(PoseStack pose,YukkuriModel model) {
        pose.mulPose(model.mouthAttachmentPose());
    }

    /**
     * Where her stack is this frame: {@code from} and {@code to} (equal outside a beat), how far it has glided ({@code share} 0..1),
     * where it is held by the server's handover ({@code at}), and how far her braid reaches toward the beat's place ({@code reach}).
     */
    record Now(Held held, Place from, Place to, Place at, float share, float reach) {
        /** Her braid's hold pose carries the stack. */
        boolean overlay() { return at == Place.GRIP; }
        /** The tuft's grip is needed: it holds the stack, or the stack glides to or from it. */
        boolean attach() { return at == Place.GRIP || share > 0 && share < 1 && (from == Place.GRIP || to == Place.GRIP); }
        /** Her lips close on it, or are about to. */
        boolean mouth() { return at == Place.MOUTH || share > 0 && share < 1 && (from == Place.MOUTH || to == Place.MOUTH); }
    }

    /** Her stack's place for this frame, or null when she holds none. */
    static Now now(YukkuriView entity,float partial) {
        if(entity.getHeldItem().isEmpty())return null;
        var held=entity.heldPlace();var beat=held.beat();
        if(beat==null)return new Now(held,held.place(),held.place(),held.place(),0,0);
        float t=entity.level().getGameTime()+partial-beat.start();int handover=beat.handover(),length=beat.length();
        float glide=Math.min(GLIDE,handover);
        float share=smooth((t-(handover-glide))/(2*glide));
        float reach=t<handover?smooth(t/Math.max(1,handover)):1-smooth((t-handover)/Math.max(1,length-handover));
        return new Now(held,beat.from(),beat.to(),t<handover?beat.from():beat.to(),share,reach);
    }

    /** The braid's reach for this frame's beat (model units, and its share), or null when the braid does not move the stack now. */
    static void braid(YukkuriView entity,YukkuriModel model,Now now,float partial) {
        if(now==null||now.held.beat()==null||now.held.beat().mouth()||now.reach<=0||entity.profile().houseBody())return;
        Place other=now.from==Place.GRIP?now.to:now.from;
        Vector3f point=switch(other){
            case MOUTH->MouthPlacement.anchor(entity).add(LIPS_CORNER);
            case HAT->{var grip=hatGrip(entity,now.held,partial);yield grip==null?null:modelPoint(entity,grip.position,partial);}
            case GROUND->now.held.beat().ground()==null?null:modelPoint(entity,now.held.beat().ground(),partial);
            default->null;
        };
        if(point!=null)model.setBraidBeat(point,now.reach);
    }

    /** How far she bows to lay her stack down from her lips (0..1). */
    static float bow(Now now) {
        return now!=null&&now.held.beat()!=null&&now.held.beat().mouth()?now.reach:0;
    }

    /**
     * Draw the stack where it is. {@code start} is the entity's render origin pose (before any body transform), {@code pose} the
     * posed model space the model's grips are in.
     */
    static void held(ItemStack stack,YukkuriView entity,YukkuriModel model,Now now,Matrix4f start,PoseStack pose,MultiBufferSource buffers,int light,int overlay,float partial) {
        var toOrigin=new Matrix4f(start).invert().mul(pose.last().pose());
        var from=frame(entity,model,now.held,now.from,toOrigin,partial);
        var to=now.from==now.to?from:frame(entity,model,now.held,now.to,toOrigin,partial);
        var drawn=from==null?to:to==null?from:blend(from,to,now.share);
        PoseTap.held(entity,to==null&&now.share>=1?null:drawn,frame(entity,model,now.held,now.at,toOrigin,partial),partial);
        if(drawn==null)return;
        if(to==null&&now.share>=1)return;
        pose.pushPose();
        pose.last().pose().set(start).mul(drawn);
        pose.last().normal().rotation(new Matrix4f(pose.last().pose()).getNormalizedRotation(new Quaternionf()));
        // The item sprite runs diagonally: align it with the frame's +Y (the short top end) and grip near its top.
        pose.translate(0,-5/16f,0);pose.mulPose(Axis.ZP.rotationDegrees(45));
        float scale=(float)Math.sqrt(.5);pose.scale(scale,scale,scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack,ItemDisplayContext.NONE,light,overlay,pose,buffers,entity.level(),entity.getId());
        pose.popPose();
    }

    /** The stack's grip frame (+Y toward its short top end) at {@code place}, relative to the render origin; null where it is not drawn. */
    private static Matrix4f frame(YukkuriView entity,YukkuriModel model,Held held,Place place,Matrix4f toOrigin,float partial) {
        return switch(place){
            case GRIP->{
                var grip=model.itemAttachmentPose();
                // A body without a braid grip holds it crosswise at the mouth, as a Marisa does in hers.
                yield grip!=null?new Matrix4f(toOrigin).mul(grip):crosswise(model,toOrigin);
            }
            case MOUTH->crosswise(model,toOrigin);
            case HAT->{var grip=hatGrip(entity,held,partial);yield grip==null?null:lying(entity,grip,partial);}
            case GROUND->held.beat()==null||held.beat().ground()==null?null:lying(entity,new Grip(held.beat().ground(),facing(entity,partial),new Vec3(0,1,0)),partial);
        };
    }

    /** Crosswise between her lips, centred on them, its short end to her braid side. */
    private static Matrix4f crosswise(YukkuriModel model,Matrix4f toOrigin) {
        return new Matrix4f(toOrigin).mul(model.mouthAttachmentPose()).rotateZ(-Mth.HALF_PI).translate(0,5/16f,0);
    }

    private record Grip(Vec3 position,Vec3 top,Vec3 up) {}

    /** Her own set-down hat's grip spot this frame, in world coordinates. */
    private static Grip hatGrip(YukkuriView entity,Held held,float partial) {
        if(held.hat()<0||held.spot()==null||!(entity.level().getEntity(held.hat()) instanceof PlacedHatView hat))return null;
        var frame=new HostFrame(hat.getPosition(partial),hat.hullScale()/16,(float)Math.toRadians(180-Mth.rotLerp(partial,hat.previousYaw(),hat.getYRot())),
                hat.roll(partial),hat.pitch(partial));
        var at=frame.toWorld(held.spot());
        return new Grip(at,frame.toWorld(held.spot().add(held.dir())).subtract(at).normalize(),frame.toWorld(held.spot().add(0,1,0)).subtract(at).normalize());
    }

    /** Lying on its side at a world grip, its short end toward {@code top}, at the size it had in her grip. */
    private static Matrix4f lying(YukkuriView entity,Grip grip,float partial) {
        var origin=entity.getPosition(partial);var y=grip.top.toVector3f();var z=grip.up.toVector3f();var x=new Vector3f(y).cross(z).normalize();
        z.set(x).cross(y).normalize();
        float size=entity.growthScale()*entity.getScale();
        return new Matrix4f().translation((float)(grip.position.x-origin.x),(float)(grip.position.y-origin.y),(float)(grip.position.z-origin.z))
                .mul(new Matrix4f(x.x,x.y,x.z,0,y.x,y.y,y.z,0,z.x,z.y,z.z,0,0,0,0,1)).scale(size);
    }

    private static Vec3 facing(YukkuriView entity,float partial) {
        float yaw=Mth.rotLerp(partial,entity.previousBodyYaw(),entity.bodyYaw())*Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw),0,Mth.cos(yaw));
    }

    /** Position, rotation and size of two frames, {@code share} of the way from the first to the second. */
    private static Matrix4f blend(Matrix4f from,Matrix4f to,float share) {
        if(share<=0)return from;
        if(share>=1)return to;
        var a=from.getTranslation(new Vector3f());var b=to.getTranslation(new Vector3f());
        var qa=from.getNormalizedRotation(new Quaternionf());var qb=to.getNormalizedRotation(new Quaternionf());
        float sa=from.getScale(new Vector3f()).x,sb=to.getScale(new Vector3f()).x;
        return new Matrix4f().translationRotateScale(a.lerp(b,share),qa.slerp(qb,share),Mth.lerp(share,sa,sb));
    }

    /** A world point in the body's model space (model units), the inverse of the renderer's turn and scale. */
    private static Vector3f modelPoint(YukkuriView entity,Vec3 world,float partial) {
        var origin=entity.getPosition(partial);float yaw=Mth.rotLerp(partial,entity.previousBodyYaw(),entity.bodyYaw());
        return new Vector3f((float)(world.x-origin.x),(float)(world.y-origin.y),(float)(world.z-origin.z))
                .rotateY(-(180-yaw)*Mth.DEG_TO_RAD).mul(16/(entity.growthScale()*entity.getScale()));
    }

    private static float smooth(float x){x=Math.clamp(x,0,1);return x*x*(3-2*x);}

    static void bedding(PoseStack pose) {
        // The source bundle is authored lying on the ground. Put its middle between
        // the lips instead of placing its ground origin above/in front of the mouth.
        PropModels.center("bedding_bundle",pose,false);
    }

    private HeldItemPresentation() {}
}
