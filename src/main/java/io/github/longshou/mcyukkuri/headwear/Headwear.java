package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The accessory as a second life (docs/HEADWEAR.md; komicolle 40720 No.11518829): it grows with the body, mends itself while
 * its owner can take it easy, can be torn and finally shatter, and its pieces are licked together and kept until they knit.
 * Without it the owner is not recognised as a yukkuri at all (canRecognize), which is the root of every social consequence.
 *
 * <p>State lives with the owner, never in an item: a taken hat item or a hat boat only carries the owner's token, so damage
 * and size survive every path the hat already takes (player, thief, boat). The numbers are project design.
 */
public final class Headwear extends Goal {
    public enum State { WORN, OFF, SHATTERED, REASSEMBLING, LOST }
    /** 0 intact, 1 frayed, 2 torn. One more and it shatters. */
    public static final int TORN = 2;
    /** Mend progress needed per level; a calm owner adds 2 per tick, an uneasy one 1, a sweetened or broken mind none. */
    public static final int MEND_FRAYED = 6000, MEND_TORN = 12000, REASSEMBLE = 12000;
    /** Without any found piece for three days the owner gives the hat up for lost. */
    public static final int GIVE_UP_TICKS = 72000;
    /** Smallest size of a worn hat relative to its owner; growth per second per feeling unit (lecture reply: it feeds on self-feeling). */
    public static final float MIN_FIT = .6f, GROWTH_PER_SECOND = .004f;

    private final YukkuriEntity entity;
    private int damage, mend, reassembly, shardsTotal, shardsHeld;
    private float fit = -1;
    private boolean shattered, lost;
    private UUID shardToken;
    private long lastProgress;
    private String cause = "";
    // shard gathering (goal)
    private ItemEntity target;
    private int elapsed, scanWait, lickTicks;

    public Headwear(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        scanWait = 20 + entity.getRandom().nextInt(40);
    }

    // ---- reads ----------------------------------------------------------------------------------------------------
    public State state() {
        if (entity.life().hasAccessory()) return State.WORN;
        if (lost) return State.LOST;
        if (shattered) return shardsHeld >= shardsTotal ? State.REASSEMBLING : State.SHATTERED;
        return State.OFF;
    }
    public String stateId() { return state().name().toLowerCase(Locale.ROOT); }
    public int damage() { return damage; }
    public boolean intact() { return entity.life().hasAccessory() && damage == 0; }
    public int shardsHeld() { return shardsHeld; }
    public int shardsTotal() { return shardsTotal; }
    public UUID shardToken() { return shardToken; }
    /** Hat size relative to the owner's body (1 = fits). */
    public float relativeFit() {
        float body = entity.family().scale();
        return body <= 0 || fit <= 0 ? 1 : Math.clamp(fit / body, MIN_FIT, 1f);
    }
    public String describe() {
        return stateId() + " damage=" + damage + " mend=" + mend + " fit=" + Math.round(relativeFit() * 100) / 100f
                + " shards=" + shardsHeld + "/" + shardsTotal + " reassembly=" + reassembly + (cause.isEmpty() ? "" : " cause=" + cause)
                + (target != null ? " gathering" : "");
    }
    /** Lasting pressure on the mind, per minute (MindLife.sample). An owner afloat in its own hat boat still has its hat. */
    public float uneasePerMinute() {
        return switch (state()) {
            case WORN -> damage >= TORN ? 1f : damage == 1 ? .5f : 0f;
            case OFF -> entity.subspecies().boatId() != null ? 0f : 3f;
            case SHATTERED -> 4f;
            case REASSEMBLING -> 1.5f;
            case LOST -> 5f;
        };
    }
    public String uneaseCause() {
        return switch (state()) {
            case WORN -> "accessory_torn";
            case OFF -> "no_accessory";
            case SHATTERED -> "accessory_shattered";
            case REASSEMBLING -> "accessory_mending";
            case LOST -> "accessory_gone";
        };
    }
    /** Client view: bits 0-1 drawn level (0-2 worn damage, 3 pieces put back together), bits 4-10 relative fit in percent. */
    public int visual() {
        var s = state();
        int level = s == State.WORN ? damage : s == State.REASSEMBLING ? 3 : 0;
        return level | Math.round(relativeFit() * 100) << 4;
    }
    public static int visualLevel(int packed) { return packed & 3; }
    public static float visualFit(int packed) { int p = packed >> 4 & 127; return p == 0 ? 1 : Math.clamp(p / 100f, MIN_FIT, 1f); }

    /** Lecture: the hat feeds on the feeling of being able to take it easy. */
    private int feeling() {
        if (entity.mind().nonYukkuri()) return 0;
        float u = entity.mind().unease();
        return u < MindLife.UNEASY ? 2 : u < MindLife.SWEETENED ? 1 : 0;
    }

