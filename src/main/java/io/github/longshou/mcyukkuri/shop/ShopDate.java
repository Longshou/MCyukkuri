package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** The matchmaking date as one staged scene (about 11 s): the keeper carries the pet over in a basket, the two meet shyly, walk up to each other and nuzzle, the result sinks in, and the pet is carried home.
 *  Bold pets break the ice first, timid ones stay shy longer, a pet with the higher badge shows it off once. The animals are never removed: the basket is a teleport between two animations.
 *  Both animals and the keeper are re-checked every tick; if any is lost the fee is refunded (while unpaid-for) and the pet goes back to its owner. */
public final class ShopDate implements IntPredicate {
    static final String LOCK = "date";
    private static final Set<UUID> HOLDING = new HashSet<>();
    /** Whether a date scene currently has the carrier in this keeper's hand (HumanNpc clears a stray carrier otherwise). */
    public static boolean holding(UUID keeper) { return HOLDING.contains(keeper); }
    public static void reset() { HOLDING.clear(); }

    private final ServerLevel level;
    private final UUID keeperId, petId, studId, buyer;
    private final Vec3 origin, meet;
    private final Walk walk;
    private final int shy;
    private final String flourish;
    private final boolean fondBefore;
    private int phase, pt;
    private boolean paid = true, away, held, ok, fond, done;
    private ItemStack kept = ItemStack.EMPTY;

    /** Both animals must already be locked "date". */
    ShopDate(ServerLevel level, UUID keeperId, YukkuriEntity pet, YukkuriEntity stud, UUID buyer, Vec3 origin, Vec3 meet) {
        this.level = level; this.keeperId = keeperId; petId = pet.getUUID(); studId = stud.getUUID(); this.buyer = buyer; this.origin = origin; this.meet = meet;
        int bold = pet.life().personality().trait(Trait.BOLDNESS), studBold = stud.life().personality().trait(Trait.BOLDNESS);
        shy = (bold >= 55 ? 24 : bold <= 35 ? 60 : 36) + (studBold <= 35 ? 10 : 0);
        flourish = bold > 35 && pet.pet().visibleGrade().id() > stud.pet().visibleGrade().id() ? "proud" : bold >= 55 ? "greet" : null;
        var relations = YukkuriWorldData.get(level);
        fondBefore = relations.affinity(petId, studId) >= 10 && relations.affinity(studId, petId) >= 10;
        walk = new Walk(studId); pet.pet().direct(walk);
    }

    private static final class Walk implements PetLife.Director {
        private final UUID goal;
        private boolean go;
        Walk(UUID goal) { this.goal = goal; }
        @Override public void tick(YukkuriEntity a) {
            if (go && a.level() instanceof ServerLevel l && l.getEntity(goal) instanceof YukkuriEntity o && a.distanceToSqr(o) > 2.0) {
                a.getLookControl().setLookAt(o, 30, 30);
                if (a.tickCount % 5 == 0) a.getNavigation().moveTo(o, .9);
            } else a.getNavigation().stop();
        }
    }

    private YukkuriEntity animal(UUID id) { return level.getEntity(id) instanceof YukkuriEntity y && y.isAlive() ? y : null; }
    private void next() { phase++; pt = 0; }
    private static void teleport(YukkuriEntity y, Vec3 to) { y.getNavigation().stop(); y.moveTo(to.x, to.y, to.z, y.getYRot(), 0); y.setDeltaMovement(Vec3.ZERO); }
    private static void face(YukkuriEntity a, YukkuriEntity b) { a.getLookControl().setLookAt(b, 30, 30); b.getLookControl().setLookAt(a, 30, 30); }
    private void hold(HumanNpc k, boolean on) {
        if (on && !held && k != null) {
            if (ShopCourier.busy(k)) return; // a courier job (or a stray basket) owns the hand: only swing
            kept = k.getMainHandItem().copy(); k.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
            held = true; HOLDING.add(keeperId);
        } else if (!on && held) {
            held = false; HOLDING.remove(keeperId);
            if (k != null) k.setItemInHand(InteractionHand.MAIN_HAND, kept);
            kept = ItemStack.EMPTY;
        }
    }
    private static void release(YukkuriEntity y) {
        y.pet().unlock(LOCK);
        if (y.isCommandPlaying()) y.playAction("idle");
    }
    private void carryHome(YukkuriEntity a) {
        KeeperMenu.poof(level, a);
        var owner = level.getPlayerByUUID(buyer); Vec3 home = origin;
        if (owner != null) { Vec3 near = KeeperMenu.spotBeside(level, a, owner.position()); if (near != null) home = near; }
        teleport(a, home); away = false; KeeperMenu.poof(level, a);
    }
    private void abort(YukkuriEntity a, YukkuriEntity b, HumanNpc k) {
        if (paid) { KeeperMenu.refund(level, buyer, origin); paid = false; }
        hold(k, false);
        if (a != null) { if (away && a.isAlive()) carryHome(a); release(a); }
        if (b != null) release(b);
    }

