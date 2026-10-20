package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetLife;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** What an exam item may use. Implemented by the running session; everything registered here is cleaned up when the item ends. */
public interface ExamContext {
    ServerLevel level();
    HumanNpc examiner();
    YukkuriEntity candidate();
    /** The owner, or null while briefly offline (the session interrupts if the owner stays away). */
    Player owner();
    ExamHall hall();
    /** The badge level currently being assessed; it chooses task complexity, never a success probability. */
    BadgeGrade tier();
    Random random();
    /** Ticks since the current item started. */
    int itemTicks();
    /** Examiner line: a speech card above the examiner (~60 ticks) and the owner's action bar. */
    void examinerSay(String langKey, Object... args);
    /** Staging cards that are removed automatically when the item ends; scale 1 is the default size. */
    UUID card(Vec3 pos, Component text, int ticks, float scale);
    UUID itemCard(Vec3 pos, ItemStack stack, int ticks, float scale);
    default UUID card(Vec3 pos, Component text, int ticks) { return card(pos, text, ticks, 1f); }
    default UUID itemCard(Vec3 pos, ItemStack stack, int ticks) { return itemCard(pos, stack, ticks, .5f); }
    void examinerWalkTo(BlockPos pos);
    boolean examinerAt(BlockPos pos);
    /** Aims the examiner once; the session never re-aims the examiner on its own while an item runs. */
    void examinerLookAt(Entity e);
    void examinerSwing();
    /** Shop trainees that sit the exam alongside the candidate (may be empty). */
    List<YukkuriEntity> trainees();
    /** The wild-role actor behind the gold booth glass, or null. */
    YukkuriEntity actor();
    /** Drive a trainee/actor/candidate for this item; the session locks them and removes the director when the item ends. */
    void direct(YukkuriEntity y, PetLife.Director d);
    /** Short lang key appended to the exam log shown with the result. */
    void note(String langKey);
}
