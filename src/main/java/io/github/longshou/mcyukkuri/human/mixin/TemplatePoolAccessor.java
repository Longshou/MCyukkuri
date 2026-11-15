package io.github.longshou.mcyukkuri.human.mixin;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructureTemplatePool.class)
public interface TemplatePoolAccessor {
    @Accessor("rawTemplates") List<Pair<StructurePoolElement, Integer>> mcyukkuri$rawTemplates();
    @Accessor("rawTemplates") @Mutable void mcyukkuri$setRawTemplates(List<Pair<StructurePoolElement, Integer>> entries);
    @Accessor("templates") ObjectArrayList<StructurePoolElement> mcyukkuri$templates();
    @Accessor("maxSize") void mcyukkuri$setMaxSize(int size);
}
