package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Only the current question can use the examination presentation and observe the candidate. */
public interface ExamContext {
    ServerLevel level();
    HumanNpc examiner();
    YukkuriEntity candidate();
    Player owner();
    int itemTicks();
    void examinerSay(String key, Object... args);
    UUID card(Vec3 pos, Component text, int ticks, float scale);
    void examinerWalkTo(BlockPos pos);
    void examinerLookAt(Entity entity);
    void examinerSwing();
    boolean inContact();
    void allowRoutine();
    void note(String key);
    void interrupt(String reason);
    void answer(String task, boolean success, String observed);
}
