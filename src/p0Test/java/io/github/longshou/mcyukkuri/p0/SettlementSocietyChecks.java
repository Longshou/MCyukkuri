package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Remote-only focused regression scenarios, called within the existing settlement fixture. */
final class SettlementSocietyChecks {
    static void run(ServerLevel level, BlockPos at, BiConsumer<String, Boolean> check) {
        var target = ModEntities.REIMU.get().create(level);
        var observer = ModEntities.MARISA.get().create(level);
        var data = SettlementData.get(level);
        var group = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FOREST, at, target.getUUID());
        data.add(group);
        target.moveTo(Vec3.atBottomCenterOf(at)); observer.moveTo(Vec3.atBottomCenterOf(at.east(20)));
        target.setOnGround(true); observer.setOnGround(true);
        level.addFreshEntity(target); level.addFreshEntity(observer);
        target.settlement().join(group); observer.settlement().join(group);
        personality(target, 30, 20, 80, 80); personality(observer, 90, 80, 30, 20);
        var record = target.settlement().society().record();
        try {
            UUID token = target.life().takeAccessory(null);
            target.settlement().society().tick();
            check.accept("lost accessory without a nearby witness keeps office", record.rejections == 0 && record.role == SettlementData.Role.LEADER);
            observer.moveTo(Vec3.atBottomCenterOf(at.east(2)));
            medal(observer, true);
            target.settlement().society().tick();
            check.accept("gold observer recognizes bare member without penalty", record.rejections == 0);
            medal(observer, false); medal(target, true);
            personality(observer, 0, 0, 80, 80);
            target.settlement().society().tick();
            check.accept("gentle unrecognizing witness does not impose a role", record.rejections == 0);
            personality(observer, 90, 80, 30, 20);
            level.setBlockAndUpdate(at.east(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(at.east().above(), Blocks.STONE.defaultBlockState());
            target.settlement().society().tick();
            check.accept("occluded witness cannot reject a member", record.rejections == 0);
            level.setBlockAndUpdate(at.east(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(at.east().above(), Blocks.AIR.defaultBlockState());
            target.settlement().society().tick();
            check.accept("target's own gold medal cannot identify it for another observer", record.rejections == 1);
            target.settlement().society().tick();
            check.accept("one encounter cannot accumulate five rejections in a tick", record.rejections == 1);
            // Advance only the observation gate; actual tick pacing is covered in live confirmation.
            for (int count = 1; count < 5; count++) { record.nextObservation = 0; target.settlement().society().tick(); }
            check.accept("repeated witnessed rejection gives this calm dependent adult a real cleaning assignment",
                    record.role == SettlementData.Role.UNUN_SLAVE && observer.getUUID().equals(record.assignedBy) && data.activeLeader(group) == null);
            var copy = SettlementData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            check.accept("standing and suspended office survive NBT", copy.role(copy.group(group.id), target.getUUID()) == SettlementData.Role.UNUN_SLAVE
                    && copy.member(copy.group(group.id), target.getUUID()).previousRole == SettlementData.Role.LEADER);
            int affinity = YukkuriWorldData.get(level).affinity(observer.getUUID(), target.getUUID());
            check.accept("wrong token does not restore office", !target.life().returnAccessory(target.getUUID(), UUID.randomUUID(), null)
                    && record.role == SettlementData.Role.UNUN_SLAVE);
            check.accept("original accessory restores office without repairing affection", target.life().returnAccessory(target.getUUID(), token, null)
                    && record.role == SettlementData.Role.LEADER && record.assignedBy == null
                    && YukkuriWorldData.get(level).affinity(observer.getUUID(), target.getUUID()) == affinity);

            var legacy = data.save(new CompoundTag(), level.registryAccess());
            for (var raw : legacy.getList("Groups", net.minecraft.nbt.Tag.TAG_COMPOUND)) ((CompoundTag)raw).remove("Members");
            var old = SettlementData.load(legacy, level.registryAccess());
            check.accept("old saves conservatively retain leader and member defaults", old.role(old.group(group.id), target.getUUID()) == SettlementData.Role.LEADER
                    && old.member(old.group(group.id), observer.getUUID()).rejections == 0);

            BlockPos toilet = at.south(3);
            level.setBlockAndUpdate(toilet, ModBlocks.WILD_LATRINE.get().defaultBlockState());
            var care = (CareBlockEntity)level.getBlockEntity(toilet);
            ItemStack portion = io.github.longshou.mcyukkuri.body.DetachedItems.waste(target);
            care.relieve(portion); care.relieve(new ItemStack(ModItems.WASTE.get()));
            ItemStack transferred = care.takeWaste();
            check.accept("cleaning transfers one original toilet portion and preserves remaining waste",
                    ItemStack.matches(portion, transferred) && care.waste() == 1 && care.cleanItems().size() == 1 && care.takeWaste().isEmpty());
            level.setBlockAndUpdate(toilet, Blocks.AIR.defaultBlockState());
        } finally {
            target.discard(); observer.discard(); data.remove(group.id);
            level.setBlockAndUpdate(at.east(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(at.east().above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(at.south(3), Blocks.AIR.defaultBlockState());
        }
    }
    private static void personality(YukkuriEntity entity, int selfish, int aggressive, int attachment, int control) {
        var tag = entity.life().personality().save(); tag.putInt("Version", 2);
        tag.putIntArray("DispositionInnate", new int[]{selfish, aggressive, attachment, control});
        tag.putIntArray("DispositionExperience", new int[4]); entity.life().personality().load(tag);
    }
    private static void medal(YukkuriEntity entity, boolean gold) {
        if (gold) entity.pet().issue(io.github.longshou.mcyukkuri.pet.BadgeGrade.GOLD, 1, null); else entity.pet().revoke();
    }
    private SettlementSocietyChecks() {}
}
