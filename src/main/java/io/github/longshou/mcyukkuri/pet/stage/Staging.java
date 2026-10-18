package io.github.longshou.mcyukkuri.pet.stage;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Shared scene helper: temporary vanilla display cards, particles and small acting shortcuts. Cards never survive a reload. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class Staging {
    public static final String CARD_TAG = "mcyukkuri_stage_card";
    private record Card(MinecraftServer server, ResourceKey<Level> dimension, long expiry) {}
    private static final Map<UUID, Card> CARDS = new HashMap<>();
    public static void reset() { CARDS.clear(); }
    private Staging() {}

    /** A billboard text card. Returns the entity id, or null if it could not be spawned. */
    public static UUID card(ServerLevel level, Vec3 pos, Component text, int ticks, float scale) {
        var tag = base("minecraft:text_display", scale);
        tag.putString("text", Component.Serializer.toJson(text, level.registryAccess()));
        tag.putInt("background", 0xA0181410); // vanilla text is white; keep the card dark for contrast
        tag.putInt("line_width", 160);
        return spawn(level, pos, tag, ticks);
    }
    public static UUID itemCard(ServerLevel level, Vec3 pos, ItemStack stack, int ticks, float scale) {
        if (stack.isEmpty()) return null;
        var tag = base("minecraft:item_display", scale);
        tag.put("item", stack.copyWithCount(1).save(level.registryAccess()));
        tag.putString("item_display", "fixed");
        return spawn(level, pos, tag, ticks);
    }
    public static void removeCard(ServerLevel level, UUID id) {
        if (id == null) return;
        CARDS.remove(id);
        var e = level.getEntity(id); if (e != null) e.discard();
    }
    public static void moveCard(ServerLevel level, UUID id, Vec3 pos) {
        if (id == null) return;
        var e = level.getEntity(id); if (e != null) e.moveTo(pos.x, pos.y, pos.z, 0, 0);
    }
    private static CompoundTag base(String id, float scale) {
        var tag = new CompoundTag(); tag.putString("id", id); tag.putString("billboard", "center"); tag.putFloat("view_range", 0.6f);
        var t = new CompoundTag();
        t.put("translation", floats(0, 0, 0)); t.put("scale", floats(scale, scale, scale));
        t.put("left_rotation", floats(0, 0, 0, 1)); t.put("right_rotation", floats(0, 0, 0, 1));
        tag.put("transformation", t); return tag;
    }
    private static ListTag floats(float... v) { var list = new ListTag(); for (float f : v) list.add(FloatTag.valueOf(f)); return list; }
    private static UUID spawn(ServerLevel level, Vec3 pos, CompoundTag tag, int ticks) {
        Entity e = EntityType.loadEntityRecursive(tag, level, entity -> { entity.moveTo(pos.x, pos.y, pos.z, 0, 0); return entity; });
        if (e == null) return null;
        e.addTag(CARD_TAG);
        CARDS.put(e.getUUID(), new Card(level.getServer(), level.dimension(), level.getGameTime() + Math.max(1, ticks)));
        if (!level.addFreshEntity(e)) { CARDS.remove(e.getUUID()); return null; }
        return e.getUUID();
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (CARDS.isEmpty()) return;
        var server = event.getServer();
        for (var it = CARDS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next(); var card = entry.getValue();
            if (card.server != server) { it.remove(); continue; }
            var level = server.getLevel(card.dimension);
            if (level == null) { it.remove(); continue; }
            if (level.getGameTime() < card.expiry) continue;
            var e = level.getEntity(entry.getKey());
            if (e != null) e.discard();
            it.remove();
        }
    }
    @SubscribeEvent public static void joining(EntityJoinLevelEvent event) {
        var e = event.getEntity();
        if (!event.getLevel().isClientSide && e.getTags().contains(CARD_TAG) && !CARDS.containsKey(e.getUUID())) event.setCanceled(true);
    }

    // particles and sound
    public static void sweat(YukkuriEntity a) {
        if (!(a.level() instanceof ServerLevel l)) return;
        l.sendParticles(ParticleTypes.FALLING_WATER, a.getX() - .2, a.getEyeY() + .1, a.getZ(), 2, .12, .05, .12, 0);
        l.sendParticles(ParticleTypes.SPLASH, a.getX() + .2, a.getEyeY(), a.getZ(), 2, .1, .05, .1, .01);
    }
    public static void sparkle(ServerLevel l, Vec3 p) { l.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y, p.z, 6, .25, .15, .25, 0); }
    public static void note(ServerLevel l, Vec3 p) { l.sendParticles(ParticleTypes.NOTE, p.x, p.y, p.z, 2, .2, .1, .2, 0); }
    public static void anger(ServerLevel l, Vec3 p) { l.sendParticles(ParticleTypes.ANGRY_VILLAGER, p.x, p.y, p.z, 3, .2, .1, .2, 0); }
    public static void sound(ServerLevel l, Vec3 p, SoundEvent s, float pitch) { l.playSound(null, BlockPos.containing(p), s, SoundSource.NEUTRAL, 1f, pitch); }

    // acting and walking
    public static void act(YukkuriEntity a, String action, String expression, String speechEvent, int ticks) {
        if (action != null) a.playAction(action);
        a.showReaction(expression, speechEvent == null ? "" : speechEvent, ticks);
    }
    public static boolean walkTo(YukkuriEntity a, BlockPos pos, double speed) {
        double x = pos.getX() + .5, z = pos.getZ() + .5, dx = a.getX() - x, dz = a.getZ() - z;
        if (dx * dx + dz * dz <= .64) { a.getNavigation().stop(); return true; }
        if (a.tickCount % 10 == 0) a.getNavigation().moveTo(x, pos.getY(), z, speed);
        return false;
    }
}
