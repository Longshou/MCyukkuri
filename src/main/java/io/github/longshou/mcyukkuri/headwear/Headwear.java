package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import io.github.longshou.mcyukkuri.body.BodyPart;
import io.github.longshou.mcyukkuri.body.InjuryModel;
import io.github.longshou.mcyukkuri.family.HatCarry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The accessory as a second life (docs/HEADWEAR.md; komicolle 40720 No.11518829): it grows with the body, mends itself while
 * its owner can take it easy, can be torn and finally shatter, and its pieces are licked together and kept until they knit.
 * Without it the owner is not recognised as a yukkuri by anyone not clever enough ({@link Recognition}), and is scorned for it
 * ({@link Contempt}): that contempt is the root of every social consequence, the sanction (bullying) included.
 *
 * <p>Worn and boat-deployed condition lives with the owner. A loose accessory carries its physical damage and repair
 * progress, while loose fragments are interchangeable within their accessory kind. The numbers are project design.
 */
public final class Headwear extends Goal {
    public enum State { WORN, OFF, SHATTERED, REASSEMBLING, LOST }
    /** 0 intact, 1 frayed, 2 torn. One more and it shatters. */
    public static final int TORN = 2;
    /** Mend progress needed per level; a calm owner adds 2 per tick, an uneasy one 1, a sweetened or broken mind none. */
    public static final int MEND_FRAYED = 6000, MEND_TORN = 12000, REASSEMBLE = 12000;
    /** Without any found piece for three days the owner gives the hat up for lost. */
    public static final int GIVE_UP_TICKS = 72000;
    /** Project design: a bare recipient without an existing broken-hat tally needs three real fragments. */
    private static final int REPLACEMENT_SHARDS = 3;
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
    private String gatherCheck="not_checked";
    private long gatherScanTick=-1;
    private int gatherCandidates,gatherReachable;
    // contempt for the hatless this individual cannot recognise (Contempt)
    private int contemptWait;
    private String lastContact = "none";
    /** Runtime-only pair cooldown shared by passive contempt and a peer greeting. */
    private final Map<UUID, Long> hatlessContactUntil = new HashMap<>();

