package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Stable action names are the save/protocol vocabulary; each entry has a server-side operation. */
public enum CareAction {
    OBSERVE(0,0), LIFT(1,0), GRIP(2,0), FLICK(3,0), PUNCH(4,0), SLAP(5,0), RUB(6,0), ROLL(7,0), LAY_DOWN(8,0), SHAKE_HOME(9,0),
    PIN(10,1,Tool.PIN), MALLET(11,1,Tool.MALLET), PIERCE(12,1,Tool.AWL), BURN(13,1,Tool.HEATED_AWL),
    FIX_SUPINE(14,1,Tool.RESTRAINT), WRAP_HANG(15,1,Tool.SLING), HOOK_HANG(16,1,Tool.HOOK),
    TRIM_HAIR(17,1,Tool.CLIPPERS), CUT_LEFT(18,1,Tool.CUTTER), CUT_RIGHT(19,1,Tool.CUTTER),
    GENTLE(20,2), COLD(21,2), FAVOR(22,2), DISLIKE(23,2), SHOW_RUMP(24,2), LIE_COMMAND(25,2),
    TAKE_ACCESSORY(26,3), CONFISCATE(27,3), HANG_ACCESSORY(28,3), COLLECT_NEAREST(29,3), RELEASE(30,3), CUT_ACCESSORY(31,1,Tool.CUTTER),
    VIBRATE(32,4,Tool.VIBRATOR), STERILIZE(33,4,Tool.STERILIZER), ENCLOSE(34,4,Tool.BOX), CUT_STEM(35,4,Tool.STEM_SHEARS), BOTTLE_STEM(36,4,Tool.BOTTLE_SHEARS),
    SING(37,2), EXERCISE(38,2), ADOPT(39,3), ABANDON(40,3), DECLINE_EVENT(41,3), HAT_CARRY_CHILD(42,3), HAT_PUT_DOWN_CHILD(43,3),
    /** Stable wire IDs are independent of enum declaration order. "Ask how it is" (docs/PET_SHOP.md section 12). */
    ASK(44,2), REMOVE_HARNESS(45,3), UNWRAP(46,3);

    public static final int GROUP_COUNT = 5;
    private final int id;
    public final int group;
    public final Tool tool;
    CareAction(int id,int group){this(id,group,null);}
    CareAction(int id,int group,Tool tool){this.id=id;this.group=group;this.tool=tool;}
    public int id(){return id;}
    public static CareAction byId(int id){
        for(var action:values())if(action.id==id)return action;
        return null;
    }
    public String key(){return name().toLowerCase(Locale.ROOT);}
    public Component title(){return Component.translatable("operation.mcyukkuri."+key());}
}
