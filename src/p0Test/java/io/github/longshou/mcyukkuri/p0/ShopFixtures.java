package io.github.longshou.mcyukkuri.p0;

import com.mojang.authlib.GameProfile;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Shared fixtures for the pet shop and human GameTests: FakePlayers that the server can find by UUID, standable spots and ready-made animals. */
final class ShopFixtures {
    private ShopFixtures() {}

    /** Main-code flows look their owner up with PlayerList.getPlayer(UUID), which never sees a FakePlayer; this registers it in the UUID map (reflection, found by generic type). */
    @SuppressWarnings("unchecked")
    static boolean online(ServerLevel level, ServerPlayer player) {
        var list = level.getServer().getPlayerList();
        try {
            for (var field : PlayerList.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                var generic = field.getGenericType().getTypeName();
                if (!generic.contains("java.util.UUID") || !generic.contains("ServerPlayer")) continue;
                field.setAccessible(true);
                ((Map<UUID, ServerPlayer>) field.get(list)).put(player.getUUID(), player);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MCYukkuri.LOGGER.warn("[pet_shop] cannot register FakePlayer in PlayerList", e);
        }
        return list.getPlayer(player.getUUID()) == player;
    }

    @SuppressWarnings("unchecked")
    static void offline(ServerLevel level, ServerPlayer player) {
        var list = level.getServer().getPlayerList();
        try {
            for (var field : PlayerList.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                var generic = field.getGenericType().getTypeName();
                if (!generic.contains("java.util.UUID") || !generic.contains("ServerPlayer")) continue;
                field.setAccessible(true);
                ((Map<UUID, ServerPlayer>) field.get(list)).remove(player.getUUID(), player);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MCYukkuri.LOGGER.warn("[pet_shop] cannot unregister FakePlayer", e);
        }
    }

    /** A fresh FakePlayer that is part of the level and findable through the PlayerList. */
    static FakePlayer player(ServerLevel level, String name) {
        var p = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), name));
        level.addNewPlayer(p);
        online(level, p);
        p.getInventory().clearContent();
        return p;
    }

    static void release(ServerLevel level, FakePlayer p) {
        offline(level, p);
        p.discard();
    }

    static int emeralds(Player p) {
        return p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }

    static void giveEmeralds(Player p, int amount) {
        while (amount > 0) {
            int n = Math.min(64, amount);
            p.getInventory().add(new ItemStack(Items.EMERALD, n));
            amount -= n;
        }
    }

    static void clearEmeralds(Player p) {
        for (var s : p.getInventory().items) if (s.is(Items.EMERALD)) s.setCount(0);
    }

    /** Needs satisfied so that PetLife.readyFor, exams and breeding pass. */
    static void needs(YukkuriEntity y) {
        var life = y.life().save();
        life.putFloat("Nutrition", 100);
        life.putFloat("Energy", 100);
        life.putFloat("Hygiene", 100);
        life.putFloat("Bladder", 0);
        life.putInt("RestWait", 0);
        y.life().load(life);
        y.setHealth(y.getMaxHealth());
    }

    /** Same adult state the factory tests use for breeders. */
    static void adult(YukkuriEntity y) {
        var age = y.family().save();
        age.putBoolean("Initialized", true);
        age.putLong("Age", 24001);
        age.putLong("Lifespan", 72000);
        age.putLong("Nourishment", 24000);
        age.putString("Stage", "ADULT");
        age.putInt("Recovery", 0);
        y.family().load(age);
    }

    static YukkuriEntity spawn(ServerLevel level, Supplier<EntityType<YukkuriEntity>> type, double x, double y, double z) {
        var e = type.get().create(level);
        e.moveTo(x, y, z, 0, 0);
        e.setOnGround(true);
        e.setPersistenceRequired();
        level.addFreshEntity(e);
        return e;
    }

    /** Free floor cells (feet positions) on the centre's level within radius, nearest first, at least minDist away. */
    static List<BlockPos> spots(ServerLevel level, Vec3 centre, double minDist, int radius) {
        var base = BlockPos.containing(centre);
        var out = new ArrayList<BlockPos>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            var feet = base.offset(dx, 0, dz);
            if (!standable(level, feet)) continue;
            double dist = Math.sqrt(feet.distToCenterSqr(centre.x, feet.getY() + .5, centre.z));
            if (dist < minDist) continue;
            out.add(feet);
        }
        out.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(centre.x, p.getY() + .5, centre.z)));
        return out;
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        var below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;
        if (!level.getFluidState(feet).isEmpty()) return false;
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(feet).inflate(.35), e -> true).isEmpty();
    }

    /** Moves the player to the nearest cell from which the NPC's menu is valid (6 blocks, line of sight). */
    static boolean placeFor(ServerLevel level, Player p, HumanNpc npc, double minDist) {
        for (var s : spots(level, npc.position(), minDist, 5)) {
            p.moveTo(s.getX() + .5, s.getY(), s.getZ() + .5, 0, 0);
            if (HumanMenu.valid(p, npc)) return true;
        }
        return false;
    }

    /** Moves the animal to the nearest free cell beside the player that it can see (within 6 blocks). */
    static boolean placeNear(ServerLevel level, YukkuriEntity y, Player p, double minDist) {
        for (var s : spots(level, p.position(), minDist, 4)) {
            y.moveTo(s.getX() + .5, s.getY(), s.getZ() + .5, 0, 0);
            if (p.distanceToSqr(y) <= 36 && p.hasLineOfSight(y)) return true;
        }
        return false;
    }
}
