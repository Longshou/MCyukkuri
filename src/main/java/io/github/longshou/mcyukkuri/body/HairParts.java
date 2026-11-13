package io.github.longshou.mcyukkuri.body;

/** The parts hidden by a left, right or full haircut. */
public final class HairParts {
    public static int lossMask(String name,float x,boolean authoredHair){
        if(!authoredHair&&!name.contains("hair")&&!name.contains("bang")&&!name.contains("braid")&&!name.startsWith("wasa_"))return 0;
        if(name.contains("bang")||name.contains("crown")||name.contains("wrap")||name.startsWith("wasa_scalp"))return 4;
        return 4|(x<0?1:2);
    }

    private HairParts(){}
}
