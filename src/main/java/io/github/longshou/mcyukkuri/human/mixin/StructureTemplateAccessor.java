package io.github.longshou.mcyukkuri.human.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
    @Accessor("entityInfoList")
    List<StructureTemplate.StructureEntityInfo> mcyukkuri$entities();
}
