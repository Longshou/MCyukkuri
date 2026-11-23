package io.github.longshou.mcyukkuri.perception;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.longshou.mcyukkuri.pet.PetRequests;
import net.minecraft.core.BlockPos;

/**
 * What one body believes about the contents of the waterers and bowls it knows of: the last time it looked. The world keeps the
 * real contents; this is only a bounded cache of looks, not saved (a reloaded body has to look again).
 */
public final class FixtureBelief {
    private final YukkuriEntity body;
    private final Map<BlockPos, Belief.Entry> looks = new LinkedHashMap<>(Belief.CAPACITY, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<BlockPos, Belief.Entry> eldest) { return size() > Belief.CAPACITY; }
    };

    public FixtureBelief(YukkuriEntity body) { this.body = body; }

    /** What the fixture holds for this body: a waterer has water, a bowl has food it seeks. */
    public static boolean stocked(CareBlockEntity care, YukkuriEntity body) {
        return care.kind() == CareBlock.Kind.WATERER ? care.water() > 0 : care.hasFood(body.life()::seeksFood);
    }

    public static Cue.Need need(CareBlock.Kind kind) {
        return kind == CareBlock.Kind.WATERER ? Cue.Need.WATER : Cue.Need.FOOD;
    }

    /** The belief after a look: a body that sees the fixture now reads it, otherwise it goes by its last look. */
    public Belief.State state(CareBlockEntity care) {
        var pos = care.getBlockPos();
        var need = need(care.kind());
        long now = body.level().getGameTime();
        long decay = Belief.decay(Senses.urgency(body, need));
        if (Senses.sees(body, pos, need)) {
            boolean stocked = stocked(care, body);
            var before = Belief.state(looks.get(pos), now, decay);
            looks.put(pos.immutable(), new Belief.Entry(now, stocked));
            // Finding stocked what it did not know or thought empty, while it wants it: a beat about the supply itself.
            if (stocked && before != Belief.State.FULL && Senses.felt(body, need))
                body.pet().care().requests().found(need == Cue.Need.WATER ? PetRequests.Kind.WATER : PetRequests.Kind.FOOD);
        }
        return Belief.state(looks.get(pos), now, decay);
    }

    /** The body saw or heard the fixture being filled. */
    public void noticed(CareBlockEntity care) {
        looks.put(care.getBlockPos().immutable(), new Belief.Entry(body.level().getGameTime(), stocked(care, body)));
    }

    /** Whether the fixture counts as a place to get what the body needs, under the given reading of what it knows. */
    public boolean counts(CareBlockEntity care, Knowledge knowledge) {
        if (knowledge == Knowledge.ACTUAL) return stocked(care, body);
        var state = state(care);
        return knowledge == Knowledge.KNOWN_STOCKED ? state == Belief.State.FULL : state != Belief.State.EMPTY;
    }
}
