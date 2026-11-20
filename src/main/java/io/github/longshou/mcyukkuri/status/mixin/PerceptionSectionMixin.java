package io.github.longshou.mcyukkuri.status.mixin;

import io.github.longshou.mcyukkuri.status.PerceptionScan;
import io.github.longshou.mcyukkuri.status.ThreatCandidates;
import java.util.Collection;
import java.util.List;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntitySection.class)
abstract class PerceptionSectionMixin<T extends EntityAccess> {
    @Unique private PerceptionScan.Section<T> mcyukkuri$threats;

    @Redirect(method = "add", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;add(Ljava/lang/Object;)Z"))
    private boolean mcyukkuri$enter(ClassInstanceMultiMap<T> storage, Object entity) {
        @SuppressWarnings("unchecked") T member = (T) entity;
        boolean added = storage.add(member);
        if (ThreatCandidates.includes(member)) {
            if (mcyukkuri$threats == null) mcyukkuri$threats = new PerceptionScan.Section<>();
            mcyukkuri$threats.enter(member);
        }
        return added;
    }

    @Redirect(method = "remove", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;remove(Ljava/lang/Object;)Z"))
    private boolean mcyukkuri$leave(ClassInstanceMultiMap<T> storage, Object entity) {
        boolean removed = storage.remove(entity);
        if (removed && ThreatCandidates.includes(entity)) {
            @SuppressWarnings("unchecked") T member = (T) entity;
            mcyukkuri$threats.leave(member);
        }
        return removed;
    }

    @Redirect(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;find(Ljava/lang/Class;)Ljava/util/Collection;"))
    private Collection<?> mcyukkuri$candidates(ClassInstanceMultiMap<T> storage, Class<?> entityClass,
                                              EntityTypeTest<?, ?> type, AABB bounds, AbortableIterationConsumer<?> consumer) {
        if (type != ThreatCandidates.TYPE) return storage.find(entityClass);
        return mcyukkuri$threats == null ? List.of() : mcyukkuri$threats.candidates();
    }
}