    @Override public boolean test(int age) {
        var a = animal(petId); var b = animal(studId); var k = level.getEntity(keeperId) instanceof HumanNpc n && n.isAlive() ? n : null;
        if (a == null || b == null || k == null) { abort(a, b, k); return true; }
        pt++;
        switch (phase) {
            case 0 -> { // carried over
                if (pt == 1) { hold(k, true); k.swing(InteractionHand.MAIN_HAND); k.getLookControl().setLookAt(a, 30, 30); a.getNavigation().stop(); a.playAction("basket_in"); }
                if (pt == 15) {
                    KeeperMenu.poof(level, a); teleport(a, meet); away = true; KeeperMenu.poof(level, a);
                    a.playAction("basket_out"); face(a, b); k.swing(InteractionHand.MAIN_HAND);
                }
                if (pt >= 47) { hold(k, false); next(); }
            }
            case 1 -> { // shy hello
                if (pt == 1) { a.playAction("shy"); b.playAction("shy"); a.showReaction("closed_smile", "date_meet", 70); }
                if (pt == 14) b.showReaction("closed_smile", "date_meet", 70);
                if (pt % 10 == 0) face(a, b);
                if (flourish != null && pt == shy) {
                    if (flourish.equals("proud")) { a.playAction("proud"); a.showReaction("smug", "badge_proud", 70); }
                    else { a.playAction("greet"); a.showReaction("open_smile", "hello", 50); }
                }
                if (pt >= shy + (flourish == null ? 0 : 26)) { a.playAction("idle"); b.playAction("idle"); walk.go = true; next(); }
            }
            case 2 -> { // step up to each other
                if (pt % 10 == 0) b.getLookControl().setLookAt(a, 30, 30);
                double d = a.distanceToSqr(b);
                if (pt >= 8 && d <= 2.4 || pt >= 50) {
                    if (d > 6.0) { Vec3 near = KeeperMenu.spotBeside(level, a, b.position()); if (near != null) { teleport(a, near); } }
                    walk.go = false; a.getNavigation().stop(); next();
                }
            }
            case 3 -> { // nuzzle
                if (pt == 1) { a.playAction("nuzzle"); b.playAction("nuzzle"); }
                if (fondBefore && pt % 12 == 6) { var mid = a.position().add(b.position()).scale(.5); level.sendParticles(ParticleTypes.HEART, mid.x, mid.y + .7, mid.z, 2, .25, .15, .25, 0); }
                if (pt % 10 == 0) face(a, b);
                if (pt >= 36) { decide(a, b, k); next(); }
            }
            case 4 -> { if (pt >= 32) { a.playAction("idle"); b.playAction("idle"); next(); } } // let the result sink in
            default -> { // carried home
                if (pt == 1) { hold(k, true); k.swing(InteractionHand.MAIN_HAND); k.getLookControl().setLookAt(a, 30, 30); a.getNavigation().stop(); a.playAction("basket_in"); }
                if (pt == 15) { carryHome(a); a.playAction("basket_out"); }
                if (pt >= 47) { hold(k, false); release(a); release(b); done = true; }
            }
        }
        return done;
    }

    /** The result: relationship, the conception check, the fee and the animals' reactions. */
    private void decide(YukkuriEntity a, YukkuriEntity b, HumanNpc k) {
        var relations = YukkuriWorldData.get(level); relations.relate(petId, studId, 20); relations.relate(studId, petId, 20);
        // The date's emotes leave a command action running, which makes FamilyLife.ready() fail: end both before the checks.
        a.playAction("idle"); b.playAction("idle");
        String why = ShopKeeping.refusal(level, a, b);
        ok = why == null && ShopKeeping.arrangedConceive(level, a, b);
        if (!ok && why == null) why = "conceive_null";
        if (!ok) MCYukkuri.LOGGER.warn("[keeper] matchmaking refused for {} x {}: {}", petId, studId, why);
        fond = ok || fondBefore;
        var sp = level.getServer().getPlayerList().getPlayer(buyer);
        if (ok) {
            paid = false; long until = level.getGameTime() + 24000;
            a.pet().permitBreeding(studId, until); b.pet().permitBreeding(petId, until);
            if (sp != null) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(sp, "shop_date");
            if (sp != null) sp.displayClientMessage(Component.translatable("shop.mcyukkuri.keeper.matched", a.getDisplayName()), true);
        } else {
            paid = false; KeeperMenu.refund(level, buyer, origin);
            if (sp != null) sp.displayClientMessage(Component.translatable("shop.mcyukkuri.keeper.failed_reason", KeeperMenu.failReason(why)), true);
        }
        if (ok) {
            a.playAction("shiawase"); a.showReaction("happy", "expecting", 100);
            b.playAction("greet"); b.showReaction("happy", "date_fond", 80);
            Staging.sparkle(level, a.position().add(0, a.getBbHeight() + .2, 0)); k.swing(InteractionHand.MAIN_HAND);
        } else if (fond) {
            a.playAction("greet"); a.showReaction("happy", "date_fond", 80);
            b.playAction("greet"); b.showReaction("happy", "date_fond", 80);
        } else {
            a.playAction("droop"); a.showReaction("teary", "", 70);
            b.showReaction("neutral", "", 50);
        }
        a.getLookControl().setLookAt(b, 30, 30); b.getLookControl().setLookAt(a, 30, 30);
    }
}
