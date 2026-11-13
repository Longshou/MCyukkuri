package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * Inventory icons for the items whose world look is a 3D prop (toys, devices, medicines, care tools): the slot shows the
 * flat 16x16 sprite {@code models/item/icon/<item>.json} (art/props/item-icons, scripts/export_item_icons.py); every other
 * view keeps the item's own model, so the prop is still what is placed, held, carried in the mouth, dropped and framed.
 */
public final class ItemIcons {
    private static final String FOLDER = "item/icon/";

    /** Every icon model shipped under models/item/icon/ is baked as a standalone model. */
    static void register(ModelEvent.RegisterAdditional event) {
        Minecraft.getInstance().getResourceManager().listResources("models/" + FOLDER.substring(0, FOLDER.length() - 1),
                file -> file.getNamespace().equals(MCYukkuri.MOD_ID) && file.getPath().endsWith(".json")).keySet()
                .forEach(file -> event.register(ModelResourceLocation.standalone(MCYukkuri.id(
                        file.getPath().substring("models/".length(), file.getPath().length() - ".json".length())))));
    }

    /** The item with the icon's name draws the icon in the GUI and its own model everywhere else. */
    static void wrap(ModelEvent.ModifyBakingResult event) {
        var models = event.getModels();
        var icons = models.keySet().stream().filter(key -> key.variant().equals(ModelResourceLocation.STANDALONE_VARIANT)
                && key.id().getNamespace().equals(MCYukkuri.MOD_ID) && key.id().getPath().startsWith(FOLDER)).toList();
        for (var icon : icons) {
            var item = ModelResourceLocation.inventory(MCYukkuri.id(icon.id().getPath().substring(FOLDER.length())));
            models.put(item, new GuiIcon(models.get(item), models.get(icon)));
        }
    }

    private static final class GuiIcon extends BakedModelWrapper<BakedModel> {
        private final BakedModel icon;
        GuiIcon(BakedModel world, BakedModel icon) { super(world); this.icon = icon; }
        /** The slot lights the sprite flat like any generated item, whatever light the prop model asks for. */
        @Override public boolean usesBlockLight() { return icon.usesBlockLight(); }
        @Override public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
            return context == ItemDisplayContext.GUI ? icon.applyTransform(context, pose, leftHand) : super.applyTransform(context, pose, leftHand);
        }
    }

    private ItemIcons() {}
}
