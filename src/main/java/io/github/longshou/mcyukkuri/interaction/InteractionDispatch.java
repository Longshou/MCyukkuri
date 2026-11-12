package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.DailyLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.status.Identity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Shared server checks for player interaction. Care buttons use the same refusal as execution. */
public final class InteractionDispatch {
    private InteractionDispatch() {}

    public static String rangeRefusal(Player player,YukkuriEntity target,double range){
        if(target==null||!target.isAlive()||!player.isAlive()||player.isSpectator()||player.level()!=target.level())return "target";
        return player.distanceToSqr(target)>range*range?"reach":null;
    }
    public static String reachRefusal(Player player,YukkuriEntity target,double range){
        var reason=rangeRefusal(player,target,range);
        if(reason!=null)return reason;
        return player.hasLineOfSight(target)?null:"sight";
    }
    public static boolean requireReach(Player player,YukkuriEntity target,double range){
        var reason=reachRefusal(player,target,range);
        if(reason==null)return true;
        refuse(player,target,"reach",reason);
        return false;
    }
    public static void refuse(Player player,YukkuriEntity target,String action,String reason){
        if(target!=null)GameplayLog.limited(target,"interaction",action+":"+reason,20,
                "interaction refused; player={} action={} reason={}",player.getUUID(),action,reason);
        player.displayClientMessage(Component.translatable("operation.mcyukkuri.refuse."+reason),true);
    }
    public static boolean ownsPet(Player player,YukkuriEntity target){
        return target.status().identity()==Identity.PET&&target.pet().owned(player);
    }
    public static String petRefusal(Player player,YukkuriEntity target){
        if(!ownsPet(player,target))return "owner";
        return target.pet().busy() || ExamSession.running(target) ? "busy" : null;
    }
    public static boolean fitWear(Player player,YukkuriEntity target,ItemStack stack,CareAction removal){
        if(!requireReach(player,target,3))return false;
        var wear=target.pet().care();
        boolean harness=removal==CareAction.REMOVE_HARNESS;
        if(player.isSecondaryUseActive()&&(harness?wear.harnessed():wear.swaddled())){
            return perform(player,target,removal);
        }
        String reason=harness?petRefusal(player,target):ownsPet(player,target)?null:"owner";
        if(reason!=null){
            refuse(player,target,removal.key(),reason);
            return false;
        }
        return harness?wear.putOnHarness(player,stack):wear.putOnSwaddle(player,stack);
    }
    public static boolean hangAccessory(Player player,YukkuriEntity target,InteractionHand hand){
        if(!requireReach(player,target,5))return false;
        var stack=player.getItemInHand(hand);
        var reason=operationRefusal(target);
        if(reason==null&&!AccessoryItem.matches(target,stack))reason="accessory_fit";
        var support=target.operations().ceiling();
        if(reason==null&&support==null)reason="ceiling";
        if(reason==null&&AccessoryItem.suspend(player,stack,support)){
            target.operations().finishHanging(player,hand);
            return true;
        }
        refuse(player,target,"hang_accessory",reason==null?"condition":reason);
        return false;
    }
    public static boolean allowCapture(Player player,YukkuriEntity target){
        if(!requireReach(player,target,4))return false;
        if (ExamSession.running(target)) { refuse(player, target, "capture", "busy"); return false; }
        if(HumanCommerce.mayCapture(player,target))return true;
        refuse(player,target,"capture","custody");
        return false;
    }
    public static boolean assist(Player player,YukkuriEntity target,InteractionHand hand){
        if(!requireReach(player,target,1.5))return false;
        var reason=operationRefusal(target);
        if(reason==null&&target.operations().beginAssistance(player,hand))return true;
        refuse(player,target,"assist",reason==null?"condition":reason);
        return false;
    }
    /** Vanilla naming and leashing, as well as held-item interactions, enter through this event. */
    public static void interact(PlayerInteractEvent.EntityInteract event){
        if(event.getLevel().isClientSide()||!(event.getTarget() instanceof YukkuriEntity target))return;
        var player=event.getEntity();
        if(!requireReach(player,target,Math.max(5,player.entityInteractionRange()))){
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        } else if (ExamSession.running(target)) {
            refuse(player, target, "exam", "busy");
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }else if(event.getItemStack().is(Items.LEAD)&&!target.isLeashed()){
            String reason=!ownsPet(player,target)?"owner":!target.pet().care().harnessed()?"harness"
                    :target.pet().busy()?"busy":target.isPassenger()?"carried":null;
            if(reason!=null){
                refuse(player,target,"leash",reason);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        }
    }
    public static void openCare(ServerPlayer player,YukkuriEntity target){
        if(requireReach(player,target,5))open(player,target,false);
    }
    public static void openCutter(ServerPlayer player,YukkuriEntity target){
        if(requireReach(player,target,5))open(player,target,true);
    }
    private static void open(ServerPlayer player,YukkuriEntity target,boolean cutter){
        var lineage=cutter?null:CareLineage.of(player.serverLevel(),target);
        player.openMenu(new SimpleMenuProvider((id,inventory,owner)->new CareMenu(id,inventory,target.getId(),cutter,lineage),
                cutter?Component.translatable("item.mcyukkuri.cutting_knife"):target.getDisplayName()),buffer->{
            buffer.writeVarInt(target.getId());
            buffer.writeBoolean(cutter);
            if(!cutter)lineage.write(buffer);
        });
    }

    public static String operationRefusal(YukkuriEntity target){
        return target.operations().ready() ? null : "busy";
    }
    static String refusal(Player player,YukkuriEntity target,CareAction action){
        double range=switch(action){
            case RUB -> 1.5;
            case ADOPT, ABANDON, DECLINE_EVENT, SING, EXERCISE -> 4;
            default -> 5;
        };
        String reason=reachRefusal(player,target,range);
        if(reason!=null)return reason;
        var operation=target.operations();
        if(action==CareAction.RELEASE)return operation.canRelease()?null:"no_operation";
        reason=operationRefusal(target);
        if(reason!=null)return reason;
        if(action.tool!=null&&!player.getMainHandItem().is(action.tool.item()))return "tool";
        if(action==CareAction.ASK||action==CareAction.ABANDON||action==CareAction.REMOVE_HARNESS||action==CareAction.UNWRAP){
            if(!ownsPet(player,target))return "owner";
        }
        if(action==CareAction.ASK&&target.pet().busy())return "busy";

        var life=target.life();
        var personality=life.personality();
        return switch(action){
            case LIFT -> target.isPanicking()||!Bonds.acceptsLift(target,player.getUUID())?"fear":null;
            case RUB -> target.isPanicking()||personality.fear(player.getUUID())>=BondLevels.AFFECTION_FEAR_LIMIT?"fear"
                    : life.hasUrgentNeed()||life.isSleeping()||life.mealTicks()>0||!personality.canTalk()?"busy":null;
            case COLD -> target.social().busyForTalk()||!personality.canTalk()?"busy":null;
            case FAVOR, DISLIKE -> personality.canTalk()?null:"busy";
            case ROLL, LAY_DOWN, FIX_SUPINE, ENCLOSE -> target.onGround()&&operation.hasRoom()?null:"space";
            case PIN -> target.onGround()?null:"space";
            case SHOW_RUMP, LIE_COMMAND -> !personality.knowsWait()||personality.trust(player.getUUID())<BondLevels.TRUST_FAMILIAR?"command"
                    : target.isPanicking()?"fear":!operation.hasRoom()&&action==CareAction.LIE_COMMAND?"space":null;
            case SHAKE_HOME -> operation.residents(player).isEmpty()?"home":null;
            case BURN -> OperationToolItem.isHot(player.getMainHandItem(),target.level())?null:"heat";
            case WRAP_HANG, HOOK_HANG -> operation.ceiling()==null?"ceiling":null;
            case TAKE_ACCESSORY, CONFISCATE, CUT_ACCESSORY -> life.hasAccessory()||target.headwear().reassembling()?null:"accessory";
            case COLLECT_NEAREST -> operation.canCollect(player)?null:"collect";
            case ASK -> target.pet().care().canAsk()?null:"busy";
            case ADOPT -> !target.status().identity().free()?"identity":!WildEvents.canAdoptHere(target,player)?"adopt":null;
            case ABANDON -> WildEvents.canAbandon(target,player)?null:"busy";
            case DECLINE_EVENT -> target.social().canDismissFoodRequest(player) || WildEvents.canDecline(target,player) ? null : "request";
            case SING, EXERCISE -> target.dailyLife().canInvite(player,action==CareAction.SING?DailyLife.Kind.SING:DailyLife.Kind.EXERCISE)?null:"leisure";
            case REMOVE_HARNESS -> target.pet().care().harnessed()?null:"harness";
            case UNWRAP -> target.pet().care().swaddled()?null:"swaddle";
            case TRIM_HAIR, CUT_LEFT, CUT_RIGHT -> (~target.body().hairLoss()&action.hairParts())!=0?null:"hair";
            case VIBRATE, STERILIZE -> !target.family().adult()||target.family().sterile()
                    ||action==CareAction.STERILIZE&&target.family().stimulation()==0?"condition":null;
            default -> null;
        };
    }
    public static boolean perform(Player player,YukkuriEntity target,CareAction action){
        var reason=refusal(player,target,action);
        if(reason==null){
            if(target.operations().perform(player,action))return true;
            reason="condition";
        }
        refuse(player,target,action.key(),reason);
        return false;
    }
}
