package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.dialogue.SpeechAddress;
import io.github.longshou.mcyukkuri.dialogue.SpeechCount;
import io.github.longshou.mcyukkuri.entity.SpeciesTraits;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.settlement.Rumours;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Per-individual side of the species profile (worldview package 4, komicolle 40720 floor 11518905). The coefficients live in
 * {@link SpeciesTraits}; the shared AI reads them through {@link #traits()}. This class owns what has no other home:
 * the move-speed modifier, frail Patchouli's retching and wrong alarms, and the speech hooks that were catalogued but never
 * triggered (forms of address, counting "1, 2, 3, lots", self-narration of stealing, fleeing and hiding).
 * Everything runs on the server clock; nothing here depends on the client frame rate. Pressure goes through
 * {@code mind().add}; the counters are registered in the status hub (group {@code species}).
 */
public final class SpeciesLife {
    private static final net.minecraft.resources.ResourceLocation SPEED_ID = MCYukkuri.id("species_speed");
    private static final int VOMIT_COOLDOWN = 3600, FAST_MEAL_TICKS = 600, NARRATE_COOLDOWN = 400;
    private static final float NARRATE_CHANCE = .3f;
    private final YukkuriEntity entity;
    private SpeciesTraits traits;
    private int vomits, chores, falseAlarms, addressed, counted, narrated;
    private float fatigueDebt;
    private long lastMeal = Long.MIN_VALUE / 2, vomitReady, lastNarrate = Long.MIN_VALUE / 2;
    private final Map<UUID, Long> addressedAt = new HashMap<>();
    private boolean wasPanicking;
    private Reactions.Threat.Stage lastStage = Reactions.Threat.Stage.NONE;

    public SpeciesLife(YukkuriEntity entity) {
        this.entity = entity;
    }

    /** Looked up on first use: the variant is assigned in the entity constructor, after this component is created. */
    public SpeciesTraits traits() {
        if (traits == null) traits = SpeciesTraits.of(entity.getVariant());
        return traits;
    }
    public int cheekCapacity() { return traits().cheek(); }
    public int vomits() { return vomits; }
    public int chores() { return chores; }
    public int falseAlarms() { return falseAlarms; }
    /** Portions currently held in the cheeks/mouth for the household chores or a foraging trip. */
    public int cheekLoad() { return Math.max(entity.habitat().carrying().getCount(), entity.forageTrip() == null ? 0 : entity.forageTrip().load().getCount()); }
    public String speechHooks() { return "address=" + addressed + ",count=" + counted + ",narrate=" + narrated; }
    public void chore() { chores++; }

    /** Energy lost by one metabolic step: frail species tire a quarter faster (a lower effective stamina ceiling). */
    public int drain() {
        fatigueDebt += traits().fatigue() - 1;
        if (fatigueDebt >= 1) { fatigueDebt -= 1; return 2; }
        return 1;
    }

    // ---- hooks called by the shared life code ---------------------------------------------------------------

    /** A meal was swallowed. Gulping one meal after another can bring the filling back up in a species that retches easily. */
    public void ate(float nutritionBefore) {
        long now = entity.level().getGameTime();
        boolean fast = now - lastMeal < FAST_MEAL_TICKS || nutritionBefore < 15;
        lastMeal = now;
        if (fast) vomit(.4f, "gulped");
    }

    /** One of the narrations the lecture describes ("they say aloud what they are doing"); about 30% of the time. */
    public void narrate(String kind) {
        long now = entity.level().getGameTime();
        if (entity.level().isClientSide || now - lastNarrate < NARRATE_COOLDOWN || entity.getRandom().nextFloat() >= NARRATE_CHANCE) return;
        if (entity.speak("narrate_" + kind)) { lastNarrate = now; narrated++; }
    }

    /** Saying how many portions are stored or how many children there are: "1, 2, 3, lots". */
    public boolean count(String base, int quantity) {
        boolean spoken = entity.speakCount(base, quantity);
        if (spoken) counted++;
        return spoken;
    }

    /** Retch up filling when frightened, gulping or strained. Keeps three portions so it cannot kill by itself. */
    public boolean vomit(float chance, String cause) {
        long now = entity.level().getGameTime();
        if (traits().vomit() <= 0 || entity.level().isClientSide || now < vomitReady || !entity.isAlive()
                || entity.getRandom().nextFloat() >= chance * traits().vomit() || entity.body().filling() <= 3) return false;
        if (!entity.body().releaseFilling("vomited")) return false;
        vomitReady = now + VOMIT_COOLDOWN; vomits++;
        entity.mind().add(3, "vomit_" + cause);
        entity.showReaction("teary", "bitter", 80);
        return true;
    }

    // ---- the clock -------------------------------------------------------------------------------------------

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive() || entity.isNoAi()) return;
        int t = entity.tickCount;
        if (t == 1 || t % 400 == 3) speed();
        boolean panic = entity.isPanicking();
        if (panic && !wasPanicking) { vomit(.25f, "fright"); narrate("flee"); }
        wasPanicking = panic;
        var stage = entity.status().reactions().threat().stage();
        if (stage != lastStage) {
            if (stage == Reactions.Threat.Stage.HIDE) narrate("hide");
            else if (stage == Reactions.Threat.Stage.RETREAT) narrate("flee");
            else if (stage == Reactions.Threat.Stage.WATCH && lastStage == Reactions.Threat.Stage.NONE) greetPredator();
            lastStage = stage;
        }
        if (t % 400 == 211 && entity.mind().unease() >= 60) vomit(.2f, "strain");
        if (entity.life().isSleeping() || panic) return;
        if (t % 40 == 13) greetHuman();
        if (t % 100 == 37) greetKin();
        if (t % 200 == 91) talkCounts();
        if (t % 600 == 301) falseAlarm();
    }

    private void speed() {
        var attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null || traits().speed() == 1 || attribute.hasModifier(SPEED_ID)) return;
        attribute.addTransientModifier(new AttributeModifier(SPEED_ID, traits().speed() - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    private boolean fresh(UUID id) {
        long now = entity.level().getGameTime();
        Long last = addressedAt.get(id);
        if (last != null && now - last < 2400) return false;
        if (addressedAt.size() > 24) addressedAt.values().removeIf(at -> now - at >= 2400);
        return true;
    }
    private void addressed(UUID id) { addressedAt.put(id, entity.level().getGameTime()); addressed++; }

    private void greetHuman() {
        if (entity.getRandom().nextFloat() >= .35f || entity.pet().busy()) return;
        Entity best = null; double bestD = 36;
        for (var p : entity.level().players()) {
            if (p.isSpectator() || !p.isAlive() || entity.distanceToSqr(p) >= bestD || !entity.hasLineOfSight(p)) continue;
            best = p; bestD = entity.distanceToSqr(p);
        }
        for (var npc : entity.level().getEntitiesOfClass(HumanNpc.class, entity.getBoundingBox().inflate(6), n -> n.isAlive() && entity.hasLineOfSight(n))) {
            if (entity.distanceToSqr(npc) < bestD) { best = npc; bestD = entity.distanceToSqr(npc); }
        }
        if (best == null || !fresh(best.getUUID())) return;
        var personality = entity.life().personality();
        SpeechAddress.HumanRelation relation;
        if (best instanceof Player player) {
            relation = HumanCommerce.ownedBy(entity, player.getUUID()) ? SpeechAddress.HumanRelation.OWNER
                    : personality.fear(player.getUUID()) >= 25 || personality.trust(player.getUUID()) < -5 ? SpeechAddress.HumanRelation.HOSTILE
                    : personality.trust(player.getUUID()) >= 10 ? SpeechAddress.HumanRelation.FRIEND : SpeechAddress.HumanRelation.STRANGER;
        } else {
            var npc = (HumanNpc) best;
            relation = HumanCommerce.ownedBy(entity, npc.getUUID()) ? SpeechAddress.HumanRelation.OWNER
                    : npc.attitude() == HumanNpc.Attitude.CRUEL ? SpeechAddress.HumanRelation.HOSTILE
                    : npc.attitude() == HumanNpc.Attitude.CARING ? SpeechAddress.HumanRelation.FRIEND : SpeechAddress.HumanRelation.STRANGER;
        }
        // The game keeps no sex or age for humans: derive both from the identity so a person is always called the same thing.
        int h = best.getUUID().hashCode();
        if (entity.speak(SpeechAddress.human(relation, (h & 1) == 0, ((h >> 1) & 3) == 0))) addressed(best.getUUID());
    }

    private void greetKin() {
        if (entity.getRandom().nextFloat() >= .3f) return;
        var family = entity.family();
        var group = entity.settlement().group();
        for (var other : entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(6),
                o -> o != entity && o.isAlive() && entity.hasLineOfSight(o))) {
            var id = other.getUUID();
            SpeechAddress.Kin kin = null;
            if (id.equals(family.mother())) kin = SpeechAddress.Kin.MOTHER;
            else if (id.equals(family.father())) kin = SpeechAddress.Kin.FATHER;
            else if (id.equals(family.partner()) || entity.getUUID().equals(other.family().partner())) kin = SpeechAddress.Kin.PARTNER;
            else if (entity.getUUID().equals(other.family().mother()) || entity.getUUID().equals(other.family().father())) kin = SpeechAddress.Kin.CHILD;
            else if (group != null && id.equals(group.leader)) kin = SpeechAddress.Kin.LEADER;
            else if (family.mother() != null && family.mother().equals(other.family().mother())
                    || family.father() != null && family.father().equals(other.family().father()))
                kin = other.growthScale() >= entity.growthScale() ? SpeechAddress.Kin.ELDER_SIBLING : SpeechAddress.Kin.YOUNGER_SIBLING;
            if (kin == null || !fresh(id)) continue;
            if (entity.speak(SpeechAddress.kin(kin))) { addressed(id); return; }
        }
    }

    private void greetPredator() {
        var source = entity.status().reactions().threat().source();
        if (!(source instanceof YukkuriEntity predator)) return;
        String event = SpeechAddress.predator(predator.getVariant().dialogueSpecies());
        if (event != null && fresh(predator.getUUID()) && entity.speak(event)) addressed(predator.getUUID());
    }

    private void talkCounts() {
        if (entity.getRandom().nextFloat() >= .2f) return;
        var level = (ServerLevel) entity.level();
        int pick = entity.getRandom().nextInt(3);
        if (pick == 0 && entity.family().adult()) {
            int children = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10), c -> entity.family().caresFor(c)).size();
            count(SpeechCount.CHILDREN, children);
        } else if (pick == 1) {
            int peers = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(8), o -> o != entity && o.isAlive()).size();
            if (peers >= 2) count(SpeechCount.PEERS, peers);
        } else if (entity.life().needsFood()) {
            int food = level.getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(6),
                    i -> i.getItem().is(YukkuriEntity.FOOD)).stream().mapToInt(i -> i.getItem().getCount()).sum();
            count(SpeechCount.FOOD, food);
        }
    }

    /** Patchouli "passes on wrong knowledge": now and then she raises an alarm for nothing; neighbours start and the group hears a rumour. */
    private void falseAlarm() {
        if (traits().falseAlarm() <= 0 || !entity.family().adult() || entity.getRandom().nextFloat() >= traits().falseAlarm()
                || entity.status().reactions().active() || !entity.speak("afraid")) return;
        falseAlarms++;
        var level = (ServerLevel) entity.level();
        for (var peer : level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(12), o -> o != entity && o.isAlive() && !o.isNoAi())) {
            peer.showReaction("surprise", "afraid", 60);
            peer.mind().add(2, "false_alarm");
        }
        var group = entity.settlement().group();
        if (group != null) Rumours.add(SettlementData.get(level), group, Rumours.WARY_HUMAN, null, 1, level.getGameTime());
    }

    // ---- persistence ---------------------------------------------------------------------------------------

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Vomits", vomits); tag.putInt("Chores", chores); tag.putInt("FalseAlarms", falseAlarms);
        tag.putInt("Addressed", addressed); tag.putInt("Counted", counted); tag.putInt("Narrated", narrated);
        return tag;
    }
    public void load(CompoundTag tag) {
        vomits = Math.max(0, tag.getInt("Vomits")); chores = Math.max(0, tag.getInt("Chores")); falseAlarms = Math.max(0, tag.getInt("FalseAlarms"));
        addressed = Math.max(0, tag.getInt("Addressed")); counted = Math.max(0, tag.getInt("Counted")); narrated = Math.max(0, tag.getInt("Narrated"));
    }
}
