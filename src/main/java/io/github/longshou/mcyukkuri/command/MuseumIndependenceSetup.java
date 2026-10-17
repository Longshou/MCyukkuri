package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.settlement.NaturalShelter;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Finite initial conditions for the ordinary independence goal; never builds its result. */
public final class MuseumIndependenceSetup {
    private static final String SESSION = "MuseumIndependenceSession", MATERIAL = "MuseumIndependenceMaterial";

    /**
     * Both south-facing caves must already exist in the authored exhibit: old mouth at home,
     * new mouth at home.east(8). This creates only the original leaf-bed marker and three
     * loose portions. It never excavates terrain, completes work, teleports, or grants growth.
     */
    public static boolean prepare(YukkuriEntity parent, YukkuriEntity teen, BlockPos home) {
        if (!(parent.level() instanceof ServerLevel level) || parent == teen || teen.level() != level
                || !parent.getTags().contains("pm_live") || !teen.getTags().contains("pm_live")
                || parent.isNoAi() || teen.isNoAi() || !parent.family().adult() || teen.family().adult()
                || !teen.childhood().canLiveIndependently()
                || !parent.getUUID().equals(teen.family().mother()) && !parent.getUUID().equals(teen.family().father())
                || parent.getPersistentData().contains(SESSION) || teen.getPersistentData().contains(SESSION)
                || parent.habitat().hasMemory() || teen.habitat().hasMemory()
                || parent.settlement().groupId() != null || teen.settlement().groupId() != null
                || teen.independence().building() || teen.independence().independent()) return false;
        BlockPos site = home.east(8);
        var oldSurvey = NaturalShelter.survey(level, home, Direction.SOUTH);
        var newSurvey = NaturalShelter.survey(level, site, Direction.SOUTH);
        // Air-only markers make teardown exact without overwriting existing natural beds.
        if (oldSurvey == null || oldSurvey.beds().size() < 2 || newSurvey == null
                || !level.getBlockState(home).isAir() || !level.getBlockState(site).isAir()
                || !parent.onGround() || !teen.onGround()
                || !teen.life().reachable(site, false) || !teen.life().reachable(newSurvey.entrance(), false)) return false;
        var plan = new ShelterPlacement.Plan(home, ShelterBlock.Kind.BURROW, Direction.SOUTH);
        if (!ShelterPlacement.canPlace(level, plan)
                || !ShelterPlacement.canPlace(level, new ShelterPlacement.Plan(site, ShelterBlock.Kind.BURROW, Direction.SOUTH))) return false;

        UUID token = UUID.randomUUID();
        var group = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FAMILY, home, null);
        var session = new CompoundTag();
        session.putUUID("Token", token); session.putUUID("Parent", parent.getUUID()); session.putUUID("Teen", teen.getUUID());
        session.putUUID("Group", group.id); session.putLong("Home", home.asLong()); session.putLong("Site", site.asLong());
        snapshot(parent, session); snapshot(teen, session);
        var placed = ShelterPlacement.place(level, plan);
        if (placed == null) { stop(teen); return false; }
        placed.shelter().getPersistentData().putUUID(SESSION, token);
        placed.shelter().setChanged();
        placed.shelter().initializeEncounter(2);
        SettlementData.get(level).add(group);
        parent.settlement().join(group); teen.settlement().join(group);
        if (!parent.habitat().choose(placed.shelter()) || !teen.habitat().choose(placed.shelter())) {
            stop(teen); return false;
        }
        for (var actor : new YukkuriEntity[]{parent, teen}) {
            var life = actor.life().save();
            life.putFloat("Nutrition", 100); life.putFloat("Energy", 100);
            life.putFloat("Bladder", 0); life.putFloat("Dryness", 0);
            actor.life().load(life);
        }
        if (!teen.independence().eligible()) { stop(teen); return false; }
        var project = new CompoundTag();
        FamilyData.putPosition(project, "Origin", GlobalPos.of(level.dimension(), home));
        FamilyData.putPosition(project, "Site", GlobalPos.of(level.dimension(), site));
        project.putUUID("OriginalGroup", group.id);
        project.putString("Kind", "BURROW"); project.putString("Facing", "south");
        project.putInt("Wait", 0); // Work and Materials intentionally remain empty.
        teen.independence().load(project);
        for (int i = 0; i < 3; i++) {
            var stack = new ItemStack(Items.OAK_LEAVES);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(MATERIAL, token));
            var point = Vec3.atBottomCenterOf(home.south(2).east(i + 2)).add(0, .03, 0);
            var item = new ItemEntity(level, point.x, point.y, point.z, stack);
            item.setDeltaMovement(Vec3.ZERO); item.setNoPickUpDelay(); item.addTag("pm_fixture");
            if (!level.addFreshEntity(item)) { stop(teen); return false; }
        }
        return true;
    }

    private static void snapshot(YukkuriEntity actor, CompoundTag session) {
        var own = session.copy();
        own.put("Life", actor.life().save()); own.put("Habitat", actor.habitat().save());
        own.put("Settlement", actor.settlement().save()); own.put("Independence", actor.independence().save());
        actor.getPersistentData().put(SESSION, own);
    }

    /**
     * Call before MuseumLiveActors.restore (or discard). False means a participant/chunk is
     * unloaded; preserve its session and retry when loaded. Outsider residents/groups survive.
     */
    public static boolean stop(YukkuriEntity actor) {
        if (!actor.getPersistentData().contains(SESSION)) return true;
        if (!(actor.level() instanceof ServerLevel level)) return false;
        var session = actor.getPersistentData().getCompound(SESSION);
        UUID token = session.getUUID("Token"), parentId = session.getUUID("Parent"), teenId = session.getUUID("Teen");
        BlockPos home = BlockPos.of(session.getLong("Home")), site = BlockPos.of(session.getLong("Site"));
        if (!level.hasChunkAt(home) || !level.hasChunkAt(site)
                || !(level.getEntity(parentId) instanceof YukkuriEntity parent)
                || !(level.getEntity(teenId) instanceof YukkuriEntity teen)
                || !sessionMatches(parent, token) || !sessionMatches(teen, token)) return false;
        var previousHome = ShelterBlock.shelter(level, home);
        var builtHome = ShelterBlock.shelter(level, site);
        boolean builtByTeen = teen.independence().independent() && builtHome != null
                && builtHome.getBlockPos().equals(site) && builtHome.owns(teenId)
                && site.equals(teen.habitat().shelter() == null ? null : teen.habitat().shelter().getBlockPos());
        Set<UUID> groups = new HashSet<>(); groups.add(session.getUUID("Group"));
        if (builtByTeen && teen.settlement().group() != null
                && teen.settlement().group().kind == SettlementData.Kind.FAMILY
                && teen.settlement().group().center.equals(site)) groups.add(teen.settlement().groupId());
        var data = SettlementData.get(level);
        for (var participant : new YukkuriEntity[]{parent, teen}) {
            var before = participant.getPersistentData().getCompound(SESSION).copy();
            participant.independence().abandon(); participant.habitat().release();
            data.leave(participant.settlement().group(), participant.getUUID());
            participant.life().load(before.getCompound("Life"));
            participant.habitat().load(before.getCompound("Habitat"));
            participant.settlement().load(before.getCompound("Settlement"));
            participant.independence().load(before.getCompound("Independence"));
            participant.getPersistentData().remove(SESSION);
        }
        // These markers were introduced only by this run. Never demolish an outsider's home.
        if (previousHome != null && previousHome.getBlockPos().equals(home)
                && previousHome.getPersistentData().hasUUID(SESSION)
                && previousHome.getPersistentData().getUUID(SESSION).equals(token)
                && previousHome.residentIds().isEmpty()) previousHome.rollback();
        if (builtByTeen && builtHome.residentIds().isEmpty()) builtHome.rollback();
        removeEmptyGroups(level, groups);
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(home).minmax(new AABB(site)).inflate(16))) {
            var custom = item.getItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (custom.hasUUID(MATERIAL) && custom.getUUID(MATERIAL).equals(token)) item.discard();
        }
        return true;
    }

    private static boolean sessionMatches(YukkuriEntity actor, UUID token) {
        var session = actor.getPersistentData().getCompound(SESSION);
        return session.hasUUID("Token") && session.getUUID("Token").equals(token);
    }

    private static void removeEmptyGroups(ServerLevel level, Set<UUID> candidates) {
        // Inspect persisted membership too: an unloaded outsider must not lose its community.
        var data = SettlementData.get(level);
        var saved = data.save(new CompoundTag(), level.registryAccess());
        for (Tag raw : saved.getList("Groups", Tag.TAG_COMPOUND)) {
            var group = (CompoundTag)raw;
            if (group.hasUUID("Id") && candidates.contains(group.getUUID("Id"))
                    && group.getList("Members", Tag.TAG_COMPOUND).isEmpty()) data.remove(group.getUUID("Id"));
        }
    }

    private MuseumIndependenceSetup() {}
}
