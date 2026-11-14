package io.github.longshou.mcyukkuri.shop;

import java.util.List;
import net.minecraft.network.chat.Component;

public enum ShopMembership {
    VISITOR(0), MEMBER(8), PATRON(24);

    private static final List<ShopMembership> TIERS = List.of(values());
    private final int threshold;

    ShopMembership(int threshold) { this.threshold = threshold; }

    static ShopMembership fromSpend(int spent) {
        return spent >= PATRON.threshold ? PATRON : spent >= MEMBER.threshold ? MEMBER : VISITOR;
    }

    public static ShopMembership byId(int id) { return TIERS.get(id); }

    boolean allows(ShopMembership required) { return ordinal() >= required.ordinal(); }

    Component nameText() { return Component.translatable("shop.mcyukkuri.membership." + ordinal()); }

    public Component label() {
        return this == PATRON ? nameText()
                : Component.translatable("shop.mcyukkuri.membership.next", nameText(), TIERS.get(ordinal() + 1).threshold);
    }
}
