package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import java.util.List;
import net.minecraft.network.chat.Component;

public enum ShopMembership {
    VISITOR(0, 0), MEMBER(8, 2), PATRON(24, 6);

    private static final List<ShopMembership> TIERS = List.of(values());
    private final int petThreshold, oniThreshold;

    ShopMembership(int petThreshold, int oniThreshold) {
        this.petThreshold = petThreshold;
        this.oniThreshold = oniThreshold;
    }

    static ShopMembership fromSpend(ShopKind kind, int spent) {
        return spent >= PATRON.threshold(kind) ? PATRON : spent >= MEMBER.threshold(kind) ? MEMBER : VISITOR;
    }

    static ShopMembership required(YukkuriEntity animal) {
        var grade = animal.pet().effectiveGrade();
        if (animal.profile().dangerToYukkuri() || grade.id() >= BadgeGrade.GOLD.id()) return PATRON;
        if (grade == BadgeGrade.SILVER) return MEMBER;
        return switch (animal.getVariant()) {
            case ALICE, CHEN, MYON, PATCHOULI -> MEMBER;
            default -> VISITOR;
        };
    }

    public static ShopMembership byId(int id) { return TIERS.get(id); }

    boolean allows(ShopMembership required) { return ordinal() >= required.ordinal(); }

    /** Oni stock costs one quarter as much, so the same purchases reach its tiers. */
    int threshold(ShopKind kind) { return kind == ShopKind.ONI ? oniThreshold : petThreshold; }

    public Component nameText() { return Component.translatable("shop.mcyukkuri.membership." + ordinal()); }

    public Component label(ShopKind kind) {
        return this == PATRON ? nameText()
                : Component.translatable("shop.mcyukkuri.membership.next", nameText(), TIERS.get(ordinal() + 1).threshold(kind));
    }
}
