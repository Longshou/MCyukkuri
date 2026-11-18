package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.network.ShopInfoPayload;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static io.github.longshou.mcyukkuri.shop.ShopHint.*;

/** Keeps the raw temperament and condition values on the server. */
final class ShopInfo {
    private record TraitBand(int value, ShopHint low, ShopHint high) {}

    static boolean send(ServerPlayer player, ShopRegistry.Entry shop, YukkuriEntity animal) {
        boolean oni = shop.kind == ShopKind.ONI;
        float health = animal.getHealth() / animal.getMaxHealth();
        var hints = oni ? oniHints(animal, health) : petHints(animal, health);
        PacketDistributor.sendToPlayer(player, new ShopInfoPayload(player.containerMenu.containerId, animal.getId(),
                animal.getVariant().ordinal(), ShopMenu.stage(animal), oni, ShopMembership.required(animal).ordinal(),
                hints.stream().mapToInt(ShopHint::ordinal).toArray()));
        GameplayLog.event(animal, "shop", "action=info result=shown shop={} target={} viewer={} kind={} hints={}",
                shop.id, animal.getUUID(), player.getUUID(), shop.kind, hints);
        return true;
    }

    private static List<ShopHint> petHints(YukkuriEntity animal, float health) {
        var p = animal.life().personality();
        var hints = new ArrayList<ShopHint>(4);
        List.of(new TraitBand(p.boldness(), TIMID, BOLD), new TraitBand(p.friendliness(), SLOW_TO_WARM, FRIENDLY),
                    new TraitBand(p.attachment(), INDEPENDENT, ATTACHED), new TraitBand(p.selfControl(), IMPATIENT, PATIENT),
                    new TraitBand(p.intelligence(), SLOW_LEARNER, QUICK_LEARNER)).stream()
                .filter(band -> Math.abs(band.value() - 50) >= 15)
                .sorted(Comparator.comparingInt((TraitBand band) -> Math.abs(band.value() - 50)).reversed())
                .limit(2).map(band -> band.value() < 50 ? band.low() : band.high()).forEach(hints::add);
        if (hints.isEmpty()) hints.add(EVEN_TEMPERED);
        hints.add(health < .6f ? RECOVERING : health >= .9f && animal.life().nutrition() >= 60 ? HEALTHY : IN_CARE);
        hints.add(petExtra(animal));
        return hints;
    }

    private static ShopHint petExtra(YukkuriEntity animal) {
        if (animal.pet().stress() > BondLevels.UNEASE_CARE_LIMIT) return TENSE;
        if (animal.pet().effectiveGrade().id() >= BadgeGrade.SILVER.id()) return STANDOUT;
        int skills = 0;
        for (var skill : PetSkill.values()) if (animal.pet().lessons().knows(skill)) skills++;
        return skills == 0 ? NEW_TO_TRAINING : skills < 3 ? SOME_TRAINING : WELL_TRAINED;
    }

    private static List<ShopHint> oniHints(YukkuriEntity animal, float health) {
        var p = animal.life().personality();
        double insolence = .45 * p.boldness() + .55 * (100 - p.friendliness());
        double numbness = .65 * (100 - p.intelligence()) + .35 * (100 - p.boldness());
        ShopHint temper = insolence >= 60 ? DEFIANT : insolence < 40 ? SUBMISSIVE : QUIET;
        ShopHint control = p.selfControl() <= 35 ? PANICKY : p.selfControl() >= 65 ? ENDURING
                : numbness >= 60 ? SLOW_TO_REACT : STEADY;
        ShopHint body = animal.family().stage() == FamilyLife.Stage.BABY ? FRAGILE_BABY : health < .6f ? HURT : TOUGH;
        return List.of(temper, control, body, oniExtra(animal));
    }

    private static ShopHint oniExtra(YukkuriEntity animal) {
        if (animal.life().nutrition() < 35) return HUNGRY;
        if (animal.life().hygiene() < 40) return DIRTY;
        if (animal.subspecies().kind() == SubspeciesLife.Kind.WASA_REIMU) return HAIRY;
        if (animal.subspecies().congenital() != SubspeciesLife.Congenital.NORMAL) return CONGENITAL;
        return animal.mind().preventionRemaining() > 0 ? PREVENTION : SETTLED;
    }

    private ShopInfo() {}
}
