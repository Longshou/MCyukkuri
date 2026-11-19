package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile.BreedingPool;
import java.util.Random;

/** Litter size: land 1 + four draws of p; aquatic (a boat seats two young, 项目设计) 1 + a draw of p + a draw of p/4. */
public final class Litter {
    public static int size(double p,BreedingPool pool,Random random){
        int count=1;
        if(pool==BreedingPool.AQUATIC)return count+(random.nextDouble()<p?1:0)+(random.nextDouble()<p/4?1:0);
        for(int i=1;i<pool.maxYoung;i++)if(random.nextDouble()<p)count++;
        return count;
    }
}
