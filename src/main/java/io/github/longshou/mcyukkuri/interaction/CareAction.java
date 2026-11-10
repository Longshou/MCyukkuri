package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Stable action names are the save/protocol vocabulary; each entry has a server-side operation. */
public enum CareAction {
    LIFT(1,0), GRIP(2,0), FLICK(3,0), PUNCH(4,0), SLAP(5,0), RUB(6,0), ROLL(7,0), LAY_DOWN(8,0), SHAKE_HOME(9,0),
    PIN(10,Tool.PIN), MALLET(11,Tool.MALLET), PIERCE(12,Tool.AWL), BURN(13,Tool.HEATED_AWL),
    FIX_SUPINE(14,Tool.RESTRAINT), WRAP_HANG(15,Tool.SLING), HOOK_HANG(16,Tool.HOOK),
    TRIM_HAIR(17,Tool.CLIPPERS), CUT_LEFT(18,Tool.CUTTER), CUT_RIGHT(19,Tool.CUTTER),
    COLD(21,1), FAVOR(22,1), DISLIKE(23,1), SHOW_RUMP(24,1), LIE_COMMAND(25,1),
    TAKE_ACCESSORY(26,2), CONFISCATE(27,2), COLLECT_NEAREST(29,2), RELEASE(30,0), CUT_ACCESSORY(31,Tool.CUTTER),
    VIBRATE(32,Tool.VIBRATOR), STERILIZE(33,Tool.STERILIZER), ENCLOSE(34,Tool.BOX), CUT_STEM(35,Tool.STEM_SHEARS), BOTTLE_STEM(36,Tool.BOTTLE_SHEARS),
    SING(37,1), EXERCISE(38,1), ADOPT(39,2), ABANDON(40,2), DECLINE_EVENT(41,2),
    /** Stable wire IDs are independent of enum declaration order. "Ask how it is" (docs/FEATURES.md). */
    ASK(44,1), REMOVE_HARNESS(45,2), UNWRAP(46,2);

    public static final int GROUP_COUNT = 3;
    private final int id;
    public final int group;
    public final Tool tool;
    CareAction(int id,int group){this(id,group,null);}
    CareAction(int id,Tool tool){this(id,-1,tool);}
    CareAction(int id,int group,Tool tool){this.id=id;this.group=group;this.tool=tool;}
    public int id(){return id;}
    public static CareAction byId(int id){
        for(var action:values())if(action.id==id)return action;
        return null;
    }
    public String key(){return name().toLowerCase(Locale.ROOT);}
    public static CareAction forTool(Tool tool){
        for(var action:values())if(action.tool==tool)return action;
        throw new IllegalArgumentException(tool.name());
    }
    int hairParts(){return switch(this){
        case TRIM_HAIR -> 7;
        case CUT_LEFT -> 1;
        case CUT_RIGHT -> 2;
        default -> throw new IllegalStateException(name());
    };}
    public Component title(){return Component.translatable("operation.mcyukkuri."+key());}
    public Component help(){return Component.translatable("operation.mcyukkuri.help."+key());}
}
