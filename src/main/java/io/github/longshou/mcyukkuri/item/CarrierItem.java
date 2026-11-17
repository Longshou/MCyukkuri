package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.event.HomeClaim;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class CarrierItem extends Item {
    public enum Grade {
        PLAIN(4), FINE(6), LARGE(12);
        private final int capacity;
        Grade(int capacity) { this.capacity = capacity; }
    }
    private enum Admission { WILLING, UNCONSCIOUS, WEAK, REFUSED }
    private static final Map<String, Integer> STAGE_LOAD = Map.of("adult", 4, "subadult", 2, "juvenile", 2, "baby", 1);
    private final Grade grade;

    public CarrierItem(Properties properties, Grade grade) {
        super(properties);
        this.grade = grade;
    }

    private static int load(YukkuriWorldData world, ItemStack stack) {
        return CarrierContents.tokens(stack).stream().map(world::captiveSnapshot)
                .mapToInt(snapshot -> STAGE_LOAD.get(CarrierContents.stage(snapshot))).sum();
    }

    private static void prepareBasket(YukkuriWorldData world, ItemStack stack) {
        CarrierContents.upgradeLegacy(stack, world);
        for (UUID token : CarrierContents.tokens(stack)) {
            if (world.hasToken(token) && !world.freezer.contains(token)) continue;
            CarrierContents.remove(stack, token);
        }
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        if (level instanceof ServerLevel server && CarrierContents.hasLegacyToken(stack))
            CarrierContents.upgradeLegacy(stack, YukkuriWorldData.get(server));
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
        if (load(world, stack) + STAGE_LOAD.get(entity.family().stageId()) > grade.capacity) {
            InteractionDispatch.refuse(player, entity, "capture", "carrier_full");
            return InteractionResult.FAIL;
        }
        var contact = SocialDisposition.contact(entity, player);
        float weakness = entity.life().weakness();
        double resistance = resistance(contact);
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
    private static double resistance(SocialDisposition.Contact contact) {
        // A timid body yields to moderate weakness. Defiance and esteem can shift that threshold up to .65.
        return .35 + .15 * contact.insistence() + .10 * contact.esteem() / 100 + .05 * (1 - contact.fear() / 100.0);
    }

    private static Admission admission(Player player, YukkuriEntity entity, float weakness, double resistance) {
        if (entity.life().drugSleep() > 0) return Admission.UNCONSCIOUS;
        if (willing(player, entity)) return Admission.WILLING;
        return weakness > resistance ? Admission.WEAK : Admission.REFUSED;
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
                response == SocialDisposition.Response.GRATEFUL ? "request_thanks" : "basket_enter", SCENE_WINDOW);
        ShopPerformance.schedule(level, entity.getUUID(), age -> {
            if (age < 16) return false;
            if (!finishScene(entity.getUUID(), session)) return true;
            var reason = InteractionDispatch.reachRefusal(player, entity, 4);
            if (reason == null) {
                var admission = admission(player, entity, entity.life().weakness(), resistance(SocialDisposition.contact(entity, player)));
                reason = admission == Admission.REFUSED ? entity.isPanicking() ? "fear" : "condition"
                        : capture(level, player, hand, stack, entity, admission);
            }
            if(reason!=null)InteractionDispatch.refuse(player,entity,"capture",reason);
            return true;
        });
    }
    private static void refuse(ServerLevel level, Player player, YukkuriEntity entity, SocialDisposition.Contact contact,
                               float weakness, double resistance) {
        var custody = Custody.view(entity);
        GameplayLog.limited(entity, "carrier", "unwilling", 40, "action=capture result=refused target={} player={} reason=unwilling weakness={} resistance={} trust={} fear={} spray_delay={} panic={} fire={} hurt_ticks={} predator_active={} owner={} role={}",
                entity.getUUID(), player.getUUID(), weakness, resistance, contact.closeness(), contact.fear(), entity.life().sprayDelay(),
                entity.isPanicking(), entity.isOnFire(), entity.hurtTime, entity.predator().active(), custody.owner(), custody.role());
        var session = scene(level, entity.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.refuse", entity.getName()), true);
            return;
        }
        var response = contact.response(false);
        boolean mobile = canReact(entity);
        boolean weary = weakness >= YukkuriLife.WEAKNESS_WARNING;
        if (mobile && !weary && response != SocialDisposition.Response.SULKY) {
            entity.showReaction(response == SocialDisposition.Response.GUARDED ? YukkuriAction.TREMBLE : YukkuriAction.STRUGGLE,
                    response == SocialDisposition.Response.GUARDED ? "teary" : "angry", "basket_refuse");
        } else if (mobile) entity.showReaction("look_down", "basket_refuse", SCENE_WINDOW);
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
                if (away != null) entity.life().navigate(BlockPos.containing(away), 1.1, false);
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
        int added = STAGE_LOAD.get(entity.family().stageId());
        if (before + added > grade.capacity) return "carrier_full";
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
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.captured", entity.getName()), true);
        GameplayLog.event(player,"carrier","action=capture result=committed target={} token={} load={}->{} capacity={} admission={}",
                entity.getUUID(),token,before,before + added,grade.capacity,admission);
        return null;
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var stack = context.getItemInHand();
        if (CarrierContents.tokens(stack).isEmpty()) return InteractionResult.PASS;
        if (context.getLevel() instanceof ServerLevel level) {
            var world = YukkuriWorldData.get(level);
            prepareBasket(world, stack);
            var tokens = CarrierContents.tokens(stack);
            if (tokens.isEmpty()) return blocked(context);
            UUID token = tokens.getFirst();
            var pos = context.getClickedPos().relative(context.getClickedFace());
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
                GameplayLog.event(context.getPlayer(),"carrier","action=release result=refused token={} destination={} reason={}",token,pos.toShortString(),!level.hasChunkAt(pos)?"unloaded":"unsupported_ground");
                return blocked(context);
            }
            YukkuriEntity entity = world.releaseOnGround(token, level, Vec3.atBottomCenterOf(pos), context.getRotation());
            if (entity == null) return blocked(context);
            if (!entity.life().sedated()) { entity.playAction("basket_out"); entity.speak("basket_exit"); } // a sedated animal simply keeps sleeping
            CarrierContents.remove(stack, token);
            GameplayLog.event(context.getPlayer(),"carrier","action=release result=committed target={} token={} remaining={}",entity.getUUID(),token,tokens.size() - 1);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    private static InteractionResult blocked(UseOnContext context) {
        if (context.getPlayer() != null) context.getPlayer().displayClientMessage(Component.translatable("care.mcyukkuri.carrier.blocked"), true);
        return InteractionResult.FAIL;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("care.mcyukkuri.carrier.capacity", grade.capacity,
                STAGE_LOAD.get("adult"), STAGE_LOAD.get("juvenile"), STAGE_LOAD.get("baby")));
        CarrierContents.appendOccupants(stack, context.registries(), tooltip);
        tooltip.add(Component.translatable(CarrierContents.tokens(stack).isEmpty() ? "care.mcyukkuri.carrier.empty" : "care.mcyukkuri.carrier.full"));
    }
}