    public Headwear(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        scanWait = 20 + entity.getRandom().nextInt(40);
        contemptWait = 40 + entity.getRandom().nextInt(80);
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
    public boolean reassembling() { return state() == State.REASSEMBLING; }
    /** Snapshot before detaching, so held fragments and elapsed repair stay with the physical item. */
    public void writeAccessoryState(CompoundTag tag) {
        if (reassembling()) {
            tag.putInt("HatDamage", TORN);
            tag.putInt("HatPieces", shardsTotal);
            tag.putInt("HatReassembly", reassembly);
        } else {
            if (damage > 0) tag.putInt("HatDamage", damage);
            if (mend > 0) tag.putInt("HatMend", mend);
        }
    }
    /** Called only after the removable physical state was copied into its item. */
    public void detachReassembly() {
        if (!reassembling()) return;
        GameplayLog.event(entity,"headwear","reassembling accessory detached held={} total={} progress={}",shardsHeld,shardsTotal,reassembly);
        clear();damage=0;target=null;
    }
    public void receiveAccessoryState(CompoundTag tag) {
        clear();
        damage=Math.clamp(tag.getInt("HatDamage"),0,TORN);
        mend=Math.max(0,tag.getInt("HatMend"));
        fit=entity.family().scale();
        if (tag.getInt("HatPieces") > 0) {
            entity.life().removeAccessory();
            shattered=true;shardToken=UUID.randomUUID();
            shardsTotal=shardsHeld=Math.clamp(tag.getInt("HatPieces"),2,3);
            reassembly=Math.clamp(tag.getInt("HatReassembly"),0,REASSEMBLE-1);
            lastProgress=entity.level().getGameTime();
            GameplayLog.event(entity,"headwear","reassembling accessory received held={} total={} progress={}",shardsHeld,shardsTotal,reassembly);
        }
    }
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
                + " " + entity.life().custody().detail() + (target != null ? " gathering" : "")
                + " gatherCheck=" + gatherCheck + " scanTick=" + gatherScanTick + " candidates=" + gatherCandidates + " reachable=" + gatherReachable;
    }
    /** Read-only encounter diagnostics; inspecting status never performs an observation or changes its cooldown. */
    public String contactDetail() {
        return "wait=" + Math.max(0, contemptWait) + " last=" + lastContact + " " + Contempt.describe(entity);
    }
    long contactWait(YukkuriEntity other) {
        return Math.max(0, hatlessContactUntil.getOrDefault(other.getUUID(), 0L) - entity.level().getGameTime());
    }
    void recordContact(YukkuriEntity other, boolean monster) {
        lastContact = (monster ? "monster" : "scorn") + ":#" + other.getId() + "@" + entity.level().getGameTime();
    }
    /** Lasting pressure on the mind, per minute (MindLife.sample). */
    public float uneasePerMinute() {
        return switch (state()) {
            case WORN -> damage >= TORN ? 1f : damage == 1 ? .5f : 0f;
            case OFF -> entity.life().accessoryCustodied() ? 0f : 3f;
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

    /** A recent non-urgent reaction to this same hatless target prevents another social approach or contempt line. */
    public boolean readyForHatlessContact(YukkuriEntity target) {
        return target != null && hatlessContactUntil.getOrDefault(target.getUUID(), 0L) <= entity.level().getGameTime();
    }

    /** Keep pair-specific social noise out of saves; its only purpose is to settle the current encounter. */
    public void coolHatlessContact(YukkuriEntity target, int ticks) {
        if (target == null || ticks <= 0) return;
        long now = entity.level().getGameTime();
        hatlessContactUntil.put(target.getUUID(), now + ticks);
        if (hatlessContactUntil.size() > 24) {
            hatlessContactUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (hatlessContactUntil.size() > 24) hatlessContactUntil.entrySet().stream().min(Comparator.comparingLong(Map.Entry::getValue))
                    .ifPresent(entry -> hatlessContactUntil.remove(entry.getKey()));
        }
    }

    /** Lecture: the hat feeds on the feeling of being able to take it easy. */
    private int feeling() {
        if (entity.mind().nonYukkuri()) return 0;
        float u = entity.mind().unease();
        return u < MindLife.UNEASY ? 2 : u < MindLife.SWEETENED ? 1 : 0;
    }

    // ---- recognition (package 7): Recognition owns the shared identity rule ---------------------------------------
    /** The observer cannot tell that this hatless individual is a yukkuri, kin included (komicolle 11518847). */
    public static boolean isUnrecognizedHatless(YukkuriEntity observer, YukkuriEntity target) { return Recognition.unrecognizedHatless(observer, target); }
    /** Unrecognised, and nobody's property: the test for driving off, bullying or treating as food (C11: pets and owned are safe). */
    public static boolean fairGame(YukkuriEntity observer, YukkuriEntity target) { return Recognition.fairGame(observer, target); }
    /** Remains count as a yukkuri (kin or same species) when the hat was on at death, or for an observer clever enough. */
    public static boolean remainsRecognized(YukkuriEntity observer, CompoundTag remains) { return Recognition.remainsRecognized(observer, remains); }

    // ---- damage ----------------------------------------------------------------------------------------------------
    /** A blow the owner survived (YukkuriEntity.hurt). {@code part} is the body region hit, or null. */
    public void struck(DamageSource source, float amount, BodyPart part) {
        if (entity.level().isClientSide || !entity.life().hasAccessory() || amount <= 0) return;
        var random = entity.getRandom();
        if (source.is(DamageTypeTags.IS_FIRE)) { if (random.nextInt(100) < 35) harm(1, "fire", true); return; }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) { harm(amount >= 6 ? 3 : 2, "explosion", false); return; }
        if (source.is(DamageTypes.FALLING_ANVIL) || source.is(DamageTypes.FALLING_BLOCK) || source.is(DamageTypes.FALLING_STALACTITE)) { harm(2, "crushed", false); return; }
        if (source.getEntity() == null || InjuryModel.attrition(source) || source.is(DamageTypeTags.IS_FALL)) return;
        boolean predator = source.getEntity() instanceof YukkuriEntity y && y.profile().dangerToYukkuri();
        int chance = part == BodyPart.TOP ? 70 : predator ? 45 : amount >= 3 ? 15 : 0;
        if (random.nextInt(100) < chance) harm(amount >= 5 ? 2 : 1, predator ? "bitten" : "struck", false);
    }
    /** Tear the worn hat by {@code levels}; past torn it shatters ({@code burn}: burns away, nothing left to gather). */
    public boolean harm(int levels, String why, boolean burn) {
        if (entity.level().isClientSide || levels <= 0 || !entity.life().hasAccessory()) return false;
        damage += levels; mend = 0; cause = why;
        if (damage > TORN) { shatter(why, burn); return true; }
        entity.mind().add(4f * levels, damage >= TORN ? "accessory_torn" : "accessory_frayed");
        if (entity.isAlive()) entity.showReaction("teary", "accessory_torn", 80);
        GameplayLog.event(entity, "headwear", "damaged before={} after={} cause={}", damage - levels, damage, why);
        return true;
    }
    /** The hat breaks apart: two or three pieces fall around the owner, who is now a stranger to everyone. */
    public void shatter(String why, boolean burned) {
        if (entity.level().isClientSide || !entity.life().hasAccessory()) return;
        if (entity.isVehicle() && HatCarry.hasHat(entity)) HatCarry.putDown(entity);
        var item = new ItemStack(ModItems.accessoryFor(entity));
        entity.life().removeAccessory();
        damage = 0; mend = 0; reassembly = 0; shardsHeld = 0; cause = why;
        lastProgress = entity.level().getGameTime();
        shattered = !burned; lost = burned;
        shardToken = burned ? null : UUID.randomUUID();
        shardsTotal = burned ? 0 : 2 + entity.getRandom().nextInt(2);
        int emitted=0;
        for (int piece = 0; piece < shardsTotal; piece++) if(dropShard(item))emitted++;
        entity.mind().add(burned ? 20 : 12, burned ? "accessory_burned" : "accessory_shattered");
        if (entity.isAlive()) { entity.mind().felt(EmotionEvent.ACCESSORY_LOST); entity.showReaction("cry", "lost_accessory", 140); }
        GameplayLog.event(entity, "headwear", "accessory destroyed burned={} cause={} pieces={} emitted={} token={}", burned, why, shardsTotal, emitted, shardToken);
    }
    /** @return whether the world accepted this one physical fragment. */
    private boolean dropShard(ItemStack hat) {
        var stack = new ItemStack(ModItems.ACCESSORY_SHARD.get());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString("Variant", entity.getVariant().assetId());
            tag.putString("SourceModel", ModItems.accessoryModelFor(entity));
            tag.putString("Accessory", BuiltInRegistries.ITEM.getKey(hat.getItem()).toString());
        });
        var drop = new ItemEntity(entity.level(), entity.getX(), entity.getEyeY(), entity.getZ(), stack);
        double angle = entity.getRandom().nextDouble() * Math.PI * 2;
        drop.setDeltaMovement(Math.cos(angle) * .12, .2, Math.sin(angle) * .12);
        drop.setPickUpDelay(40);
        return entity.level().addFreshEntity(drop);
    }

    // ---- shards ----------------------------------------------------------------------------------------------------
    /** One compatible physical piece, whether found on the ground or handed over. */
    public boolean takeShard(ItemStack stack, Player giver) {
        if (!(entity.level() instanceof ServerLevel) || transferredShardRefusalReason(stack) != null) return false;
        if (state() != State.SHATTERED) {
            State previous = state();
            // Retire a genuinely lost hat's old return capability only after accepting replacement material.
            // A still-custodied hat is rejected above; scanning never alters ownership or repair state.
            entity.life().removeAccessory();
            shattered = true; lost = false;
            damage = mend = reassembly = 0;
            if (shardsTotal < 2 || shardsTotal > 3) shardsTotal = REPLACEMENT_SHARDS;
            if (shardToken == null) shardToken = UUID.randomUUID();
            cause = "replacement_fragments";
            GameplayLog.event(entity,"headwear","fragment replacement started previous={} held={} total={} token={}",
                    previous,shardsHeld,shardsTotal,shardToken);
        }
        int before=shardsHeld;
        shardsHeld++; lastProgress = entity.level().getGameTime();
        boolean complete = shardsHeld >= shardsTotal;
        if (complete && giver != null) entity.life().personality().accessoryReturned(giver.getUUID());
        entity.mind().add(complete ? -8 : -3, "shard_gathered");
        entity.showReaction(complete ? "happy" : "teary", "shard_gathered", 80);
        GameplayLog.event(entity, "headwear", "piece gathered held={} -> {} total={} token={} giver={}", before, shardsHeld, shardsTotal, shardToken, giver == null ? null : giver.getUUID());
        return true;
    }
    public boolean canReceiveTransferredShard(ItemStack stack) { return transferredShardRefusalReason(stack)==null; }
    /** Read-only category shared by player transfer and the actual gathering goal. */
    public String transferredShardRefusalReason(ItemStack stack) {
        if(stack.isEmpty()||!stack.is(ModItems.ACCESSORY_SHARD.get()))return "kind";
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(tag.getBoolean("Cut"))return "cut";
        var id=ResourceLocation.tryParse(tag.getString("Accessory"));
        var item=id==null?null:BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if(item==null)return "kind";
        String incompatible=AccessoryItem.recipientProfileMismatchReason(entity,item,tag);
        if(incompatible!=null)return "incompatible_"+incompatible;
        return shardReceiptRefusalReason();
    }
    /** Missing hats can be rebuilt from compatible material; worn, knitting and custodied hats need none. */
    private String shardReceiptRefusalReason() {
        String unavailable=entity.life().accessoryReceiptRefusalReason();
        if(unavailable!=null)return unavailable;
        if(state()==State.REASSEMBLING)return "already_full";
        if(state()==State.SHATTERED&&shardToken==null)return "missing_recipient_token";
        int required=shardsTotal>=2&&shardsTotal<=3?shardsTotal:REPLACEMENT_SHARDS;
        return shardsHeld>=required?"already_full":null;
    }
    /**
     * A whole compatible accessory is about to replace this shattered one.  The fragments already gathered in this
     * entity are still real repair material, so release them one by one before accepting the replacement.  Each
     * successful spawn is immediately reflected in {@code shardsHeld}; a failed spawn leaves the rest held and makes
     * the caller refuse the replacement rather than allowing {@link #clock()} to erase them.
     */
    public boolean releaseHeldShardsForWholeAccessory() {
        if (shardsHeld == 0) return true;
        if (shardsTotal <= 0 || shardsHeld > shardsTotal) {
            GameplayLog.limited(entity, "headwear", "replacement_held_shards_invalid", 40,
                    "whole accessory replacement refused reason=invalid_held_shard_count held={} total={}", shardsHeld, shardsTotal);
            return false;
        }
        var hat = new ItemStack(ModItems.accessoryFor(entity));
        int initiallyHeld = shardsHeld;
        while (shardsHeld > 0) {
            if (!dropShard(hat)) {
                GameplayLog.limited(entity, "headwear", "replacement_held_shards_drop_failed", 40,
                        "whole accessory replacement refused reason=held_shard_drop_failed released={} remaining={} token={}",
                        initiallyHeld - shardsHeld, shardsHeld, shardToken);
                return false;
            }
            shardsHeld--;
        }
        target = null;
        GameplayLog.event(entity, "headwear", "held fragments released for whole accessory replacement released={} token={}",
                initiallyHeld, shardToken);
        return true;
    }
    /** Lose the remaining pieces for good (destroyed shard item, test door). */
    public void loseForGood(String why) {
        if (entity.life().hasAccessory() || lost) return;
        lost = true; shattered = false; shardToken = null; cause = why;
        if (entity.isAlive()) { entity.mind().felt(EmotionEvent.ACCESSORY_LOST); entity.showReaction("cry", "lost_accessory", 140); }
        GameplayLog.event(entity, "headwear", "accessory lost permanently cause={}", why);
    }
    /** Positive destruction of the actual deployed hat invalidates its sole token, even after an earlier drift loss. */
    public boolean deployedDestroyed(UUID owner, UUID token, String why) {
        if (entity.level().isClientSide || !entity.life().matchesAccessory(owner, token)) return false;
        entity.life().removeAccessory(why);
        damage = mend = reassembly = shardsHeld = shardsTotal = 0;
        if ("burned".equals(why)) entity.mind().add(20, "accessory_burned");
        loseForGood(why);
        return true;
    }
    private void restore() {
        shattered = false; lost = false; shardsTotal = shardsHeld = 0; reassembly = 0; shardToken = null; mend = 0;
        damage = TORN; // the seams still show; it mends from here as a worn hat
        entity.life().wearAccessory();
        entity.mind().felt(EmotionEvent.ACCESSORY_RETURNED); entity.showReaction("happy", "returned_accessory", 120);
        GameplayLog.event(entity, "headwear", "accessory reassembled damage={}", damage);
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

    private void mendStep(int feeling, String where) {
        if (damage > 0 && feeling > 0 && (mend += feeling) >= (damage >= TORN ? MEND_TORN : MEND_FRAYED)) {
            damage--; mend = 0;
            if (entity.isAlive()) entity.showReaction("happy", "accessory_mended", 60);
            GameplayLog.event(entity, "headwear", "accessory mended damage={} -> {} where={}", damage + 1, damage, where);
        }
    }

    // ---- every tick --------------------------------------------------------------------------------------------------
    public void clock() {
        if (entity.level().isClientSide) return;
        if (scanWait > 0) scanWait--;
        if (--contemptWait <= 0) contemptWait = Contempt.observe(entity);
        boolean worn = entity.life().hasAccessory();
        if (worn && (shattered || lost)) clear(); // put back on by some other path
        float body = entity.family().scale();
        if (fit <= 0 || fit > body) fit = body; // the hat never outgrows its owner
        int feeling = feeling();
        if (worn) {
            if (fit < body && feeling > 0 && entity.tickCount % 20 == 0) fit = Math.min(body, fit + GROWTH_PER_SECOND * feeling * Math.max(.3f, body));
            mendStep(feeling, "worn");
        } else if (state() == State.REASSEMBLING) {
            // Kept close and knitting even while miserable; only a broken mind stops it (recovery is not meant to be cruel).
            if (!entity.mind().nonYukkuri() && (reassembly += Math.max(1, feeling)) >= REASSEMBLE) restore();
        } else if (state() == State.SHATTERED && entity.level().getGameTime() - lastProgress > GIVE_UP_TICKS) loseForGood("gave_up");
        entity.syncHeadwear(visual());
    }

    // ---- goal: lick the pieces together ----------------------------------------------------------------------------
    public boolean active() { return target != null; }
    public String activity() { return target == null ? Behaviors.IDLE : lickTicks > 0 ? Behaviors.SHARD_LICK : Behaviors.SHARD_SEEK; }
    private String inabilityReason() {
        if(!entity.isAlive())return "not_alive";
        if(entity.isNoAi())return "no_ai";
        if(entity.isPanicking())return "panicking";
        if(entity.isCommandPlaying())return "command";
        if(entity.isPassenger())return "passenger";
        if(entity.life().isSleeping())return "sleeping";
        if(entity.life().mealTicks()>0)return "eating";
        if(entity.operations().active())return "operation";
        if(entity.mind().nonYukkuri())return "mind_broken";
        if(!entity.childhood().canLearn())return "movement_not_learned";
        return null;
    }
    private boolean able() { return inabilityReason()==null; }
    @Override public boolean canUse() {
        if (target != null) return true;
        if (scanWait > 0) return false;
        String blocked=shardReceiptRefusalReason();
        if(blocked==null)blocked=inabilityReason();
        if(blocked!=null){gatherCheck=blocked;return false;}
        scanWait = 60;
        var candidates = entity.level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(12, 4, 12),
                item -> item.isAlive() && !item.hasPickUpDelay() && canReceiveTransferredShard(item.getItem()));
        gatherCandidates=candidates.size();gatherReachable=0;gatherScanTick=entity.level().getGameTime();
        for(var item:candidates){
            if(!entity.life().reachable(item.blockPosition(),false))continue;
            gatherReachable++;
            if(target==null||entity.distanceToSqr(item)<entity.distanceToSqr(target))target=item;
        }
        gatherCheck=target!=null?"target_selected":candidates.isEmpty()?"no_compatible_fragments":"no_reachable_fragments";
        if(target==null&&!candidates.isEmpty())GameplayLog.limited(entity,"headwear","shard_scan_no_path",200,
                "shard scan blocked reason=no_reachable_fragments candidates={} sampleDrop={} samplePosition={} held={} total={}",
                gatherCandidates,candidates.getFirst().getUUID(),candidates.getFirst().blockPosition(),shardsHeld,shardsTotal);
        elapsed = lickTicks = 0;
        return target != null;
    }
    @Override public boolean canContinueToUse() {
        return target != null && target.isAlive() && canReceiveTransferredShard(target.getItem()) && elapsed < 600 && able();
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.social().stop();
        if(target!=null)GameplayLog.event(entity,"headwear","shard gathering started drop={} count={} state={} held={} total={}",
                target.getUUID(),target.getItem().getCount(),stateId(),shardsHeld,shardsTotal);
    }
    @Override public void tick() {
        elapsed++;
        if (target == null) return;
        entity.getLookControl().setLookAt(target, 30, 30);
        if (entity.distanceToSqr(target) < 1.4) {
            entity.getNavigation().stop();
            if (++lickTicks >= 32) {
                var stack = target.getItem();
                int before=stack.getCount();
                if (takeShard(stack, null)) {
                    stack.shrink(1);
                    GameplayLog.event(entity,"headwear","ground shard consumed drop={} count={} -> {}",target.getUUID(),before,stack.getCount());
                    if (stack.isEmpty()) target.discard(); else target.setItem(stack);
                }
                target = null;
            }
        } else {
            lickTicks = 0;
            if (elapsed % 10 == 1 && !entity.life().navigate(target.blockPosition(), 1, false)) {
                GameplayLog.limited(entity,"headwear","shard_path_failed",100,"shard gathering interrupted reason=no_path drop={}",target.getUUID());
                target = null;
            }
        }
    }
    @Override public void stop() {
        if(target!=null)GameplayLog.limited(entity,"headwear","shard_gather_interrupted",100,
                "shard gathering interrupted drop={} alive={} elapsed={} held={} total={}",target.getUUID(),target.isAlive(),
                elapsed,shardsHeld,shardsTotal);
        target = null; lickTicks = 0; entity.getNavigation().stop();
    }

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
        target = null; hatlessContactUntil.clear();
        gatherCheck="not_checked";gatherScanTick=-1;gatherCandidates=gatherReachable=0;
    }
}
