package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;

/** Rate-limited server log of aquatic movement state changes and refusals ("[aqdiag]"), read by the headless harness. */
public final class AquaDiag {
    /** Off by default (it is a debugging aid for the harness): {@code /yukkuri aqdiag on}. */
    private static volatile boolean ENABLED=Boolean.getBoolean("mcyukkuri.aqdiag");
    private static final Map<String,Long> LAST=new HashMap<>();
    public static boolean enabled(){return ENABLED;}
    public static void enabled(boolean on){ENABLED=on;}
    /** {@code /yukkuri aqdiag on|off}: server log lines tagged [aqdiag] for aquatic landing, voyage return, rescue and self rescue. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command(){
        return net.minecraft.commands.Commands.literal("aqdiag").requires(source->source.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("on").executes(context->{enabled(true);context.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal("aqdiag logging on"),false);return 1;}))
                .then(net.minecraft.commands.Commands.literal("off").executes(context->{enabled(false);context.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal("aqdiag logging off"),false);return 1;}));
    }
    /** Log {@code message} for {@code key} at most once per {@code minTicks} game ticks (0: every call). */
    public static void log(Entity at,String key,int minTicks,String message){
        if(!ENABLED||at==null||at.level().isClientSide)return;
        long now=at.level().getGameTime();
        if(minTicks>0){
            Long before=LAST.get(key);
            if(before!=null&&now-before<minTicks)return;
            LAST.put(key,now);
            if(LAST.size()>2048)LAST.clear();
        }
        MCYukkuri.LOGGER.info("[aqdiag] t={} {}: {}",now,key,message);
    }
    private static String fmt(double v){return String.format(java.util.Locale.ROOT,"%.2f",v);}
    public static String at(net.minecraft.world.phys.Vec3 p){return p==null?"null":"("+fmt(p.x)+","+fmt(p.y)+","+fmt(p.z)+")";}
    private AquaDiag(){}
}
