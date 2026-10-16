package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import java.util.Comparator;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

/** Keeper duties: every three game days the studs breed if the shop has room, and grown nursery young are trained and put on sale. */
public final class ShopKeeping {
    public static final long INTERVAL = 72000, RETRY = 2400;
    /** Largest litter the cap check has to leave room for. */
    private static final int LITTER_MARGIN = 4;

    /** Called every 20 ticks while the keeper is active. */
    public static void tick(HumanNpc keeper) {
        if (!(keeper.level() instanceof ServerLevel level) || !keeper.isAlive()) return;
        for (var y : keeper.animals("shop_nursery")) if ((y.family().adult() || y.childhood().canLiveIndependently()) && !y.pet().busy() && !y.isPassenger()) train(keeper, level, y);
        long now = level.getGameTime();
        if (keeper.nextBreed() == 0) { keeper.setNextBreed(now + INTERVAL); return; }
        if (now < keeper.nextBreed()) return;
        keeper.setNextBreed(now + (breed(keeper, level) ? INTERVAL : RETRY));
    }

    /** The shop restocks only while it holds at most this many animals within 32 blocks, so the studs' local population budget (default 16) has room for a litter. */
    public static final int RESTOCK_MAX_POPULATION = 12;

    private static boolean breed(HumanNpc keeper, ServerLevel level) {
        if (ShopPens.population(level, keeper) + LITTER_MARGIN > ShopMenu.CAPACITY || ShopPens.population(level, keeper) > RESTOCK_MAX_POPULATION) return false;
        var family = FamilyData.get(level); var studs = keeper.animals("stud");
        for (int i = 0; i < studs.size(); i++) for (int j = i + 1; j < studs.size(); j++) {
            var a = studs.get(i); var b = studs.get(j);
            if (a.pet().busy() || b.pet().busy() || family.busyParent(a.getUUID()) || family.busyParent(b.getUUID()) || !FamilyLife.arrangedMatch(a, b)) continue;
            if ("crowded".equals(refusal(level, a, b)) && "crowded".equals(refusal(level, b, a))) continue;
            if (arrangedConceive(level, a, b) || arrangedConceive(level, b, a)) return true;
        }
        return false;
    }

    /** Where an arranged birth happens: the mother's home, else her position. */
    private static GlobalPos anchorOf(ServerLevel level, YukkuriEntity mother) {
        var home = mother.life().homeStand();
        return GlobalPos.of(level.dimension(), home != null ? home : mother.blockPosition());
    }
    /** FamilyData.conceiveRefusal for an arranged pair (the pair is arranged only for the duration of the call), born at the mother's own home. Null means it would succeed. */
    public static String refusal(ServerLevel level, YukkuriEntity mother, YukkuriEntity father) {
        return refusal(level, mother, father, anchorOf(level, mother));
    }
    public static String refusal(ServerLevel level, YukkuriEntity mother, YukkuriEntity father, GlobalPos anchor) {
        mother.family().arrange(father.getUUID()); father.family().arrange(mother.getUUID());
        try { return FamilyData.get(level).conceiveRefusal(mother, father, mother.family().naturalBirthKind(father), anchor); }
        finally { mother.family().arrange(null); father.family().arrange(null); }
    }

    /** Conception between two animals that are not courting in the wild: the pair is arranged only for the duration of the call. */
    public static boolean arrangedConceive(ServerLevel level, YukkuriEntity mother, YukkuriEntity father) {
        return arrangedConceive(level, mother, father, anchorOf(level, mother));
    }
    public static boolean arrangedConceive(ServerLevel level, YukkuriEntity mother, YukkuriEntity father, GlobalPos anchor) {
        mother.family().arrange(father.getUUID()); father.family().arrange(mother.getUUID());
        try { return FamilyData.get(level).conceive(mother, father, mother.family().naturalBirthKind(father), anchor) != null; }
        finally { mother.family().arrange(null); father.family().arrange(null); }
    }

    /** Nursery young that can live on their own become trained stock: abilities from pedigree, a bronze or silver badge when earned, a pen. */
    private static void train(HumanNpc keeper, ServerLevel level, YukkuriEntity y) {
        var shop = level.getEntitiesOfClass(HumanNpc.class, keeper.getBoundingBox().inflate(48), n -> n.isAlive() && n.siteRole().equals("shopkeeper"))
                .stream().min(Comparator.comparingDouble(keeper::distanceToSqr)).orElse(null);
        if (shop == null) return;
        int[] score = new int[4]; var rng = y.getRandom();
        for (var a : Ability.values()) score[a.ordinal()] = Math.clamp(Math.round(20 + y.pet().pedigree() * .4f) + rng.nextInt(21) - 10, 0, 100);
        int m = score[Ability.MANNERS.ordinal()], ac = score[Ability.ACADEMICS.ordinal()], w = score[Ability.WITS.ordinal()];
        var grade = m >= 60 && ac >= 50 && w >= 50 ? BadgeGrade.SILVER : m >= 30 ? BadgeGrade.BRONZE : BadgeGrade.NONE;
        var pen = ShopPens.freePen(level, shop, y, grade.id() >= BadgeGrade.SILVER.id());
        if (pen == null) return;
        for (var a : Ability.values()) y.pet().setAbility(a, score[a.ordinal()]);
        for (int k = 0; k < 3; k++) { int r = 1 + rng.nextInt(2); while (y.pet().routine(k) < r) y.pet().routineCompleted(k); }
        if (grade != BadgeGrade.NONE) y.pet().issue(grade, 2, shop.getUUID());
        HumanCommerce.assign(y, shop.getUUID(), "stock");
        y.getNavigation().stop(); y.moveTo(pen.getX() + .5, pen.getY() + .12, pen.getZ() + .5, y.getYRot(), 0);
    }
    private ShopKeeping() {}
}
