package io.github.longshou.mcyukkuri.p0;

import net.minecraft.nbt.CompoundTag;

/** Explicit deterministic temperament for scenarios originally authored with six preference values. */
final class PersonalityFixtures {
    static void preferences(CompoundTag tag,int[] values){
        tag.putInt("Version",2);tag.putIntArray("Innate",values);
        tag.putIntArray("DispositionInnate",new int[]{100-values[3],50,values[1],(values[4]+values[5])/2});
        tag.putIntArray("DispositionExperience",new int[4]);tag.putIntArray("DispositionProgress",new int[4]);
    }
    private PersonalityFixtures(){}
}
