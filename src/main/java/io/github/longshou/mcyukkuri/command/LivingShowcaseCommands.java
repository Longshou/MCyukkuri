package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;

/** Explicit operator setup for the disposable living showcase; never awards a life milestone. */
public final class LivingShowcaseCommands {
    private static final Set<String> ZONES = Set.of("land", "heights", "water", "autumn", "winter", "spring", "summer");
    private static final String OWNED = "living_showcase";

    /** Keeps explicit ownership when a visitor carries an actor outside the force-loaded gardens. */
    private static final class Registry extends SavedData {
        private final java.util.Map<String, Set<UUID>> actors = new java.util.HashMap<>();
        static Registry get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(new Factory<>(Registry::new, Registry::load), "mcyukkuri_living_showcase");
        }
        Set<UUID> zone(String zone) { return actors.computeIfAbsent(zone, ignored -> new HashSet<>()); }
        static Registry load(CompoundTag tag, HolderLookup.Provider registries) {
            var result = new Registry();
            for (String zone : ZONES) for (Tag entry : tag.getList(zone, Tag.TAG_COMPOUND)) {
                var value = (CompoundTag)entry; if (value.hasUUID("Id")) result.zone(zone).add(value.getUUID("Id"));
            }
            return result;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            actors.forEach((zone, ids) -> {
                var list = new ListTag();
                for (var id : ids) { var value = new CompoundTag(); value.putUUID("Id", id); list.add(value); }
                tag.put(zone, list);
            });
            return tag;
        }
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("showcase").requires(source -> source.hasPermission(2))
                .then(Commands.literal("pair")
                        .then(Commands.argument("mother", EntityArgument.entity())
                                .then(Commands.argument("father", EntityArgument.entity())
                                        .then(Commands.argument("home", BlockPosArgument.blockPos())
                                                .then(Commands.argument("birth", StringArgumentType.word())
                                                        .executes(c -> pair(c.getSource(), EntityArgument.getEntity(c, "mother"), EntityArgument.getEntity(c, "father"),
                                                                BlockPosArgument.getLoadedBlockPos(c, "home"), StringArgumentType.getString(c, "birth"))))))))
                .then(Commands.literal("track").then(Commands.argument("zone", StringArgumentType.word())
                        .executes(c -> track(c.getSource(), StringArgumentType.getString(c, "zone"), false))))
                .then(Commands.literal("reset").then(Commands.argument("zone", StringArgumentType.word())
                        .executes(c -> track(c.getSource(), StringArgumentType.getString(c, "zone"), true))))
                .then(Commands.literal("launch").then(Commands.argument("owner", EntityArgument.entity())
                        .then(Commands.argument("water", BlockPosArgument.blockPos()).executes(c -> {
                            var entity = EntityArgument.getEntity(c, "owner");
                            if (!(entity instanceof YukkuriEntity y) || !y.getTags().contains(OWNED)) return 0;
                            // Explicit demonstration provisioning: transfer finite old household stores and one
                            // actual twig source. No food, paddle, child or voyage completion is invented.
                            var home = y.habitat().shelter(); net.minecraft.core.BlockPos twig = null;
                            for (var at : net.minecraft.core.BlockPos.betweenClosed(y.blockPosition().offset(-2, 0, -2), y.blockPosition().offset(2, 1, 2))) {
                                if (!y.aquaticLife().hasStick() && y.level().getBlockState(at).is(net.minecraft.world.level.block.Blocks.DEAD_BUSH)) {
                                    twig = at.immutable(); break;
                                }
                            }
                            var boat = HatBoatEntity.launchFromBank(y, BlockPosArgument.getLoadedBlockPos(c, "water"));
                            if (boat == null) { c.getSource().sendFailure(Component.literal("未能从真实岸边下水；请检查水面、帽子和位置。")); return 0; }
                            // Failed launches leave both the twig and the household stock untouched.
                            if (twig != null && !boat.cargo().paddle() && y.level().setBlock(twig, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3))
                                boat.cargo().givePaddle(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK), false);
                            boat.addTag(OWNED); for (String tag : y.getTags()) if (tag.startsWith("living_")) boat.addTag(tag);
                            if (home != null) for (int i = 0; i < 4; i++) {
                                var serving = home.takeFood(y);
                                if (serving.isEmpty()) break;
                                boat.cargo().store(serving, 1, false);
                                if (!serving.isEmpty()) home.returnFood(serving);
                            }
                            return 1;
                        }))));
    }

    private static int pair(CommandSourceStack source, net.minecraft.world.entity.Entity first,
                            net.minecraft.world.entity.Entity second, net.minecraft.core.BlockPos pos, String birth) {
        if (!(first instanceof YukkuriEntity mother) || !(second instanceof YukkuriEntity father)
                || mother == father || !mother.getTags().contains(OWNED) || !father.getTags().contains(OWNED)
                || mother.level() != source.getLevel() || father.level() != source.getLevel()
                || !(birth.equals("live") || birth.equals("stem"))) return 0;
        var level = source.getLevel();
        // Setup only fresh demonstration adults, never overwrite a visitor's established family.
        if (mother.family().partner() != null || father.family().partner() != null
                || mother.habitat().shelter() != null || father.habitat().shelter() != null
                || mother.settlement().groupId() != null || father.settlement().groupId() != null) return 0;
        var motherBefore = new CompoundTag(); var fatherBefore = new CompoundTag();
        mother.saveWithoutId(motherBefore); father.saveWithoutId(fatherBefore);
        if (!mother.family().introduce(father)) return 0;
        var shelter = ShelterBlock.shelter(level, pos);
        if (shelter == null || !mother.habitat().choose(shelter) || !father.habitat().choose(shelter)) {
            if (shelter != null) { shelter.release(mother.getUUID()); shelter.release(father.getUUID()); }
            mother.load(motherBefore); father.load(fatherBefore);
            source.sendFailure(Component.literal("家庭未能认领实际可达的睡位；没有创建胎次。")); return 0;
        }
        var group = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FAMILY, pos, null);
        SettlementData.get(level).add(group); mother.settlement().join(group); father.settlement().join(group);
        // A prepared couple begins with mutual recognition; subsequent care and growth are ordinary simulation.
        var relations = YukkuriWorldData.get(level);
        int oldMotherAffinity = relations.affinity(mother.getUUID(), father.getUUID());
        int oldFatherAffinity = relations.affinity(father.getUUID(), mother.getUUID());
        relations.relate(mother.getUUID(), father.getUUID(), 40 - oldMotherAffinity);
        relations.relate(father.getUUID(), mother.getUUID(), 40 - oldFatherAffinity);
        var brood = FamilyData.get(level).conceive(mother, father,
                birth.equals("live") ? FamilyData.BirthKind.LIVE : FamilyData.BirthKind.STEM, GlobalPos.of(level.dimension(), pos));
        if (brood == null) {
            shelter.release(mother.getUUID()); shelter.release(father.getUUID()); SettlementData.get(level).remove(group.id);
            relations.relate(mother.getUUID(), father.getUUID(), oldMotherAffinity - relations.affinity(mother.getUUID(), father.getUUID()));
            relations.relate(father.getUUID(), mother.getUUID(), oldFatherAffinity - relations.affinity(father.getUUID(), mother.getUUID()));
            mother.load(motherBefore); father.load(fatherBefore);
            source.sendFailure(Component.literal("受孕条件未满足；住所、配偶、聚落和亲密度已回退，没有生成子代或消耗库存。请重置本区后再开始。")); return 0;
        }
        source.sendSuccess(() -> Component.literal("真实胎次已开始；等待出生、取粮喂糜、首便、运动学习与成长。"), false);
        return 1;
    }

    /** Follow ancestry and hat ownership, so a reset does not depend on where a child wandered. */
    private static int track(CommandSourceStack source, String zone, boolean reset) {
        if (!ZONES.contains(zone)) return 0;
        var level = source.getLevel(); var family = FamilyData.get(level); String tag = "living_" + zone;
        var registry = Registry.get(level);
        Set<UUID> owned = new HashSet<>(registry.zone(zone));
        var loaded = new java.util.HashMap<UUID, net.minecraft.world.entity.Entity>();
        for (var dimension : source.getServer().getAllLevels()) for (var entity : dimension.getAllEntities()) {
            loaded.put(entity.getUUID(), entity);
            if (entity.getTags().contains(OWNED) && entity.getTags().contains(tag)) owned.add(entity.getUUID());
        }
        boolean changed;
        do {
            changed = false;
            for (var parent : Set.copyOf(owned)) for (var child : family.children(parent)) if (owned.add(child)) changed = true;
        } while (changed);
        for (var entity : loaded.values()) if (entity instanceof HatBoatEntity boat && owned.contains(boat.ownerYukkuri())) owned.add(boat.getUUID());
        if (!registry.zone(zone).equals(owned)) { registry.zone(zone).addAll(owned); registry.setDirty(); }
        if (reset) for (var id : owned) {
            var person = family.person(id);
            if (person != null && person.status == FamilyData.Status.ALIVE && !loaded.containsKey(id)) {
                source.sendFailure(Component.literal("本区有已卸载或被收容的展示成员；请先带回并放出，再重置。没有清理任何家庭。"));
                return 0;
            }
        }
        Set<UUID> groups = new HashSet<>(); int count = 0;
        var settlementSnapshots = new java.util.HashMap<ServerLevel, CompoundTag>();
        if (reset) for (var dimension : source.getServer().getAllLevels())
            settlementSnapshots.put(dimension, SettlementData.get(dimension).save(new CompoundTag(), dimension.registryAccess()));
        for (var entity : loaded.values()) {
            if (!owned.contains(entity.getUUID())) continue;
            entity.addTag(OWNED); entity.addTag(tag); count++;
            if (reset && entity instanceof YukkuriEntity y) {
                if (y.settlement().groupId() != null) groups.add(y.settlement().groupId());
                var home = y.habitat().shelter(); if (home != null) home.release(y.getUUID());
                family.died(y); // Cancels reserved unborn children through the normal ledger as well.
            }
            if (reset) entity.discard();
        }
        if (reset) for (var snapshot : settlementSnapshots.entrySet()) for (Tag entry : snapshot.getValue().getList("Groups", Tag.TAG_COMPOUND)) {
            var group = (CompoundTag)entry; var id = group.getUUID("Id");
            if (!groups.contains(id)) continue;
            // Include saved, unloaded members when deciding whether this group belongs wholly to the demo.
            boolean outside = group.hasUUID("Leader") && !owned.contains(group.getUUID("Leader"));
            for (Tag memberEntry : group.getList("Members", Tag.TAG_COMPOUND))
                if (!owned.contains(((CompoundTag)memberEntry).getUUID("Id"))) outside = true;
            var data = SettlementData.get(snapshot.getKey());
            if (!outside) data.remove(id);
            else for (var member : owned) data.leave(data.group(id), member);
        }
        if (reset) { registry.zone(zone).clear(); registry.setDirty(); }
        return reset ? 1 : count;
    }

    private LivingShowcaseCommands() {}
}
