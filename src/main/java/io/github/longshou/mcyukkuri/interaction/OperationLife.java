package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** Short, exclusive physical operations. Every exit keeps or returns each inserted object exactly once. */
public final class OperationLife {
    public enum Pose { NONE, LIFT, GRIP, SLIDE, RUB, ROLL, SUPINE, PIN, FIXED, WRAPPED, HOOK, RUMP, VIBRATE, BOX, ASSIST, HAT_REMOVE, HAT_RETURN }
    private final YukkuriEntity entity;
    private Pose pose=Pose.NONE;
    private UUID operator;
    private ItemStack inserted=ItemStack.EMPTY;
    private BlockPos ceiling;
    private Vec3 anchor=Vec3.ZERO,last=Vec3.ZERO;
    private int remaining,elapsed,cooldown;
    private float turn,abrasion;
    private boolean ownDamage;
    /** The dose occupies the same durable reservation slot as a physical tool. */
    public boolean beginAssistance(Player player,InteractionHand hand){
        var stack=player.getItemInHand(hand);
        if(!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)||!CareMenu.valid(player,entity)
                ||active()||!inserted.isEmpty()||cooldown>0||player.distanceToSqr(entity)>2.25
                ||io.github.longshou.mcyukkuri.life.Consumables.kind(stack)!=io.github.longshou.mcyukkuri.life.Consumables.Kind.REPRODUCTIVE
                ||!io.github.longshou.mcyukkuri.family.FamilyData.get(level).canAssist(entity))return false;
        if(!begin(player,Pose.ASSIST,60,null))return false;
        if(!player.getAbilities().instabuild)inserted=stack.split(1);
        player.swing(hand);return true;
    }
    public OperationLife(YukkuriEntity entity){this.entity=entity;}
    public boolean active(){return pose!=Pose.NONE;}
    public boolean controlling(){return active()&&pose!=Pose.SLIDE;}
    public Pose pose(){return pose;}
    public UUID operator(){return operator;}
    public ItemStack inserted(){return inserted.copy();}
    public void damaged(DamageSource source){if(active()&&!ownDamage)end();}
    public boolean canStart(Player player,CareAction action){
        return CareMenu.valid(player,entity)&&!active()&&inserted.isEmpty()&&cooldown==0
                &&(action.tool==null||player.getMainHandItem().is(action.tool.item()));
    }
    public boolean perform(Player player,CareAction action){
        if(entity.level().isClientSide||!CareMenu.valid(player,entity))return false;
        if(action==CareAction.RELEASE)return active()||!inserted.isEmpty()?end():false;
        if(!canStart(player,action))return false;
        boolean done=switch(action){
            case OBSERVE -> {
                player.sendSystemMessage(Component.translatable("operation.mcyukkuri.observation",entity.getName(),
                        Math.round(entity.getHealth()),Math.round(entity.life().nutrition()),entity.body().wound(),entity.body().filling()));yield true;
            }
            case GENTLE -> entity.social().talk(player,true);
            case COLD -> entity.social().talk(player,false);
            case ADOPT -> io.github.longshou.mcyukkuri.event.WildEvents.adopt(entity,player);
            case ABANDON -> io.github.longshou.mcyukkuri.event.WildEvents.abandon(entity,player);
            case DECLINE_EVENT -> io.github.longshou.mcyukkuri.event.WildEvents.decline(entity,player);
            case SING -> entity.dailyLife().invite(player,io.github.longshou.mcyukkuri.social.DailyLife.Kind.SING);
            case EXERCISE -> entity.dailyLife().invite(player,io.github.longshou.mcyukkuri.social.DailyLife.Kind.EXERCISE);
            case FAVOR,DISLIKE -> {
                boolean favored=action==CareAction.FAVOR;
                if(!entity.life().personality().treatment(player.getUUID(),favored))yield false;
                entity.showReaction(favored?"happy":"surprise",favored?"caregiver":"afraid",100);yield true;
            }
            case LIFT -> !entity.isPanicking()&&entity.life().personality().fear(player.getUUID())<40&&begin(player,Pose.LIFT,160,null);
            case GRIP -> begin(player,Pose.GRIP,100,null);
            case RUB -> {
                if(entity.isPanicking()||entity.life().hasUrgentNeed()||entity.life().isSleeping()||entity.life().mealTicks()>0
                        ||entity.life().personality().fear(player.getUUID())>=25||player.distanceToSqr(entity)>2.25
                        ||!entity.life().personality().talk(player.getUUID(),true))yield false;
                entity.showReaction("happy","gentle",100);yield begin(player,Pose.RUB,40,null);
            }
            case FLICK,PUNCH,SLAP -> {
                float damage=action==CareAction.PUNCH?2:action==CareAction.FLICK?.5f:1;
                if(!strike(player,damage))yield false;
                var away=entity.position().subtract(player.position()).multiply(1,0,1).normalize();
                entity.setDeltaMovement(away.scale(action==CareAction.FLICK?.25:.4).add(0,.16,0));
                if(entity.isAlive())begin(player,Pose.SLIDE,30,null);yield true;
            }
            case ROLL -> entity.onGround()&&begin(player,Pose.ROLL,50,null);
            case LAY_DOWN -> entity.onGround()&&begin(player,Pose.SUPINE,60,null);
            case LIE_COMMAND,SHOW_RUMP -> {
                if(!entity.life().personality().knowsWait()||entity.life().personality().trust(player.getUUID())<10||entity.isPanicking())yield false;
                yield begin(player,action==CareAction.SHOW_RUMP?Pose.RUMP:Pose.SUPINE,80,null);
            }
            case SHAKE_HOME -> shakeHome(player);
            case PIN -> {if(!entity.onGround()||!strike(player,1)||!entity.isAlive())yield false;yield begin(player,Pose.PIN,240,Tool.PIN);}
            case MALLET -> strike(player,entity.getMaxHealth()+20);
            case PIERCE -> strike(player,2);
            case BURN -> {
                if(!io.github.longshou.mcyukkuri.item.OperationToolItem.isHot(player.getMainHandItem(),entity.level()))yield false;
                if(!strike(player,2))yield false;
                yield true;
            }
            case FIX_SUPINE -> entity.onGround()&&begin(player,Pose.FIXED,400,Tool.RESTRAINT);
            case WRAP_HANG,HOOK_HANG -> {
                var support=ceiling();if(support==null){player.displayClientMessage(Component.translatable("operation.mcyukkuri.need_ceiling"),true);yield false;}
                if(action==CareAction.HOOK_HANG&&(!strike(player,1)||!entity.isAlive()))yield false;
                ceiling=support;yield begin(player,action==CareAction.WRAP_HANG?Pose.WRAPPED:Pose.HOOK,300,action.tool);
            }
            case TRIM_HAIR -> entity.body().cutHair(7,player);
            case CUT_LEFT -> entity.body().cutHair(1,player);
            case CUT_RIGHT -> entity.body().cutHair(2,player);
            case CUT_ACCESSORY -> cutAccessory(player);
            case VIBRATE -> entity.family().adult()&&!entity.family().sterile()&&begin(player,Pose.VIBRATE,120,Tool.VIBRATOR);
            case STERILIZE -> {
                if(!entity.family().adult()||entity.family().sterile()||entity.family().stimulation()==0)yield false;
                if(!strike(player,2)||!entity.isAlive())yield false;
                yield entity.family().sterilize();
            }
            case ENCLOSE -> entity.onGround()&&begin(player,Pose.BOX,400,Tool.BOX);
            case CUT_STEM,BOTTLE_STEM -> io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity.cut(entity,player,action==CareAction.BOTTLE_STEM);
            case TAKE_ACCESSORY,CONFISCATE -> takeAccessory(player,action==CareAction.CONFISCATE);
            case HANG_ACCESSORY -> AccessoryItem.suspend(player,entity,player.getMainHandItem());
            case HAT_CARRY_CHILD -> io.github.longshou.mcyukkuri.family.HatCarry.pickUp(entity,player);
            case HAT_PUT_DOWN_CHILD -> io.github.longshou.mcyukkuri.family.HatCarry.putDown(entity);
            case COLLECT_NEAREST -> collect(player);
            case ASK -> entity.pet().care().ask(player);
            case REMOVE_HARNESS -> entity.pet().care().takeOffHarness(player);
            case UNWRAP -> entity.pet().care().takeOffSwaddle(player);
            case RELEASE -> false;
        };
        if(done){
            if((action==CareAction.GENTLE||action==CareAction.COLD||action==CareAction.FLICK)&&entity.pet().lessons().awaitingFeedback())entity.pet().lessons().feedback(player,action);
            entity.pet().care().feedback(player,action);
            cooldown=20;player.swing(InteractionHand.MAIN_HAND);
            entity.pet().contact(player.getUUID(),!java.util.EnumSet.of(CareAction.FLICK,CareAction.PUNCH,CareAction.SLAP,CareAction.MALLET,CareAction.PIERCE,CareAction.BURN,CareAction.PIN).contains(action));
            if(action.tool!=null&&!action.tool.inserted)player.getMainHandItem().hurtAndBreak(1,player,EquipmentSlot.MAINHAND);
        }
        return done;
    }
    private boolean strike(Player player,float amount){
        ownDamage=true;
        try{return entity.hurt(entity.damageSources().playerAttack(player),amount);}finally{ownDamage=false;}
    }
    private boolean begin(Player player,Pose next,int duration,Tool binding){
        if(!entity.isAlive()||active())return false;
        if((next==Pose.SUPINE||next==Pose.FIXED||next==Pose.ROLL||next==Pose.BOX)&&entity.level().getBlockCollisions(entity,
                entity.getBoundingBox().inflate(.6,0,.6).expandTowards(0,.4,0).move(0,.01,0)).iterator().hasNext())return false;
        entity.life().interrupt();entity.social().stop();entity.house().stop();entity.family().stop();
        entity.getNavigation().stop();entity.setPersistenceRequired();
        pose=next;operator=player.getUUID();remaining=duration;elapsed=0;turn=abrasion=0;anchor=last=entity.position();
        if(binding!=null&&!player.getAbilities().instabuild){inserted=player.getMainHandItem().split(1);}
        entity.syncOperation(pose.name(),0);entity.syncOperationAnchor(ceiling);return true;
    }
    private BlockPos ceiling(){
        for(int dy=2;dy<=4;dy++){
            var pos=entity.blockPosition().above(dy);
            if(!entity.level().getBlockState(pos).getCollisionShape(entity.level(),pos).isEmpty())return pos;
        }
        return null;
    }
    public void tick(){
        if(entity.level().isClientSide)return;
        if(cooldown>0)cooldown--;
        if(!active()){if(!inserted.isEmpty())returnInserted();return;}
        var player=entity.level().getPlayerByUUID(operator);
        if(player==null||!CareMenu.valid(player,entity)||entity.isInWaterOrBubble()||entity.isOnFire()||--remaining<=0){end();return;}
        elapsed++;
        if(pose==Pose.ASSIST){
            if(player.distanceToSqr(entity)>2.25||entity.position().distanceToSqr(anchor)>.04
                    ||!entity.family().readyForAssistance()){end();return;}
            entity.getNavigation().stop();entity.setDeltaMovement(entity.getDeltaMovement().multiply(0,1,0));
            // Show the rear to the caregiver; birth later uses a separate front outlet.
            entity.setYRot(player.getYRot());entity.yBodyRot=player.getYRot();entity.setYHeadRot(player.getYRot());
            if(elapsed==40){
                var brood=io.github.longshou.mcyukkuri.family.FamilyData.get((net.minecraft.server.level.ServerLevel)entity.level()).assist(entity);
                if(brood!=null){inserted=ItemStack.EMPTY;entity.showReaction("happy","expecting",100);}
                cooldown=40;end();return;
            }
        }
        if(pose==Pose.RUB){
            if(player.distanceToSqr(entity)>2.25||entity.life().hasUrgentNeed()||entity.isPanicking()){end();return;}
            if(elapsed==30)entity.dailyLife().comfort(25);
        }
        if(pose==Pose.WRAPPED||pose==Pose.HOOK){
            if(ceiling==null||entity.level().getBlockState(ceiling).getCollisionShape(entity.level(),ceiling).isEmpty()){end();return;}
            moveTo(Vec3.atBottomCenterOf(ceiling).add(0,-.9-entity.getBbHeight(),0));
        }else if(pose==Pose.LIFT||pose==Pose.GRIP){
            var forward=player.getLookAngle().multiply(1,0,1).normalize();
            moveTo(player.getEyePosition().add(forward.scale(1.1)).add(0,-.65-entity.getBbHeight()/2,0));
            if(pose==Pose.GRIP&&elapsed%40==0&&strike(player,1)&&entity.isAlive())entity.body().releaseFilling("squeeze");
        }else if(pose==Pose.PIN||pose==Pose.FIXED||pose==Pose.SUPINE||pose==Pose.BOX||pose==Pose.VIBRATE||pose==Pose.HAT_REMOVE||pose==Pose.HAT_RETURN){
            moveTo(anchor);
            if(pose==Pose.VIBRATE&&elapsed%20==0){entity.family().stimulate();entity.life().spendNutrition(.5f);}
            if(pose==Pose.BOX&&elapsed%80==0)entity.showReaction("surprise","afraid",80);
        }else if(pose==Pose.ROLL){
            var forward=entity.position().subtract(player.position()).multiply(1,0,1).normalize();
            entity.move(MoverType.SELF,forward.scale(.045).add(0,-.04,0));
        }else if(pose==Pose.RUMP){
            entity.setYRot(player.getYRot());entity.yBodyRot=player.getYRot();entity.setYHeadRot(player.getYRot());
        }
        double moved=entity.position().subtract(last).horizontalDistance();
        // Neither still restraint nor suspension causes ground abrasion. Large loads/teleports are excluded.
        if((pose==Pose.ROLL||pose==Pose.SLIDE)&&entity.onGround()&&moved<.5){
            turn+=moved*360/(Math.PI*Math.max(.1,entity.getBbWidth()));
            var floor=io.github.longshou.mcyukkuri.life.Facilities.floor(entity);
            float roughness=io.github.longshou.mcyukkuri.life.Facilities.roughness(floor);
            abrasion+=moved*roughness;
            if(abrasion>=1.5f){abrasion-=1.5f;strike(player,1);}
        }
        last=entity.position();entity.syncOperation(pose.name(),pose==Pose.HAT_REMOVE||pose==Pose.HAT_RETURN?elapsed/20f:turn);
    }
    private void moveTo(Vec3 desired){
        var delta=desired.subtract(entity.position());
        if(delta.lengthSqr()>9){end();return;}
        if(delta.lengthSqr()>.09)delta=delta.normalize().scale(.3);
        entity.setDeltaMovement(Vec3.ZERO);if(delta.lengthSqr()>1e-10)entity.move(MoverType.SELF,delta);
    }
    /** A failed world insertion leaves the binding in this entity's durable pending-return slot. */
    private boolean returnInserted(){
        if(inserted.isEmpty())return true;
        var item=new ItemEntity(entity.level(),entity.getX(),entity.getY(),entity.getZ(),inserted.copy());item.setDefaultPickUpDelay();
        if(!entity.level().addFreshEntity(item))return false;inserted=ItemStack.EMPTY;return true;
    }
    public boolean end(){
        pose=Pose.NONE;operator=null;ceiling=null;remaining=elapsed=0;
        entity.syncOperation(Pose.NONE.name(),0);entity.getNavigation().stop();return returnInserted();
    }
    private boolean takeAccessory(Player player,boolean confiscated){
        if(entity.isVehicle()&&!io.github.longshou.mcyukkuri.family.HatCarry.putDown(entity))return false;
        var token=entity.life().takeAccessory(player.getUUID());if(token==null)return false;
        var stack=new ItemStack(ModItems.accessoryFor(entity));
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Owner",entity.getUUID());tag.putUUID("Token",token);
            tag.putString("Variant",entity.getVariant().assetId());tag.putString("SourceModel",entity.subspecies().assetId());tag.putFloat("SourceScale",entity.growthScale()*entity.getScale()*(entity.getVariant().isHouse()?.32f:1));});
        entity.pet().markAccessory(stack);
        if(confiscated)entity.life().personality().confiscated(player.getUUID());
        if(!player.getInventory().add(stack))player.drop(stack,false);animateHat(player,false);return true;
    }
    public void animateHat(Player player,boolean returning){
        if(!io.github.longshou.mcyukkuri.family.HatCarry.hasHat(entity))return;
        if(pose==Pose.HAT_REMOVE||pose==Pose.HAT_RETURN)end();
        if(!active())begin(player,returning?Pose.HAT_RETURN:Pose.HAT_REMOVE,21,null);
    }
    private boolean cutAccessory(Player player){
        if(entity.isVehicle()&&!io.github.longshou.mcyukkuri.family.HatCarry.putDown(entity))return false;
        if(!entity.life().hasAccessory())return false;
        var token=UUID.randomUUID();var stack=new ItemStack(ModItems.accessoryFor(entity));
        stack.set(DataComponents.CUSTOM_NAME,Component.translatable("item.mcyukkuri.cut_accessory",stack.getHoverName()));
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Owner",entity.getUUID());tag.putUUID("Token",token);tag.putBoolean("Cut",true);
            tag.putString("Variant",entity.getVariant().assetId());tag.putString("SourceModel",entity.subspecies().assetId());tag.putFloat("SourceScale",entity.growthScale()*entity.getScale()*(entity.getVariant().isHouse()?.32f:1));});
        entity.pet().markAccessory(stack);
        var item=new ItemEntity(entity.level(),entity.getX(),entity.getY(),entity.getZ(),stack);item.setDefaultPickUpDelay();
        if(!entity.level().addFreshEntity(item))return false;
        if(entity.life().takeAccessory(player.getUUID(),token)==null){item.discard();return false;}
        entity.life().personality().confiscated(player.getUUID());entity.showReaction("surprise","lost_accessory",140);return true;
    }
    private boolean collect(Player player){
        var item=entity.level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(2),value->value.isAlive()
                &&player.distanceToSqr(value)<25&&player.hasLineOfSight(value)).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(item==null)return false;
        var stack=item.getItem();int before=stack.getCount();player.getInventory().add(stack);
        if(stack.isEmpty())item.discard();else item.setItem(stack);
        return stack.getCount()<before;
    }
    private boolean shakeHome(Player player){
        BlockPos home=entity.house().nestPosition();
        if(home==null&&entity.life().home()!=null&&entity.life().home().dimension().equals(entity.level().dimension()))home=entity.life().home().pos();
        if(home==null||entity.level().getBlockState(home).isAir()||player.distanceToSqr(Vec3.atCenterOf(home))>25)return false;
        final BlockPos chosen=home;
        var residents=entity.level().getEntitiesOfClass(YukkuriEntity.class,new net.minecraft.world.phys.AABB(home).inflate(6),other->other.isAlive()&&(
                chosen.equals(other.house().nestPosition())||other.life().home()!=null&&other.life().home().dimension().equals(entity.level().dimension())&&chosen.equals(other.life().home().pos())));
        residents.forEach(other->{other.life().interrupt();other.social().stop();other.showReaction("surprise","afraid",100);});return !residents.isEmpty();
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putInt("Version",1);tag.putInt("Cooldown",cooldown);
        if(!inserted.isEmpty())tag.put("PendingReturn",inserted.save(entity.registryAccess()));return tag;
    }
    public void load(CompoundTag tag){
        pose=Pose.NONE;operator=null;ceiling=null;remaining=elapsed=0;cooldown=Math.clamp(tag.getInt("Cooldown"),0,200);
        inserted=tag.contains("PendingReturn")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("PendingReturn")):ItemStack.EMPTY;
        entity.syncOperation(Pose.NONE.name(),0);
    }
}
