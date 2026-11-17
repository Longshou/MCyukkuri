package io.github.longshou.mcyukkuri.human.mixin;

import io.github.longshou.mcyukkuri.human.VillagePlant;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(JigsawStructure.class)
abstract class VillagePlantMixin {
    @Unique private static final Set<String> MCYUKKURI_VILLAGE_POOLS = Set.of(
            "village/plains/town_centers", "village/desert/town_centers", "village/savanna/town_centers",
            "village/snowy/town_centers", "village/taiga/town_centers");
    @Shadow @Final private Holder<StructureTemplatePool> startPool;
    @Shadow @Final private int maxDistanceFromCenter;

    @Inject(method = "findGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void mcyukkuri$appendPlant(Structure.GenerationContext context,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> callback) {
        if (!startPool.unwrapKey().map(key -> key.location()).filter(id ->
                id.getNamespace().equals("minecraft") && MCYUKKURI_VILLAGE_POOLS.contains(id.getPath())).isPresent()) return;
        callback.setReturnValue(callback.getReturnValue().map(stub ->
                new Structure.GenerationStub(stub.position(), pieces -> {
                    stub.generator().left().orElseThrow().accept(pieces);
                    VillagePlant.append(context, stub.position(), maxDistanceFromCenter, pieces);
                })));
    }
}
