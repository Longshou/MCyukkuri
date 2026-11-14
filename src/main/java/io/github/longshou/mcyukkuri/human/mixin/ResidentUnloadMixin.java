package io.github.longshou.mcyukkuri.human.mixin;

import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.VillageBin;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
abstract class ResidentUnloadMixin<T extends EntityAccess> {
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;

    // Vanilla writes the entity chunk before issuing EntityLeaveLevelEvent. Include the drop in that same write.
    @Inject(method = "processChunkUnload", at = @At("HEAD"))
    private void mcyukkuri$dropResidentLitter(long chunk, CallbackInfoReturnable<Boolean> callback) {
        var residents = sectionStorage.getExistingSectionsInChunk(chunk)
                .flatMap(section -> section.getEntities())
                .filter(HumanNpc.class::isInstance).map(HumanNpc.class::cast).toList();
        residents.forEach(VillageBin::dropLitter);
    }
}
