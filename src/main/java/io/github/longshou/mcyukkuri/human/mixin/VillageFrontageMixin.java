package io.github.longshou.mcyukkuri.human.mixin;

import com.mojang.datafixers.util.Either;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SinglePoolElement.class)
abstract class VillageFrontageMixin {
    @Unique private static final Set<String> MCYUKKURI_VILLAGE_STYLES = Set.of("plains", "desert", "savanna", "snowy", "taiga");
    @Shadow @Final protected Either<ResourceLocation, StructureTemplate> template;

    @Inject(method = "getShuffledJigsawBlocks", at = @At("RETURN"))
    private void mcyukkuri$reserveShopFrontage(StructureTemplateManager manager, BlockPos pos, Rotation rotation,
            RandomSource random, CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> callback) {
        ResourceLocation id = template.left().orElse(null);
        if (id == null || !id.getNamespace().equals("minecraft") || !id.getPath().startsWith("village/")) return;
        int center = id.getPath().indexOf("/town_centers/", "village/".length());
        if (center < 0) return;
        String style = id.getPath().substring("village/".length(), center);
        if (!MCYUKKURI_VILLAGE_STYLES.contains(style)) return;

        String streets = "minecraft:village/" + style + "/streets";
        var connectors = callback.getReturnValue();
        for (int i = 0; i < connectors.size(); i++) {
            var connector = connectors.get(i);
            var tag = connector.nbt();
            if (tag == null || !tag.getString("pool").equals(streets) || !tag.getString("target").equals("minecraft:street")) continue;

            // Reserve one outward lot before the other streets use the free space. The pool falls back to the original streets.
            var frontage = tag.copy();
            frontage.putString("pool", "mcyukkuri:village/" + style + "/frontage");
            connectors.remove(i);
            connectors.addFirst(new StructureTemplate.StructureBlockInfo(connector.pos(), connector.state(), frontage));
            return;
        }
    }
}
