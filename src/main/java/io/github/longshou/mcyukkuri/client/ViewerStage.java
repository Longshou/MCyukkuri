package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.presentation.ExpressionClips;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraft.world.level.Level;
import org.lwjgl.opengl.GL11;

/**
 * The menu's client-only preview subject and its clock. The subject is built the way the network builds every client entity and
 * is never added to a level, so it has no AI, no ticking and no records; closing the menu drops it.
 */
final class ViewerStage {
    private record Form(YukkuriVariant variant, int flags) {}

    private static final Map<String, Form> FORMS = forms();

    private static YukkuriEntity subject;
    private static Level level;
    static String form;
    static String clip = "idle";
    /** Pinned face; null follows the clip's own authored span. */
    static String face;
    static float seconds, speed = 1;
    static boolean loop, playing;
    private static long lastNanos;

    /** Every visible form, the same enumeration the catalogue names: deibu last, one entry per asset. */
    private static Map<String, Form> forms() {
        var forms = new HashMap<String, Form>();
        for (int deibu = 0; deibu < 2; deibu++)
            for (var variant : YukkuriVariant.values())
                for (var kind : SubspeciesLife.Kind.values()) {
                    if (kind.aquatic() || !kind.fits(variant)) continue;
                    for (var congenital : SubspeciesLife.Congenital.values()) {
                        if (congenital != SubspeciesLife.Congenital.NORMAL && kind != SubspeciesLife.Kind.NORMAL) continue;
                        int flags = kind.ordinal() | congenital.ordinal() << 4 | (deibu != 0 ? SubspeciesLife.DEIBU_FLAG : 0);
                        forms.putIfAbsent(SubspeciesLife.assetId(variant, flags), new Form(variant, flags));
                    }
                }
        return forms;
    }

    /** Starts the frame clock and picks the first form on the first open. */
    static void open(String first) {
        if (form == null) setForm(first);
        lastNanos = Util.getNanos();
    }

    /** Drops the subject; the next open builds a fresh one. */
    static void close() {
        subject = null;
        level = null;
        playing = false;
    }

    static void setForm(String asset) {
        form = asset;
        subject = null;
        seconds = 0;
        playing = false;
        if (!YukkuriModel.get(asset).clips().containsKey(clip)) clip = "idle";
        if (face != null && !FaceLibrary.selectable(asset).contains(face)) face = null;
    }

    static void setClip(String id) {
        clip = id;
        seconds = 0;
        playing = false;
    }

    static void setFace(String id) { face = id; }

    static void playOnce() { seconds = 0; loop = false; playing = true; }

    static void playLoop() { loop = true; playing = true; }

    static void togglePlay() {
        if (!playing && seconds >= duration()) seconds = 0;
        playing = !playing;
    }

    static void cycleSpeed() { speed = speed == 1 ? .5f : speed == .5f ? .25f : 1; }

    static void scrub(float at) { seconds = Mth.clamp(at, 0, duration()); playing = false; }

    static float duration() { return YukkuriModel.get(form).clips().get(clip).duration; }

    static String speedLabel() { return speed == 1 ? "1×" : speed == .5f ? "½×" : "¼×"; }

    /** Advances the clip clock by the frame delta at the chosen speed. */
    static void advance() {
        long now = Util.getNanos();
        float delta = (now - lastNanos) / 1.0e9f * speed;
        lastNanos = now;
        if (!playing) return;
        float duration = duration();
        seconds += delta;
        if (loop) seconds = seconds % duration;
        else if (seconds >= duration) {
            seconds = duration;
            playing = false;
        }
    }

    /** Pins this frame's clip time and face on the subject, then draws it into the box. */
    static void draw(GuiGraphics graphics, int x, int y, int w, int h, float yaw, float pitch, float zoom) {
        var entry = FORMS.get(form);
        var entity = subject();
        float ticks = seconds * 20;
        float partial = ticks - Mth.floor(ticks);
        String shown = face != null ? face : ExpressionClips.authored(form, clip, seconds);
        entity.preview(entry.flags(), clip, entity.level().getGameTime() - Mth.floor(ticks), shown);
        CareInspection.drawModel(graphics, entity, x, y, w, h, yaw, pitch, zoom, partial);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private static YukkuriEntity subject() {
        var mc = Minecraft.getInstance();
        if (subject == null || level != mc.level) {
            subject = ModEntities.forVariant(FORMS.get(form).variant()).create(mc.level);
            level = mc.level;
        }
        return subject;
    }

    private ViewerStage() {}
}
