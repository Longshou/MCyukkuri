package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The authored differences between shops. The site, staff jobs and transactions are shared. */
public enum ShopKind {
    PET("shopkeeper", "shop_staff", "mcy_shop_", new BlockPos(-11, -3, -11), new BlockPos(11, 12, 16), new BlockPos(0, 0, -20)),
    ONI("oni_shopkeeper", "oni_staff", "mcy_oni_", new BlockPos(-8, -3, -14), new BlockPos(14, 10, 7), new BlockPos(2, 0, -22));

    final String clerkRole, staffRole, markers;
    final BlockPos frontCorner, backCorner, departure;

    ShopKind(String clerkRole, String staffRole, String markers, BlockPos frontCorner, BlockPos backCorner, BlockPos departure) {
        this.clerkRole = clerkRole;
        this.staffRole = staffRole;
        this.markers = markers;
        this.frontCorner = frontCorner;
        this.backCorner = backCorner;
        this.departure = departure;
    }

    static ShopKind forStaff(HumanNpc npc) {
        String role = npc.siteRole();
        return role.equals(ONI.clerkRole) || role.equals(ONI.staffRole) ? ONI : PET;
    }

    Item grain() { return this == ONI ? ModItems.GRAIN_BUDGET.get() : ModItems.GRAIN_REGULAR.get(); }
    Item buybackCurrency() { return this == ONI ? Items.GOLD_NUGGET : Items.EMERALD; }

    void prepareStock(YukkuriEntity animal) {
        if (this == ONI) animal.mind().preventSyndrome();
    }

    boolean accepts(YukkuriEntity animal) {
        return this == PET || !animal.profile().dangerToYukkuri();
    }
}
