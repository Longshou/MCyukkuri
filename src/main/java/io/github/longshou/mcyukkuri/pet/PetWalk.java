package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import net.minecraft.world.entity.LivingEntity;

/**
 * A walk on the harness (docs/PET_SHOP.md): while the pet is leashed to its own owner, the owner's horizontal travel adds up
 * and the pet's actual travel add up by their smaller distance (teleports, vehicles and elytra flight do not count). Every 64 blocks is one walk unit, at most 4 units a game day take effect: boredom,
 * the need for affection, stress, energy and a little nutrition change. Everything is counted in blocks moved, never in frames.
 * {@link #walking(YukkuriEntity)} is the hook for encounters: a pet on a walk passing wild yukkuri is a natural poise practice.
 */
public final class PetWalk {
    /** Blocks per walk unit, units per game day that count, ticks between two "I do not want to go" protests. */
    public static final double UNIT = 64;
    public static final int UNITS_PER_DAY = 4, TUG_INTERVAL = 400;
    private final YukkuriEntity animal;
    private Vec3 last, lastPet;
    private boolean moving;
    private double carry;
    private int units, tugWait;
    private UUID shoreOwner;
    private InteractionHand shoreHand;
    private long shoreUntil;

    public PetWalk(YukkuriEntity animal) { this.animal = animal; }

    /** Whether the pet is currently on a lead held by its owner. */
    public static boolean walking(YukkuriEntity pet) { return pet.pet().care().walk().walking(); }
    public boolean walking() { return moving; }
    public int unitsToday() { return units; }
    public double carry() { return carry; }

    void newDay() { units = 0; }

    /** One actual lead interaction reserves landing; the lead remains in the owner's hand until attachment. */
    public boolean requestLeadOnShore(Player player, InteractionHand hand) {
        if (!animal.isPassenger() || animal.pet().care().leashRefusal(player) != null
                || !player.getItemInHand(hand).is(Items.LEAD) || !animal.aquaticLife().requestShoreWork(ShoreWork.HUMAN)) return false;
        if (shoreOwner == null) {
            shoreOwner = player.getUUID(); shoreHand = hand; shoreUntil = animal.level().getGameTime() + 600;
            GameplayLog.event(animal, "pet-walk", "lead shore pending owner={} until={}", shoreOwner, shoreUntil);
        }
        player.displayClientMessage(Component.translatable("aquatic.mcyukkuri.shore_pending"), true);
        return true;
    }
    private void tickShoreLead() {
        if (shoreOwner == null || !(animal.level() instanceof ServerLevel level)) return;
        var player = level.getPlayerByUUID(shoreOwner);
        String why = player == null || !player.isAlive() ? "owner_unavailable"
                : level.getGameTime() >= shoreUntil ? "landing_timeout"
                : animal.pet().care().leashRefusal(player) != null ? "ownership_or_harness_changed"
                : !player.getItemInHand(shoreHand).is(Items.LEAD) ? "lead_not_held"
                : animal.distanceToSqr(player) > 64 ? "owner_far" : animal.isLeashed() ? "already_leashed" : null;
        if (why == null && animal.isPassenger()) {
            if (animal.aquaticLife().requestShoreWork(ShoreWork.HUMAN)) return;
            why = "landing_unavailable";
        }
        if (why == null && (!animal.onGround() || animal.aquaticLife().shoreVisitRequested())) return;
        if (why == null) {
            animal.setLeashedTo(player, true);
            if (!player.getAbilities().instabuild) player.getItemInHand(shoreHand).shrink(1);
            GameplayLog.event(animal, "pet-walk", "lead attached after landing owner={}", shoreOwner);
        } else GameplayLog.event(animal, "pet-walk", "lead shore cancelled owner={} reason={}", shoreOwner, why);
        shoreOwner = null; shoreHand = null; shoreUntil = 0;
        animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
    }
    public void tick() {
        tickShoreLead();
        var holder = animal.getLeashHolder();
        // The owner is a player, or a resident (HumanNpc) walking its own pet: both count the same blocks.
        if (!(holder instanceof Player || holder instanceof HumanNpc) || !animal.pet().isPet() || !animal.pet().ownedBy(holder.getUUID()) || holder.level() != animal.level()) { last = null; lastPet = null; moving = false; return; }
        var owner = (LivingEntity) holder;
        if (animal.isPassenger()) {
            animal.aquaticLife().requestShoreWork(ShoreWork.HUMAN); last = null; lastPet = null; moving = false; return;
        }
        if (!animal.onGround() || animal.aquaticLife().shoreVisitRequested()) { last = null; lastPet = null; moving = false; return; }
        animal.aquaticLife().holdShoreWork(ShoreWork.HUMAN);
        var now = owner.position();
        var petNow = animal.position();
        moving = false;
        if (last != null && lastPet != null) {
            double dx = now.x - last.x, dz = now.z - last.z;
            double px = petNow.x - lastPet.x, pz = petNow.z - lastPet.z;
            double ownerMoved = Math.hypot(dx, dz), petMoved = Math.hypot(px, pz);
            double moved = Math.min(ownerMoved, petMoved);
            if (moved > .001 && ownerMoved <= 1.0 && petMoved <= 1.0 && dx * px + dz * pz > 0
                    && animal.distanceToSqr(owner) <= 36 && !animal.life().hasUrgentNeed() && !animal.pet().busy()
                    && !owner.isPassenger() && !owner.isFallFlying()) {
                moving = true;
                carry += moved;
                animal.pet().care().requests().walked(moved);
                while (carry >= UNIT) { carry -= UNIT; unit(owner); }
            }
        }
        last = now; lastPet = petNow;
        if (tugWait > 0) tugWait--;
        if (animal.life().hasUrgentNeed() && tugWait == 0) { // it will not follow while something is urgent: the owner feels the pull of the lead
            tugWait = TUG_INTERVAL;
            animal.showReaction("teary", "harness_tug", 70);
        }
    }

    private void unit(LivingEntity owner) {
        if (units >= UNITS_PER_DAY) return;
        units++;
        var daily = animal.dailyLife(); var pet = animal.pet();
        daily.entertained(6); daily.comfort(4); pet.addStress(-2);
        animal.life().spendEnergy(2); animal.life().spendNutrition(1);
        pet.contact(owner.getUUID(), true);
        if (units == 3) {
            animal.showReaction("happy", "walk_enjoy", 80);
            if (animal.level() instanceof ServerLevel level) Staging.sparkle(level, animal.position().add(0, animal.getBbHeight() + .3, 0));
        }
    }

    public int saveUnits() { return units; }
    public void load(int savedUnits, double savedCarry) { units = Math.clamp(savedUnits, 0, UNITS_PER_DAY); carry = Math.clamp(savedCarry, 0, UNIT); last = null; lastPet = null; moving = false; }
}
