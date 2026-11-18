package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.interaction.RestraintTableBlock;
import io.github.longshou.mcyukkuri.interaction.RestraintTableBlockEntity;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.Naming;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.shop.ShopStaff;
import io.github.longshou.mcyukkuri.shop.ShopTrade;
import io.github.longshou.mcyukkuri.social.ProtectivePuff;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Works the visible box stock with the actual tools in the cabinet and in hand. */
final class PlantSweetening extends Goal {
    private static final int SCAN_INTERVAL = 11;
    // Give the animals time to react, settle and return to their own activities after an actual contact.
    private static final int CONTACT_RECOVERY = 1000;
    private static final double REACH = 3;
    private static final double CARRY_REACH = 2.5;
    private static final float EASE_OFF = 72, TREAT_LATER = .75f;
    private static final int RICH_FILLING = 75;
    private static final int SWEET_FILLING = 65;

    private final HumanNpc staff;
    private Job job;
    private long nextPressure;

    private record Job(Path path, Runnable contact, Approach approach) {
        Job(Path path, Runnable contact) { this(path, contact, null); }
    }
    private record Approach(PlantSite.Box box, Set<UUID> witnesses, long startedAt) {}
    private record Candidate(YukkuriEntity body, PlantSite.Box box, double need) {}

    PlantSweetening(HumanNpc staff) {
        this.staff = staff;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    static void equip(HumanNpc staff, ItemStack basket) {
        staff.setItemInHand(InteractionHand.OFF_HAND, basket);
        staff.setDropChance(EquipmentSlot.OFFHAND, 2);
    }

    static InteractionResult receiveBasket(HumanNpc staff, Player player, InteractionHand hand) {
        var basket = player.getItemInHand(hand);
        if (!staff.getOffhandItem().isEmpty() || !CarrierContents.isBasket(basket) || !CarrierContents.tokens(basket).isEmpty())
            return InteractionResult.PASS;
        if (!staff.level().isClientSide) {
            equip(staff, basket.split(1));
            GameplayLog.event(staff, "factory", "action=table_basket result=received player={}", player.getUUID());
        }
        return InteractionResult.sidedSuccess(staff.level().isClientSide);
    }

    static void openingStock(ServerLevelAccessor level, UUID site, String box, BlockPos position) {
        var variant = switch (box) {
            case "B" -> YukkuriVariant.REIMU;
            case "C" -> YukkuriVariant.MARISA;
            case "D" -> YukkuriVariant.ALICE;
            default -> throw new IllegalArgumentException(box);
        };
        var animal = ModEntities.forVariant(variant).create(level.getLevel());
        Motor.relocate(animal, Vec3.atBottomCenterOf(position), 0, "plant_opening_stock");
        animal.finalizeSpawn(level, level.getCurrentDifficultyAt(position), MobSpawnType.STRUCTURE, null);
        animal.setPersistenceRequired();
        animal.mind().set(switch (box) { case "B" -> 53; case "C" -> 68; default -> 80; });
        animal.mind().setSweetness(switch (box) { case "B" -> 5; case "C" -> 20; default -> 42; });
        Custody.assignRaw(animal, site, Custody.OwnerKind.NPC, Custody.Role.PLANT_STOCK.id());
        if (level.addFreshEntity(animal)) GameplayLog.event(animal, "factory", "action=opening_stock box={} site={} unease={} sweet={}",
                box, site, animal.mind().unease(), animal.mind().fillingSweetness());
    }

    @Override public boolean canUse() {
        if (!staff.sweeteningStaff() || !staff.onGround() || staff.hurtTime > 0 || staff.tickCount % SCAN_INTERVAL != 0
                || !(staff.level() instanceof ServerLevel level)) return false;
        if (CarrierContents.singleToken(staff.getOffhandItem()) != null) {
            job = new Job(null, () -> abortCarry(level, "interrupted"));
            return true;
        }
        var layout = PlantSite.layout(level, staff.plantSite());
        if (layout == null) return false;
        var candidates = new ArrayList<Candidate>();
        for (var box : layout.boxes()) {
            var stock = level.getEntitiesOfClass(YukkuriEntity.class, AABB.of(box.bounds()).expandTowards(0, 1, 0),
                    animal -> owns(animal) && box.floor().contains(BlockPos.containing(animal.getX(), box.door().getY(), animal.getZ())));
            updateReadout(level, box, stock);
            for (var animal : stock) {
                if (staff.getUUID().equals(animal.operations().operator())) {
                    if (!animal.hasReaction() || animal.mind().unease() >= EASE_OFF || needsTreatment(animal)) {
                        if (touching(animal)) {
                            job = new Job(null, () -> staff.releaseTool(animal));
                            return true;
                        }
                        var path = contactPath(level, animal, box);
                        if (path != null && path.canReach()) {
                            job = new Job(path, () -> staff.releaseTool(animal));
                            return true;
                        }
                    }
                    continue;
                }
                if (level.getGameTime() < nextPressure || !visible(level, box, animal)) continue;
                if (needsTreatment(animal) && GlassSight.sees(staff, animal)) {
                    GameplayLog.limited(animal, "factory", "treat_later", 1200, "action=sweetening decision=treat_later staff={} health={} unease={}",
                            staff.getUUID(), animal.body().healthRatio(), animal.mind().unease());
                    continue;
                }
                double need = need(animal);
                if (need > 0) candidates.add(new Candidate(animal, box, need));
            }
        }

        if (staff.getMainHandItem().isEmpty() && recoverTool(level, layout)) return true;
        if (candidates.isEmpty()) return false;
        var cabinet = level.getBlockEntity(layout.tools()) instanceof ChestBlockEntity chest ? chest : null;
        candidates.sort(Comparator.comparingDouble(Candidate::need).reversed());
        for (var candidate : candidates) if (planPressure(level, layout.tools(), cabinet, candidate)) return true;
        return false;
    }

    private boolean planPressure(ServerLevel level, BlockPos tools, ChestBlockEntity cabinet, Candidate candidate) {
        var body = candidate.body();
        var box = candidate.box();
        var held = staff.getMainHandItem();
        var preferred = preferredTool(body);
        boolean hasBasket = CarrierContents.isBasket(staff.getOffhandItem()) && !body.isVehicle();
        if (hasBasket && (preferred == ModItems.RESTRAINT.get() || !usable(held, body))) {
            var table = box.floor().stream().map(level::getBlockEntity)
                    .filter(RestraintTableBlockEntity.class::isInstance).map(RestraintTableBlockEntity.class::cast)
                    .filter(at -> at.occupant() == null && at.fits(body)).findFirst().orElse(null);
            if (table != null && planTable(level, box, body, table)) return true;
        }
        boolean inReach = touching(body);
        var path = inReach ? null : contactPath(level, body, box);
        if (!inReach && (path == null || !path.canReach() || path.getTarget().equals(staff.blockPosition()))) return false;
        if (held.is(ModItems.RESTRAINT.get()) && hasBasket) {
            job = new Job(path, () -> deployTable(level, box, body));
            return true;
        }
        int slot = cabinet == null ? -1 : toolSlot(cabinet, preferred, body);
        if (!held.is(preferred) && slot >= 0 && getTool(level, tools, slot, preferred)) return true;
        if (!usable(held, body)) {
            if (cabinet == null) return false;
            for (int i = 0; i < cabinet.getContainerSize(); i++) {
                var stack = cabinet.getItem(i);
                if (usable(stack, body) || hasBasket && stack.is(ModItems.RESTRAINT.get()))
                    return getTool(level, tools, i, stack.getItem());
            }
            return false;
        }
        job = new Job(path, () -> apply(box, body),
                path != null && held.getItem() instanceof OperationToolItem ? new Approach(box, new HashSet<>(), level.getGameTime()) : null);
        return true;
    }

    private boolean planTable(ServerLevel level, PlantSite.Box box, YukkuriEntity body, RestraintTableBlockEntity table) {
        boolean inReach = InteractionDispatch.reachRefusal(staff, body, CARRY_REACH) == null;
        var path = inReach ? null : contactPath(level, body, box, CARRY_REACH);
        if (!inReach && (path == null || !path.canReach() || path.getTarget().equals(staff.blockPosition()))) return false;
        job = new Job(path, () -> carryToTable(level, box, body, table), new Approach(box, new HashSet<>(), level.getGameTime()));
        return true;
    }

    private void deployTable(ServerLevel level, PlantSite.Box box, YukkuriEntity body) {
        var stack = staff.getMainHandItem();
        if (!stack.is(ModItems.RESTRAINT.get()) || !touching(body) || need(body) <= 0) return;
        var cell = box.floor().stream().filter(pos -> !pos.equals(box.door()) && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && ShopStaff.reaches(staff, RestraintTableBlock.surface(pos), pos)
                && level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), LivingEntity::isAlive).isEmpty())
                .min(Comparator.comparingDouble(pos -> body.distanceToSqr(RestraintTableBlock.surface(pos)))).orElse(null);
        if (cell == null) {
            nextPressure = level.getGameTime() + CONTACT_RECOVERY;
            GameplayLog.event(staff, "factory", "action=table_deploy result=refused reason=no_clear_cell");
            return;
        }
        if (!level.setBlock(cell, ModBlocks.RESTRAINT.get().defaultBlockState(), Block.UPDATE_ALL)) return;
        stack.shrink(1);
        Motor.face(staff, RestraintTableBlock.surface(cell));
        staff.swing(InteractionHand.MAIN_HAND);
        GameplayLog.event(staff, "factory", "action=table_deploy result=placed table={} held={}", cell, stack.getCount());
    }

    private void carryToTable(ServerLevel level, PlantSite.Box box, YukkuriEntity body, RestraintTableBlockEntity table) {
        var at = table.getBlockPos();
        var surface = RestraintTableBlock.surface(at);
        if (InteractionDispatch.reachRefusal(staff, body, CARRY_REACH) != null || need(body) <= 0 || !box.bounds().isInside(body.blockPosition())
                || table.isRemoved() || table.occupant() != null || !table.fits(body)) return;
        boolean here = ShopStaff.reaches(staff, surface, at) && staff.position().distanceToSqr(surface) <= 2.25;
        Path route = null;
        if (!here) {
            var stands = new HashSet<BlockPos>();
            for (var direction : Direction.Plane.HORIZONTAL) {
                var stand = at.relative(direction);
                if (box.floor().contains(stand)) stands.add(stand);
            }
            route = stands.isEmpty() ? null : staff.shopPath(stands, new AABB(at));
            if (route == null || !route.canReach() || !staff.getNavigation().moveTo(route, .9)) {
                nextPressure = level.getGameTime() + CONTACT_RECOVERY;
                GameplayLog.event(staff, "factory", "action=table_carry result=refused target={} reason=no_route", body.getUUID());
                return;
            }
        }
        var basket = staff.getOffhandItem();
        var world = YukkuriWorldData.get(level);
        float before = body.mind().unease();
        var token = world.capture(body);
        if (token == null) return;
        CarrierContents.add(basket, token, world.captiveSnapshot(token));
        staff.swing(InteractionHand.OFF_HAND);
        nextPressure = level.getGameTime() + CONTACT_RECOVERY;
        GameplayLog.event(staff, "factory", "action=table_carry result=packed target={} token={} table={}", body.getUUID(), token, GameplayLog.at(surface));
        job = new Job(route, () -> {
            if (!table.isRemoved() && staff.onGround() && ShopStaff.reaches(staff, surface, at)) {
                Motor.face(staff, surface);
                var result = table.placeFrom(staff, basket, token, staff.getYRot());
                if (result.animal() != null) {
                    staff.swing(InteractionHand.OFF_HAND);
                    GameplayLog.event(result.animal(), "factory", "action=table_carry result=placed staff={} token={} table={}", staff.getUUID(), token, GameplayLog.at(surface));
                    pressure(result.animal(), ModItems.RESTRAINT.get(), before);
                    return;
                }
            }
            abortCarry(level, "table_unreachable");
        });
    }

    private void abortCarry(ServerLevel level, String reason) {
        var basket = staff.getOffhandItem();
        var token = CarrierContents.singleToken(basket);
        if (token == null) return;
        var body = YukkuriWorldData.get(level).releaseOnGround(token, level, staff.position(), staff.getYRot(), 2);
        if (body != null) {
            CarrierContents.remove(basket, token);
            staff.swing(InteractionHand.OFF_HAND);
        } else if (ShopTrade.drop(level, null, staff.position(), basket) != null) {
            staff.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        GameplayLog.event(staff, "factory", "action=table_carry result={} token={} reason={}",
                body != null ? "released_to_floor" : staff.getOffhandItem().isEmpty() ? "basket_dropped" : "basket_held", token, reason);
    }

    private Path contactPath(ServerLevel level, YukkuriEntity body, PlantSite.Box box) {
        return contactPath(level, body, box, REACH);
    }

    private Path contactPath(ServerLevel level, YukkuriEntity body, PlantSite.Box box, double reach) {
        var stands = new HashSet<BlockPos>();
        for (var stand : box.floor()) {
            var feet = Vec3.atBottomCenterOf(stand);
            if (feet.distanceToSqr(body.position()) > reach * reach) continue;
            var ray = new ClipContext(feet.add(0, staff.getEyeHeight(), 0), body.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, staff);
            if (level.clip(ray).getType() == HitResult.Type.MISS) stands.add(stand);
        }
        return stands.isEmpty() ? null : staff.shopPath(stands, body.getBoundingBox());
    }

    private boolean visible(ServerLevel level, PlantSite.Box box, YukkuriEntity animal) {
        if (GlassSight.sees(staff, animal)) return true;
        // The bench-side sightline is through the front glass, including while the worker is fetching a tool.
        var eye = Vec3.atBottomCenterOf(box.lookout()).add(0, staff.getEyeHeight(), 0);
        return level.clip(new ClipContext(eye, animal.getEyePosition(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, staff)).getType() == HitResult.Type.MISS || GlassSight.glassBetween(staff, eye, animal) != null;
    }

    private boolean owns(YukkuriEntity animal) {
        var custody = Custody.view(animal);
        return animal.isAlive() && custody.role() == Custody.Role.PLANT_STOCK && custody.ownedBy(staff.plantSite());
    }

    private static boolean needsTreatment(YukkuriEntity animal) {
        return animal.body().dying() || animal.body().healthRatio() <= TREAT_LATER || animal.body().condition().painSeverity() >= 3;
    }

    private static boolean leaveToRest(YukkuriEntity body) {
        return needsTreatment(body) || body.mind().nonYukkuri() || body.mind().unease() >= EASE_OFF
                || body.mind().fillingSweetness() >= RICH_FILLING;
    }

    private double need(YukkuriEntity body) {
        if (leaveToRest(body) || !body.onGround() || body.hasReaction() || body.isCommandPlaying() || body.life().isSleeping()
                || body.life().hasUrgentNeed() || body.life().sedated() || InteractionDispatch.operationRefusal(body) != null
                || InteractionDispatch.contactRefusal(body) != null) return 0;
        var p = body.life().personality();
        float limit = switch (staff.attitude()) { case CRUEL -> 70; case NEUTRAL -> 67; case CARING -> 64; };
        return Math.min(EASE_OFF, limit + (p.boldness() - 50) * .08) - body.mind().unease()
                - Math.max(0, p.fear(staff.getUUID()) - 30) * .2 + (body.social().demandingFoodFrom(staff) ? 4 : 0);
    }

    private Item preferredTool(YukkuriEntity body) {
        if (body.social().demandingFoodFrom(staff)) return Tool.PIN.item();
        if (body.mind().unease() >= 62 && CarrierContents.isBasket(staff.getOffhandItem())) return ModItems.RESTRAINT.get();
        if (!soundForPuncture(body) || body.life().personality().fear(staff.getUUID()) >= 20) return Items.NAME_TAG;
        return Tool.AWL.item();
    }

    private static boolean soundForPuncture(YukkuriEntity body) {
        // Every staff puncture starts with a sound shell and enough filling to take the full two-point awl hit.
        return body.body().condition().painSeverity() == 0 && !body.body().coreExposed()
                && body.getHealth() - 2 > body.getMaxHealth() * .65f;
    }

    private boolean usable(ItemStack stack, YukkuriEntity body) {
        if (stack.is(Items.NAME_TAG)) {
            var name = stack.get(DataComponents.CUSTOM_NAME);
            return !body.hasCustomName() && name != null && !Naming.ownName(body, name.getString());
        }
        if (!(stack.getItem() instanceof OperationToolItem item)) return false;
        return switch (item.tool()) {
            case AWL -> soundForPuncture(body) && !OperationToolItem.isHot(stack, staff.level());
            case PIN -> soundForPuncture(body);
            default -> false;
        };
    }

    private boolean touching(YukkuriEntity body) { return InteractionDispatch.reachRefusal(staff, body, REACH) == null; }

    private void apply(PlantSite.Box box, YukkuriEntity body) {
        var tool = staff.getMainHandItem();
        if (!owns(body) || !box.bounds().isInside(body.blockPosition()) || !touching(body) || need(body) <= 0 || !usable(tool, body)) return;
        var item = tool.getItem();
        float unease = body.mind().unease();
        if (tool.is(Items.NAME_TAG)) Naming.useHeldTag(staff, body);
        else if (!staff.useHeldTool(body)) return;
        nextPressure = staff.level().getGameTime() + CONTACT_RECOVERY;
        pressure(body, item, unease);
    }

    private void pressure(YukkuriEntity body, Item item, float unease) {
        GameplayLog.event(body, "factory", "action=sweetening decision=pressure staff={} item={} unease={} -> {} health={} sweet={} fear={}",
                staff.getUUID(), item, unease, body.mind().unease(), body.body().healthRatio(), body.mind().sweetness(),
                body.life().personality().fear(staff.getUUID()));
    }

    private int toolSlot(ChestBlockEntity cabinet, Item item, YukkuriEntity body) {
        for (int i = 0; i < cabinet.getContainerSize(); i++) {
            var stack = cabinet.getItem(i);
            if (stack.is(item) && (stack.is(ModItems.RESTRAINT.get()) || usable(stack, body))) return i;
        }
        return -1;
    }

    private boolean getTool(ServerLevel level, BlockPos at, int slot, Item item) {
        Runnable swap = () -> {
            if (at.distSqr(staff.blockPosition()) > 1 || !(level.getBlockEntity(at) instanceof ChestBlockEntity cabinet)
                    || !cabinet.getItem(slot).is(item)) return;
            var taken = cabinet.getItem(slot);
            var returned = staff.getMainHandItem();
            cabinet.setItem(slot, returned);
            staff.setItemInHand(InteractionHand.MAIN_HAND, taken);
            staff.setDropChance(EquipmentSlot.MAINHAND, 2);
            Motor.face(staff, Vec3.atCenterOf(at));
            staff.swing(InteractionHand.MAIN_HAND);
            GameplayLog.event(staff, "factory", "action=tool_exchange at={} took={} returned={}", at.toShortString(), taken.getItem(), returned.getItem());
        };
        if (at.distSqr(staff.blockPosition()) <= 1) { job = new Job(null, swap); return true; }
        var stands = new HashSet<BlockPos>();
        for (var direction : Direction.Plane.HORIZONTAL) stands.add(at.relative(direction));
        return walk(staff.shopPath(stands, null), swap);
    }

    private boolean recoverTool(ServerLevel level, PlantSite.Layout layout) {
        var dropped = level.getEntitiesOfClass(ItemEntity.class, staff.getBoundingBox().inflate(6), item -> item.isAlive()
                && !item.hasPickUpDelay() && item.getItem().getItem() instanceof OperationToolItem tool && tool.tool().inserted && tool.tool().staffUse()
                && layout.boxes().stream().anyMatch(box -> box.bounds().isInside(item.blockPosition())));
        dropped.sort(Comparator.comparingDouble(staff::distanceToSqr));
        for (var item : dropped) {
            if (staff.hasLineOfSight(item) && staff.getBoundingBox().inflate(1.5, .5, 1.5).intersects(item.getBoundingBox())) {
                job = new Job(null, () -> staff.pickUpTool(item));
                return true;
            }
            if (walk(staff.shopPath(item.blockPosition()), () -> staff.pickUpTool(item))) return true;
        }
        return false;
    }

    private boolean walk(Path path, Runnable contact) {
        if (path == null || !path.canReach() || path.getTarget().equals(staff.blockPosition())) return false;
        job = new Job(path, contact);
        return true;
    }

    @Override public void start() {
        if (job.path() == null) tick();
        else if (!staff.getNavigation().moveTo(job.path(), .9)) job = null;
    }

    @Override public boolean canContinueToUse() { return job != null && staff.isAlive() && staff.hurtTime == 0; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        if (job == null) return;
        if (job.path() != null && !staff.blockPosition().equals(job.path().getTarget()) && !staff.getNavigation().isDone()) {
            if (job.approach() != null)
                noticeApproach(job.approach());
            return;
        }
        var work = job;
        job = null;
        staff.getNavigation().stop();
        work.contact().run();
    }

    private void noticeApproach(Approach approach) {
        var bounds = approach.box().bounds();
        var stock = staff.level().getEntitiesOfClass(YukkuriEntity.class, AABB.of(bounds),
                body -> owns(body) && bounds.isInside(body.blockPosition()) && !body.body().dying());
        for (var parent : stock) {
            if (approach.witnesses().contains(parent.getUUID()) || parent.isNoAi() || parent.life().isSleeping()
                    || parent.distanceToSqr(staff) > 36 || !GlassSight.sees(parent, staff)) continue;
            stock.stream().filter(child -> !child.family().adult() && ProtectivePuff.parentOf(child, parent)
                            && GlassSight.sees(parent, child))
                    .min(Comparator.comparingDouble(parent::distanceToSqr)).ifPresent(child -> {
                        approach.witnesses().add(parent.getUUID());
                        var response = parent.kinProtection().approaching(child, staff);
                        GameplayLog.event(parent, "factory", "kin tool approach started={} child={} staff={} response={}",
                                approach.startedAt(), child.getUUID(), staff.getUUID(), response);
                    });
        }
    }

    @Override public void stop() {
        staff.getNavigation().stop();
        if (staff.isAlive() && staff.level() instanceof ServerLevel level) abortCarry(level, "interrupted");
        job = null;
    }

    private static void updateReadout(ServerLevel level, PlantSite.Box box, List<YukkuriEntity> stock) {
        if (!(level.getBlockEntity(box.readout()) instanceof SignBlockEntity sign)) return;
        int pale = 0, sweet = 0, rich = 0, sick = 0, resting = 0;
        for (var animal : stock) {
            if (leaveToRest(animal)) resting++;
            int filling = animal.mind().fillingSweetness();
            if (animal.mind().nonYukkuri()) sick++;
            else if (filling >= RICH_FILLING) rich++;
            else if (filling >= SWEET_FILLING) sweet++;
            else pale++;
        }
        var text = sign.getFrontText()
                .setMessage(1, Component.translatable("factory.mcyukkuri.box.pale_rest", pale, resting))
                .setMessage(2, Component.translatable("factory.mcyukkuri.box.sweet", sweet))
                .setMessage(3, Component.translatable("factory.mcyukkuri.box.rich_sick", rich, sick));
        if (!Arrays.equals(text.getMessages(false), sign.getFrontText().getMessages(false))) {
            sign.setText(text, true);
            GameplayLog.world(level, "factory", "action=box_readout box={} pale={} sweet={} rich={} sick={} resting={}",
                    text.getMessage(0, false).getString(), pale, sweet, rich, sick, resting);
        }
    }
}
