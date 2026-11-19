package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.network.ShopInfoPayload;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.CharacterWords;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static io.github.longshou.mcyukkuri.shop.ShopHint.*;

/** Keeps the raw temperament and condition values on the server. */
final class ShopInfo {
    static boolean send(ServerPlayer player, ShopRegistry.Entry shop, YukkuriEntity animal) {
        boolean oni = shop.kind == ShopKind.ONI;
        float health = animal.getHealth() / animal.getMaxHealth();
        var hints = new ArrayList<ShopHint>(4);
        for (var word : CharacterWords.select(animal.life().personality().temperament()))
            hints.add(ShopHint.character(word));
        if (oni) {
            hints.add(animal.family().stage() == FamilyLife.Stage.BABY ? FRAGILE_BABY : health < .6f ? HURT : TOUGH);
            hints.add(oniExtra(animal));
        } else {
            hints.add(health < .6f ? RECOVERING : health >= .9f && animal.life().nutrition() >= 60 ? HEALTHY : IN_CARE);
            hints.add(petExtra(animal));
        }
        PacketDistributor.sendToPlayer(player, new ShopInfoPayload(player.containerMenu.containerId, animal.getId(),
                animal.getVariant().ordinal(), ShopMenu.stage(animal), oni, ShopMembership.required(animal).ordinal(),
                hints.stream().mapToInt(ShopHint::ordinal).toArray()));
        GameplayLog.event(animal, "shop", "action=info result=shown shop={} target={} viewer={} kind={} hints={}",
                shop.id, animal.getUUID(), player.getUUID(), shop.kind, hints);
        return true;
    }

    private static ShopHint petExtra(YukkuriEntity animal) {
        if (animal.pet().stress() > BondLevels.UNEASE_CARE_LIMIT) return TENSE;
        if (animal.pet().effectiveGrade().id() >= BadgeGrade.SILVER.id()) return STANDOUT;
        int skills = Integer.bitCount(animal.skills().domesticMask());
        return skills == 0 ? NEW_TO_TRAINING : skills < 3 ? SOME_TRAINING : WELL_TRAINED;
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
