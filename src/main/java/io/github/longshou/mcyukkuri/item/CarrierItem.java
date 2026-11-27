package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.event.HomeClaim;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.interaction.RestraintTableBlockEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.PlantReceiving;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class CarrierItem extends Item {
    private enum Admission { WILLING, UNCONSCIOUS, WEAK, REFUSED }
    private final CarrierLoad.Grade grade;

    public CarrierItem(Properties properties, CarrierLoad.Grade grade) {
        super(properties);
        this.grade = grade;
    }

    private static int load(YukkuriWorldData world, ItemStack stack) {
        return CarrierContents.tokens(stack).stream().map(world::captiveSnapshot)
                .mapToInt(snapshot -> CarrierLoad.stageLoad(CarrierContents.stage(snapshot))).sum();
    }

    static void prepareBasket(YukkuriWorldData world, ItemStack stack) {
        if (CarrierContents.needsAppearance(stack)) CarrierContents.refreshAppearance(stack, world);
        for (UUID token : CarrierContents.tokens(stack)) {
            if (world.hasToken(token) && !world.freezer.contains(token)) continue;
            CarrierContents.remove(stack, token);
        }
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        if (level instanceof ServerLevel server && CarrierContents.needsAppearance(stack))
            CarrierContents.refreshAppearance(stack, YukkuriWorldData.get(server));
    }

    /** Old baskets can already be on the ground or in a frame when the world is loaded. */
    public static void joining(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof ItemEntity item) {
            var stack = item.getItem();
            if (CarrierContents.isBasket(stack) && CarrierContents.needsAppearance(stack)) {
                var copy = stack.copy();
                if (CarrierContents.refreshAppearance(copy, YukkuriWorldData.get(level))) item.setItem(copy);
            }
        } else if (event.getEntity() instanceof ItemFrame frame) {
            var stack = frame.getItem();
            if (CarrierContents.isBasket(stack) && CarrierContents.needsAppearance(stack)) {
                var copy = stack.copy();
                if (CarrierContents.refreshAppearance(copy, YukkuriWorldData.get(level))) frame.setItem(copy, false);
            }
        }
    }

    @Override public Component getName(ItemStack stack) {
        int count = CarrierContents.tokens(stack).size();
        var name = super.getName(stack);
        return count == 0 ? name : Component.translatable("care.mcyukkuri.carrier.loaded_name", name, count);
    }
    /** Click suppression follows game time; callback identity survives natural expiry until its scheduled age. */
    private static final int SCENE_WINDOW = 24;
    private static final LeaseTable<UUID, Object> SCENES = new LeaseTable<>(SCENE_WINDOW);
    private static final Map<UUID, Object> RUNNING = new HashMap<>();
    public static void resetScenes() { SCENES.clear(); RUNNING.clear(); }
    public static void forget(Set<UUID> ids) {
        SCENES.removeKeys(ids::contains); RUNNING.keySet().removeAll(ids);
    }
    private static Object scene(ServerLevel level, UUID id) {
        long now = level.getGameTime();
        if (!SCENES.free(List.of(id), now)) return null;
        if (SCENES.size() > 64) SCENES.prune(now);
        var session = new Object();
        if (!SCENES.acquireAll(List.of(id), session, now + SCENE_WINDOW, now)) return null;
        RUNNING.put(id, session); return session;
    }
    private static boolean finishScene(UUID id, Object session) {
        if (!RUNNING.remove(id, session)) return false;
        SCENES.release(id, session); return true;
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        // Vanilla passes a disposable copy here in creative mode. Custody must reach the actual hand.
        stack = player.getItemInHand(hand);
        if (stack.getCount() != 1) {
            InteractionDispatch.refuse(player,entity,"capture","single_carrier");
            return InteractionResult.FAIL;
        }
        if (!InteractionDispatch.allowCapture(player,entity)) return InteractionResult.FAIL;
        var level=(ServerLevel)player.level();
        var world = YukkuriWorldData.get(level);
        prepareBasket(world, stack);
        if (!CarrierLoad.fits(grade, load(world, stack), CarrierLoad.stageLoad(entity.family().stageId()))) {
            InteractionDispatch.refuse(player, entity, "capture", "carrier_full");
            return InteractionResult.FAIL;
        }
        var contact = SocialDisposition.contact(entity, player);
        float weakness = entity.life().weakness();
        double resistance = CarrierLoad.resistance(contact.insistence(), contact.esteem(), contact.fear());
        var admission = admission(player, entity, weakness, resistance);
        if (admission == Admission.WILLING) welcome(level, player, hand, stack, entity, contact);
        else if (admission == Admission.REFUSED) refuse(level, player, entity, contact, weakness, resistance);
        else {
            var reason = capture(level, player, hand, stack, entity, admission);
            if (reason != null) {
                InteractionDispatch.refuse(player,entity,"capture",reason);
                return InteractionResult.FAIL;
            }
        }
        return InteractionResult.CONSUME;
    }
    private static Admission admission(Player player, YukkuriEntity entity, float weakness, double resistance) {
        // A body frozen stiff is lifted like one that is out cold: it neither agrees nor resists.
        if (entity.life().drugSleep() > 0 || entity.coldStiff()) return Admission.UNCONSCIOUS;
        if (willing(player, entity)) return Admission.WILLING;
        return CarrierLoad.overpowered(weakness, resistance) ? Admission.WEAK : Admission.REFUSED;
    }

    private static boolean willing(Player player, YukkuriEntity entity) {
        var commerce = Custody.view(entity); UUID id = player.getUUID();
        var personality = entity.life().personality();
        boolean known = commerce.ownedBy(id) && personality.trust(id) >= BondLevels.AFFINITY_NEUTRAL
                && personality.fear(id) < BondLevels.AFFECTION_FEAR_LIMIT
                || Bonds.trustedPlayer(entity, id);
        return known && !entity.isPanicking() && !entity.isOnFire() && entity.hurtTime == 0 && !entity.predator().active();
    }
    private void welcome(ServerLevel level, Player player, InteractionHand hand, ItemStack stack, YukkuriEntity entity,
                         SocialDisposition.Contact contact) {
        var session = scene(level, entity.getUUID());
        if (session == null) {
            InteractionDispatch.refuse(player,entity,"capture","busy");
            return;
        }
        GameplayLog.event(player,"carrier","action=capture result=accepted target={} completion=pending",entity.getUUID());
        var response = contact.response(true);
        entity.playAction("basket_in");
        entity.showReaction(response == SocialDisposition.Response.GUARDED ? "teary" : "closed_smile",
                response == SocialDisposition.Response.GRATEFUL ? "request_thanks" : "basket_enter", FaceTier.Tier.ofTicks(SCENE_WINDOW));
        ShopPerformance.schedule(level, entity.getUUID(), age -> {
            if (age < 16) return false;
            if (!finishScene(entity.getUUID(), session)) return true;
            var reason = InteractionDispatch.reachRefusal(player, entity, 4);
            if (reason == null) {
                var now = SocialDisposition.contact(entity, player);
                var admission = admission(player, entity, entity.life().weakness(),
                        CarrierLoad.resistance(now.insistence(), now.esteem(), now.fear()));
                reason = admission == Admission.REFUSED ? entity.isPanicking() ? "fear" : "condition"
                        : capture(level, player, hand, stack, entity, admission);
            }
            if(reason!=null)InteractionDispatch.refuse(player,entity,"capture",reason);
            return true;
        });
    }
    private static void refuse(ServerLevel level, Player player, YukkuriEntity entity, SocialDisposition.Contact contact,
                               float weakness, double resistance) {
        int suppressed = GameplayLog.admit(entity, "carrier", "unwilling", 40);
        if (suppressed >= 0) {
            var custody = Custody.view(entity);
            GameplayLog.limitedEvent(entity, "carrier", suppressed, "action=capture result=refused target={} player={} reason=unwilling weakness={} resistance={} trust={} fear={} spray_delay={} panic={} fire={} hurt_ticks={} predator_active={} owner={} role={}",
                    entity.getUUID(), player.getUUID(), weakness, resistance, contact.closeness(), contact.fear(), entity.life().sprayDelay(),
                    entity.isPanicking(), entity.isOnFire(), entity.hurtTime, entity.predator().active(), custody.owner(), custody.role());
        }
        var session = scene(level, entity.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.refuse", entity.getName()), true);
            return;
        }
        var response = contact.response(false);
        boolean mobile = canReact(entity);
        boolean weary = weakness >= YukkuriLife.WEAKNESS_WARNING;
        if (mobile && !weary && response != SocialDisposition.Response.SULKY) {
            if (response == SocialDisposition.Response.GUARDED) entity.showReaction(YukkuriAction.TREMBLE, "teary", "basket_refuse_guarded");
            else entity.showReaction(YukkuriAction.STRUGGLE, "angry", "basket_refuse_defiant");
        } else if (mobile) entity.showReaction("look_down", ReactionSpeech.basketRefusal(weary), FaceTier.Tier.ofTicks(SCENE_WINDOW));
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.refuse", entity.getName()), true);
        GameplayLog.event(entity, "carrier", "capture reaction player={} response={} esteem={} wariness={} insistence={} nutrition={} mobile={}",
                player.getUUID(), !mobile ? "busy" : weary ? "weary" : response.id(),
                contact.esteem(), contact.wariness(), contact.insistence(), entity.life().nutrition(), mobile);
        ShopPerformance.schedule(level, entity.getUUID(), age -> {
            if (age < 20) return false;
            if (!finishScene(entity.getUUID(), session)) return true;
            if (mobile && !weary && canReact(entity) && entity.life().weakness() < YukkuriLife.WEAKNESS_WARNING
                    && InteractionDispatch.reachRefusal(player, entity, 4) == null) {
                var away = Reactions.escapeStand(entity, player, 5, Set.of());
                if (away != null) entity.life().retreatTo(BlockPos.containing(away), 1.1);
            }
            return true;
        });
    }

    private static boolean canReact(YukkuriEntity entity) {
        return entity.isAlive() && !entity.isPanicking() && !entity.isPassenger() && !entity.isVehicle() && entity.onGround()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.family().active() && !entity.life().isSleeping()
                && entity.life().mealTicks() == 0 && !entity.body().dying() && entity.hurtTime == 0;
    }
    /** A voluntary entry rechecks the original basket and its current room at handoff. */
    private String capture(ServerLevel level, Player player, InteractionHand hand, ItemStack stack, YukkuriEntity entity,
                           Admission admission) {
        if (player.getItemInHand(hand) != stack || !entity.isAlive()) return "condition";
        if (stack.getCount() != 1) return "single_carrier";
        if (!HumanCommerce.mayCapture(player, entity)) return "custody";
        var world = YukkuriWorldData.get(level);
        prepareBasket(world, stack);
        int before = load(world, stack);
        int added = CarrierLoad.stageLoad(entity.family().stageId());
        if (!CarrierLoad.fits(grade, before, added)) return "carrier_full";
        var previousCustody = HumanCommerce.data(entity).copy();
        var role = Custody.fromData(previousCustody).role();
        if (role == Custody.Role.PLANT_STOCK && Custody.harmRefusal(player, entity, entity.blockPosition()) != null) return "custody";
        if (role == Custody.Role.PLANT_STOCK && (entity.operations().active() || InteractionDispatch.contactRefusal(entity) != null)) return "condition";
        if (role != Custody.Role.LOST && !PlantReceiving.acceptsConsignment(level, entity))
            Custody.assign(entity, player, Custody.Role.COMPANION);
        UUID token = world.capture(entity, () -> HomeClaim.displaced(entity,player));
        if (token == null) {
            Custody.restore(entity, previousCustody);
            GameplayLog.event(player,"carrier","action=capture result=rolled_back target={} reason=capture_failed",entity.getUUID());
            return "condition";
        }
        CarrierContents.add(stack, token, world.captiveSnapshot(token));
        if (entity.coldStiff()) entity.beatLast("stiff_lift", "");
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.captured", entity.getName()), true);
        GameplayLog.event(player,"carrier","action=capture result=committed target={} token={} load={}->{} capacity={} admission={} stiff={}",
                entity.getUUID(),token,before,before + added,grade.capacity(),admission,entity.coldStiff());
        return null;
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (CarrierContents.tokens(stack).isEmpty()) return InteractionResultHolder.pass(stack);
        if (level instanceof ServerLevel server)
            CarrierRelease.click(server, player, stack, hand, CarrierRelease.Target.FRONT, BlockPos.ZERO, player.getYRot());
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var stack = context.getItemInHand();
        if (CarrierContents.tokens(stack).isEmpty()) return InteractionResult.PASS;
        if (context.getLevel() instanceof ServerLevel level) {
            boolean table = level.getBlockEntity(context.getClickedPos()) instanceof RestraintTableBlockEntity;
            if (table && context.getPlayer() == null) return InteractionResult.PASS;
            CarrierRelease.click(level, context.getPlayer(), stack, context.getHand(),
                    table ? CarrierRelease.Target.TABLE : CarrierRelease.Target.GROUND,
                    table ? context.getClickedPos() : context.getClickedPos().relative(context.getClickedFace()),
                    context.getRotation());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("care.mcyukkuri.carrier.capacity", grade.capacity(),
                CarrierLoad.stageLoad("adult"), CarrierLoad.stageLoad("juvenile"), CarrierLoad.stageLoad("baby")));
        CarrierContents.appendOccupants(stack, context.registries(), tooltip);
        tooltip.add(Component.translatable(CarrierContents.tokens(stack).isEmpty() ? "care.mcyukkuri.carrier.empty" : "care.mcyukkuri.carrier.full"));
    }
}
