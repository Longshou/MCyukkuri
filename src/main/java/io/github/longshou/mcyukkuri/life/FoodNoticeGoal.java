package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * A free stray sees a player holding food in the open from as far as hunger and nerve carry it ({@link HungerDrive#noticeRange}),
 * stops for the warning beat, then walks to the hand. Within reach the spoken ask is {@code YukkuriSocial.takeFoodApproach}'s;
 * where that cannot ask (too hungry or too fed) the stray waits at the hand drooling for {@link #GIVE_UP} ticks, and the
 * begging latch keeps it from starting again until something has changed. When two others already ring the holder it watches
 * from {@link #HANG_BACK} blocks. Replaces the vanilla tempt scan for these bodies only (a fixed ten blocks through walls);
 * a pane between them is the life plan's window begging, not this goal's.
 */
public final class FoodNoticeGoal extends Goal {
    private static final double CONTACT = 2.25, RING = 3.5, HANG_BACK = 4;
    private static final int GIVE_UP = 400;
    private final YukkuriEntity owner;
    private Player holder;
    private int waited;
    private boolean crowded, reached;

    public FoodNoticeGoal(YukkuriEntity owner) {
        this.owner = owner;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean holds(Player player, double range) {
        var life = owner.life();
        return player.isAlive() && !player.isSpectator() && owner.distanceToSqr(player) <= range * range
                && life.seeksOfferedFood(player) && owner.hasLineOfSight(player)
                && (LearnedFear.hungerOverrides(owner) || life.personality().fear(player.getUUID()) < BondLevels.SOCIAL_FEAR_LIMIT);
    }

    private boolean able() {
        var life = owner.life();
        return life.widensFoodNotice() && !owner.forage().givenUp() && !owner.isPassenger() && !owner.isPanicking() && !life.isSleeping() && life.canEat();
    }

    @Override public boolean canUse() {
        if (!able()) return false;
        double range = owner.life().foodNoticeRange();
        long now = owner.level().getGameTime();
        Player nearest = null;
        for (var player : owner.level().players()) {
            if (holds(player, range) && owner.social().begs().allows(now, owner.life().nutrition(), key(player))
                    && (nearest == null || owner.distanceToSqr(player) < owner.distanceToSqr(nearest))) nearest = player;
        }
        if (nearest == null || owner.social().takeFoodApproach(nearest)) return false;
        holder = nearest;
        return true;
    }

    /** What the latch tells one holder from another by: a different hand is a changed condition. */
    public static long key(Player player) { return player.getUUID().getLeastSignificantBits(); }

    @Override public boolean canContinueToUse() {
        // The keen range keeps its hand through a step or two of margin so a stray at the edge does not flicker in and out.
        return able() && waited < GIVE_UP && holder != null && holds(holder, owner.life().foodNoticeRange() + 2)
                && !owner.social().takeFoodApproach(holder);
    }

    @Override public void start() {
        waited = 0;
        crowded = reached = false;
        owner.speakCue("food_found"); // the first sight of a serving it wants
    }

    @Override public void stop() {
        if (waited >= GIVE_UP) {
            owner.social().begs().ended(owner.level().getGameTime(), owner.life().nutrition(), key(holder));
            GameplayLog.limited(owner, "meal", "food_wait_gave_up", 400, "gave up waiting at a held serving player={} nutrition={}",
                    holder.getUUID(), owner.life().nutrition());
        }
        holder = null;
        owner.getNavigation().stop();
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        double distance = owner.distanceTo(holder);
        owner.getLookControl().setLookAt(holder, 30, 30);
        if (!reached && distance <= CONTACT) {
            reached = true;
            GameplayLog.event(owner, "meal", "food_hand_reached", "reached the hand of a held serving player={} nutrition={}",
                    holder.getUUID(), owner.life().nutrition());
        }
        if (distance > CONTACT && owner.life().noticing(holder)) {
            owner.getNavigation().stop();
            return;
        }
        if (owner.tickCount % 10 == 0) crowded = ringed();
        if (distance > CONTACT && !(crowded && distance <= HANG_BACK)) {
            owner.getNavigation().moveTo(holder, 1.15);
            return;
        }
        owner.getNavigation().stop();
        if (waited++ % 60 == 0) owner.showReaction("drool", "", FaceTier.Tier.MEDIUM);
    }

    /** Two or more other bodies, nearer to the holder than this one, already stand at the holder's feet. */
    private boolean ringed() {
        double mine = owner.distanceToSqr(holder);
        return holder.level().getEntitiesOfClass(YukkuriEntity.class, holder.getBoundingBox().inflate(RING),
                other -> other != owner && other.distanceToSqr(holder) < mine).size() >= 2;
    }
}
