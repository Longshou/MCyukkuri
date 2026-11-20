package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile.BreedingPool;
import java.util.Random;

/** Predator litters draw once, ordinary land litters four times, and aquatic litters twice at p and p/4. */
public final class Litter {
    public static int size(double p,BreedingPool pool,Random random){
        int count=1;
        if(pool==BreedingPool.AQUATIC)return count+(random.nextDouble()<p?1:0)+(random.nextDouble()<p/4?1:0);
        int draws=pool==BreedingPool.PREDATOR ? pool.maxYoung-1 : 4;
        for(int i=0;i<draws;i++)if(random.nextDouble()<p)count++;
        return count;
    }
}
