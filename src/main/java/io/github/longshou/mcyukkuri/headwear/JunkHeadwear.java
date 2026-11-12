package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Stable appearance IDs. Each occupied head or remains record represents one generic item. */
public enum JunkHeadwear {
    PAPER_BOX(1, "paper_box"),
    BOTTLE_CAP(2, "bottle_cap"),
    ICE_CREAM_CONE(3, "ice_cream_cone");

    public static final String KEY = "JunkHeadwear";
    private final int visualId;
    private final String id;

    JunkHeadwear(int visualId, String id) {
        this.visualId = visualId;
        this.id = id;
    }

    public int visualId() { return visualId; }
    public String id() { return id; }
    public static ItemStack item() { return new ItemStack(ModItems.JUNK_HEADWEAR.get()); }

    public static JunkHeadwear fromVisual(int visualId) {
        return switch (visualId) {
            case 1 -> PAPER_BOX;
            case 2 -> BOTTLE_CAP;
            case 3 -> ICE_CREAM_CONE;
            default -> null;
        };
    }

    public static JunkHeadwear read(CompoundTag tag) {
        return switch (tag.getString(KEY)) {
            case "paper_box" -> PAPER_BOX;
            case "bottle_cap" -> BOTTLE_CAP;
            case "ice_cream_cone" -> ICE_CREAM_CONE;
            default -> null;
        };
    }
}
