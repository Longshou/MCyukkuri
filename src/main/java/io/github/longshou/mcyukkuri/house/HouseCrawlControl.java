package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Navigation retains its target and collision handling while the contact gait pauses. */
public final class HouseCrawlControl extends MoveControl {
    private final YukkuriEntity entity;

    public HouseCrawlControl(YukkuriEntity entity) {
        super(entity);
        this.entity = entity;
    }

    @Override public void tick() {
        boolean wanted = hasWanted();
        super.tick();
        boolean crawl = wanted && operation != Operation.JUMPING && entity.onGround()
                && entity.canCrawlNow()
                && (!entity.childhood().canJump() || entity.body().footBurned() || entity.house().wantsCrawl() || entity.life().wantsCrawl());
        entity.syncCrawl(crawl);
        if (crawl) entity.yBodyRot = entity.getYRot();
    }
}
