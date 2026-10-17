package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import net.minecraft.network.chat.Component;

/** Stable action names are the save/protocol vocabulary; each entry has a server-side operation. */
public enum CareAction {
    OBSERVE(0), LIFT(0), GRIP(0), FLICK(0), PUNCH(0), SLAP(0), RUB(0), ROLL(0), LAY_DOWN(0), SHAKE_HOME(0),
    PIN(1,Tool.PIN), MALLET(1,Tool.MALLET), PIERCE(1,Tool.AWL), BURN(1,Tool.HEATED_AWL),
    FIX_SUPINE(1,Tool.RESTRAINT), WRAP_HANG(1,Tool.SLING), HOOK_HANG(1,Tool.HOOK),
    TRIM_HAIR(1,Tool.CLIPPERS), CUT_LEFT(1,Tool.CUTTER), CUT_RIGHT(1,Tool.CUTTER),
    GENTLE(2), COLD(2), FAVOR(2), DISLIKE(2), SHOW_RUMP(2), LIE_COMMAND(2),
    TAKE_ACCESSORY(3), CONFISCATE(3), HANG_ACCESSORY(3), COLLECT_NEAREST(3), RELEASE(3), CUT_ACCESSORY(1,Tool.CUTTER),
    VIBRATE(4,Tool.VIBRATOR), STERILIZE(4,Tool.STERILIZER), ENCLOSE(4,Tool.BOX), CUT_STEM(4,Tool.STEM_SHEARS), BOTTLE_STEM(4,Tool.BOTTLE_SHEARS),
    SING(2), EXERCISE(2), ADOPT(3), ABANDON(3), DECLINE_EVENT(3), HAT_CARRY_CHILD(3), HAT_PUT_DOWN_CHILD(3),
    /** Appended: the menu buttons are addressed by ordinal. "Ask how it is" (docs/PET_SHOP.md section 12). */
    ASK(2), REMOVE_HARNESS(3), UNWRAP(3);

    public final int group;
    public final Tool tool;
    CareAction(int group){this(group,null);}
    CareAction(int group,Tool tool){this.group=group;this.tool=tool;}
    public String key(){return name().toLowerCase(java.util.Locale.ROOT);}
    public Component title(){return Component.translatable("operation.mcyukkuri."+key());}
}