    // ---- recognition (package 7: misrecognition without the accessory) -------------------------------------------
    /** The observer cannot tell that this hatless individual is a yukkuri, kin included (komicolle 11518847). */
    public static boolean isUnrecognizedHatless(YukkuriEntity observer, YukkuriEntity target) {
        return observer != target && target.isAlive() && !target.life().hasAccessory() && !observer.life().canRecognize(target);
    }
    /** Unrecognised, and nobody's property: the test for driving off, bullying or treating as food (C11: pets and owned are safe). */
    public static boolean fairGame(YukkuriEntity observer, YukkuriEntity target) {
        return isUnrecognizedHatless(observer, target) && YukkuriStatus.identity(target).free() && !target.getVariant().isPredator();
    }

    // ---- damage ----------------------------------------------------------------------------------------------------
    /** A blow the owner survived (YukkuriEntity.hurt). {@code part} is the body region hit, or null. */
    public void struck(DamageSource source, float amount, io.github.longshou.mcyukkuri.body.BodyPart part) {
        if (entity.level().isClientSide || !entity.life().hasAccessory() || amount <= 0) return;
        var random = entity.getRandom();
        if (source.is(DamageTypeTags.IS_FIRE)) { if (random.nextInt(100) < 35) harm(1, "fire", true); return; }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) { harm(amount >= 6 ? 3 : 2, "explosion", false); return; }
        if (source.is(DamageTypes.FALLING_ANVIL) || source.is(DamageTypes.FALLING_BLOCK) || source.is(DamageTypes.FALLING_STALACTITE)) { harm(2, "crushed", false); return; }
        if (source.getEntity() == null || io.github.longshou.mcyukkuri.body.InjuryModel.attrition(source) || source.is(DamageTypeTags.IS_FALL)) return;
        boolean predator = source.getEntity() instanceof YukkuriEntity y && y.getVariant().isPredator();
        int chance = part == io.github.longshou.mcyukkuri.body.BodyPart.TOP ? 70 : predator ? 45 : amount >= 3 ? 15 : 0;
        if (random.nextInt(100) < chance) harm(amount >= 5 ? 2 : 1, predator ? "bitten" : "struck", false);
    }
    /** Tear the worn hat by {@code levels}; past torn it shatters ({@code burn}: burns away, nothing left to gather). */
    public boolean harm(int levels, String why, boolean burn) {
        if (entity.level().isClientSide || levels <= 0 || !entity.life().hasAccessory()) return false;
        damage += levels; mend = 0; cause = why;
        if (damage > TORN) { shatter(why, burn); return true; }
        entity.mind().add(4f * levels, damage >= TORN ? "accessory_torn" : "accessory_frayed");
        if (entity.isAlive()) entity.showReaction("teary", "accessory_torn", 80);
        MCYukkuri.LOGGER.info("[headwear] {} #{} damaged to {} by {}", entity.getVariant().assetId(), entity.getId(), damage, why);
        return true;
    }
    /** The hat breaks apart: two or three pieces fall around the owner, who is now a stranger to everyone. */
    public void shatter(String why, boolean burned) {
        if (entity.level().isClientSide || !entity.life().hasAccessory()) return;
        if (entity.isVehicle() && io.github.longshou.mcyukkuri.family.HatCarry.hasHat(entity)) io.github.longshou.mcyukkuri.family.HatCarry.putDown(entity);
        entity.aquaticLife().firstVoyage().dropStoredCargo();
        var item = new ItemStack(ModItems.accessoryFor(entity));
        entity.life().removeAccessory();
        damage = 0; mend = 0; reassembly = 0; shardsHeld = 0; cause = why;
        lastProgress = entity.level().getGameTime();
        shattered = !burned; lost = burned;
        shardToken = burned ? null : UUID.randomUUID();
        shardsTotal = burned ? 0 : 2 + entity.getRandom().nextInt(2);
        for (int piece = 0; piece < shardsTotal; piece++) dropShard(piece, item);
        entity.mind().add(burned ? 20 : 12, burned ? "accessory_burned" : "accessory_shattered");
        if (entity.isAlive()) entity.showReaction("cry", "lost_accessory", 140);
        MCYukkuri.LOGGER.info("[headwear] {} #{} accessory {} by {} ({} pieces)", entity.getVariant().assetId(), entity.getId(),
                burned ? "burned away" : "shattered", why, shardsTotal);
    }
    private void dropShard(int piece, ItemStack hat) {
        var stack = new ItemStack(ModItems.ACCESSORY_SHARD.get());
        final var token = shardToken;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("Owner", entity.getUUID()); tag.putUUID("Token", token); tag.putInt("Piece", piece);
            tag.putString("Variant", entity.getVariant().assetId()); tag.putString("SourceModel", entity.subspecies().assetId());
            tag.putString("Accessory", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hat.getItem()).toString());
            tag.putFloat("SourceScale", entity.growthScale() * entity.getScale() * (entity.getVariant().isHouse() ? .32f : 1));
        });
        var drop = new ItemEntity(entity.level(), entity.getX(), entity.getEyeY(), entity.getZ(), stack);
        double angle = entity.getRandom().nextDouble() * Math.PI * 2;
        drop.setDeltaMovement(Math.cos(angle) * .12, .2, Math.sin(angle) * .12);
        drop.setPickUpDelay(40);
        entity.level().addFreshEntity(drop);
    }

    // ---- shards ----------------------------------------------------------------------------------------------------
    public boolean ownsShard(ItemStack stack) {
        if (!stack.is(ModItems.ACCESSORY_SHARD.get()) || state() != State.SHATTERED || shardToken == null) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID("Owner") && tag.hasUUID("Token") && entity.getUUID().equals(tag.getUUID("Owner")) && shardToken.equals(tag.getUUID("Token"));
    }
    /** One piece back in the owner's keeping, licked up by itself or handed over by a player. */
    public boolean takeShard(ItemStack stack, Player giver) {
        if (!ownsShard(stack)) return false;
        shardsHeld++; lastProgress = entity.level().getGameTime();
        boolean complete = shardsHeld >= shardsTotal;
        if (complete && giver != null) entity.life().personality().accessoryReturned(giver.getUUID());
        entity.mind().add(complete ? -8 : -3, "shard_gathered");
        entity.showReaction(complete ? "happy" : "teary", "shard_gathered", 80);
        MCYukkuri.LOGGER.info("[headwear] {} #{} gathered piece {}/{}{}", entity.getVariant().assetId(), entity.getId(), shardsHeld, shardsTotal,
                giver != null ? " from a player" : "");
        return true;
    }
    /** Lose the remaining pieces for good (destroyed shard item, test door). */
    public void loseForGood(String why) {
        if (entity.life().hasAccessory() || lost) return;
        lost = true; shattered = false; shardToken = null; cause = why;
        if (entity.isAlive()) entity.showReaction("cry", "lost_accessory", 140);
        MCYukkuri.LOGGER.info("[headwear] {} #{} accessory lost for good ({})", entity.getVariant().assetId(), entity.getId(), why);
    }
    /** A shard item was destroyed (lava, fire, cactus, void): the owner can never complete its hat. */
    public static void shardDestroyed(ServerLevel level, ItemStack stack) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.hasUUID("Owner") || !(level.getEntity(tag.getUUID("Owner")) instanceof YukkuriEntity owner)) return;
        if (owner.headwear().ownsShard(stack)) owner.headwear().loseForGood("shard_destroyed");
    }
    private void restore() {
        shattered = false; lost = false; shardsTotal = shardsHeld = 0; reassembly = 0; shardToken = null; mend = 0;
        damage = TORN; // the seams still show; it mends from here as a worn hat
        entity.life().wearAccessory();
        entity.showReaction("happy", "returned_accessory", 120);
        MCYukkuri.LOGGER.info("[headwear] {} #{} pieces knit back into its accessory", entity.getVariant().assetId(), entity.getId());
    }

    // ---- writes for status and tests -------------------------------------------------------------------------------
    public String force(String what) {
        switch (what) {
            case "shatter" -> { if (!entity.life().hasAccessory()) return "not worn"; shatter("status", false); }
            case "burn" -> { if (!entity.life().hasAccessory()) return "not worn"; shatter("status", true); }
            case "lose" -> { if (entity.life().hasAccessory()) return "worn"; if (!shattered && !lost) { shattered = true; } loseForGood("status"); }
            case "gather" -> { if (state() != State.SHATTERED) return "not shattered"; shardsHeld = shardsTotal; lastProgress = entity.level().getGameTime(); }
            case "knit" -> { if (state() != State.REASSEMBLING) return "not reassembling"; restore(); }
            case "restore" -> { if (!entity.life().hasAccessory()) entity.life().wearAccessory(); clear(); damage = 0; }
            default -> { return "shatter, burn, lose, gather, knit or restore"; }
        }
        return null;
    }
    public String setDamage(int value) {
        if (value < 0 || value > 3) return "0-3 (3 shatters)";
        if (!entity.life().hasAccessory()) return "not worn";
        if (value == 3) { shatter("status", false); return null; }
        damage = value; mend = 0; return null;
    }
    public String setFit(float ratio) {
        if (!(ratio >= MIN_FIT && ratio <= 1)) return "ratio " + MIN_FIT + "-1";
        fit = ratio * entity.family().scale(); return null;
    }
    private void clear() { shattered = lost = false; shardsTotal = shardsHeld = 0; reassembly = 0; shardToken = null; mend = 0; cause = ""; }

    // ---- every tick --------------------------------------------------------------------------------------------------
    public void clock() {
        if (entity.level().isClientSide) return;
        if (scanWait > 0) scanWait--;
        boolean worn = entity.life().hasAccessory();
        if (worn && (shattered || lost)) clear(); // put back on by some other path
        float body = entity.family().scale();
        if (fit <= 0 || fit > body) fit = body; // the hat never outgrows its owner
        int feeling = feeling();
        if (worn) {
            if (fit < body && feeling > 0 && entity.tickCount % 20 == 0) fit = Math.min(body, fit + GROWTH_PER_SECOND * feeling * Math.max(.3f, body));
            if (damage > 0 && feeling > 0 && (mend += feeling) >= (damage >= TORN ? MEND_TORN : MEND_FRAYED)) {
                damage--; mend = 0;
                if (entity.isAlive()) entity.showReaction("happy", "accessory_mended", 60);
                MCYukkuri.LOGGER.info("[headwear] {} #{} accessory mended to {}", entity.getVariant().assetId(), entity.getId(), damage);
            }
        } else if (state() == State.REASSEMBLING) {
            // Kept close and knitting even while miserable; only a broken mind stops it (recovery is not meant to be cruel).
            if (!entity.mind().nonYukkuri() && (reassembly += Math.max(1, feeling)) >= REASSEMBLE) restore();
        } else if (state() == State.SHATTERED && entity.level().getGameTime() - lastProgress > GIVE_UP_TICKS) loseForGood("gave_up");
        entity.syncHeadwear(visual());
    }

    // ---- goal: lick the pieces together ----------------------------------------------------------------------------
    public boolean active() { return target != null; }
    public String activity() { return target == null ? "idle" : lickTicks > 0 ? "shard_lick" : "shard_seek"; }
    private boolean able() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.isPassenger()
                && !entity.life().isSleeping() && entity.life().mealTicks() == 0 && !entity.operations().active() && !entity.mind().nonYukkuri()
                && entity.childhood().canLearn();
    }
    @Override public boolean canUse() {
        if (target != null) return true;
        if (scanWait > 0 || state() != State.SHATTERED || !able()) return false;
        scanWait = 60;
        target = entity.level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(12, 4, 12),
                        item -> item.isAlive() && ownsShard(item.getItem()) && entity.life().reachable(item.blockPosition(), false))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        elapsed = lickTicks = 0;
        return target != null;
    }
    @Override public boolean canContinueToUse() {
        return target != null && target.isAlive() && ownsShard(target.getItem()) && elapsed < 600 && able();
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.social().stop(); }
    @Override public void tick() {
        elapsed++;
        if (target == null) return;
        entity.getLookControl().setLookAt(target, 30, 30);
        if (entity.distanceToSqr(target) < 1.4) {
            entity.getNavigation().stop();
            if (++lickTicks >= 32) {
                var stack = target.getItem();
                if (takeShard(stack, null)) { stack.shrink(1); if (stack.isEmpty()) target.discard(); else target.setItem(stack); }
                target = null;
            }
        } else {
            lickTicks = 0;
            if (elapsed % 10 == 1 && !entity.life().navigate(target.blockPosition(), 1, false)) target = null;
        }
    }
    @Override public void stop() { target = null; lickTicks = 0; entity.getNavigation().stop(); }

    // ---- save ----------------------------------------------------------------------------------------------------
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Damage", damage); tag.putInt("Mend", mend); tag.putFloat("Fit", fit);
        tag.putBoolean("Shattered", shattered); tag.putBoolean("Lost", lost);
        if (shardToken != null) tag.putUUID("ShardToken", shardToken);
        tag.putInt("ShardsTotal", shardsTotal); tag.putInt("ShardsHeld", shardsHeld); tag.putInt("Reassembly", reassembly);
        tag.putLong("LastProgress", lastProgress); tag.putString("Cause", cause);
        return tag;
    }
    public void load(CompoundTag tag) {
        damage = Math.clamp(tag.getInt("Damage"), 0, TORN); mend = Math.max(0, tag.getInt("Mend"));
        fit = tag.contains("Fit") ? tag.getFloat("Fit") : -1; if (!Float.isFinite(fit)) fit = -1;
        shattered = tag.getBoolean("Shattered"); lost = tag.getBoolean("Lost");
        shardToken = tag.hasUUID("ShardToken") ? tag.getUUID("ShardToken") : null;
        shardsTotal = Math.clamp(tag.getInt("ShardsTotal"), 0, 3); shardsHeld = Math.clamp(tag.getInt("ShardsHeld"), 0, shardsTotal);
        reassembly = Math.max(0, tag.getInt("Reassembly")); lastProgress = tag.getLong("LastProgress"); cause = tag.getString("Cause");
        if (shattered && shardToken == null) { shattered = false; lost = true; }
        target = null;
    }
}
