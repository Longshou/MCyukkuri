package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

/** All ground goals, including tempting and random strolling, respect the aquatic shoreline. */
public final class AquaticNavigation extends GroundPathNavigation {
    private final YukkuriEntity entity;
    public AquaticNavigation(YukkuriEntity entity,Level level){super(entity,level);this.entity=entity;}
    @Override public boolean moveTo(Path path,double speed){
        if(!entity.aquaticLife().shore().allows(path)){stop();return false;}
        return super.moveTo(path,speed);
    }
}
