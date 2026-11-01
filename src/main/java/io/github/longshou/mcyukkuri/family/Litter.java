package io.github.longshou.mcyukkuri.family;

import java.util.Random;

/** Litter size: land 1 + four draws of p; aquatic (a boat seats two young, 项目设计) 1 + a draw of p + a draw of p/4. */
public final class Litter {
    public static int size(double p,boolean aquatic,Random random){
        int count=1;
        if(aquatic)return count+(random.nextDouble()<p?1:0)+(random.nextDouble()<p/4?1:0);
        for(int i=0;i<4;i++)if(random.nextDouble()<p)count++;
        return count;
    }
}
