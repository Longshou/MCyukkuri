package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side API that puts the human world on the map where world generation has not (the playground, a fresh flat world): whole buildings with their
 * staff and stock, caretakers walking a pet, a cruel resident. Everything here uses the real templates and the real entities; there are no stand-ins.
 * {@code /yukkuri human ...} (command/HumanCommands) is a thin wrapper over these methods.
 *
 * Why the playground showed no shop, factory or Oniisan: the buildings are only placed by structure generation (random spread, 24 / 48 chunks, plains,
 * sunflower plains, meadow and savanna only, never into chunks that already exist), and the playground's flat map has no such chunks; scenario scripts only
 * summoned loose NPCs. Placing the template here is the same thing the generator does, at a chosen spot.
 */
public final class HumanScenes {
    private HumanScenes() {}

    public enum Kind {
        SHOP("yukkuri_shop", 12), LABOR("yukkuri_labor_factory", 20), MANJU("yukkuri_manju_factory", 20), BAG("yukkuri_bag_factory", 20);
        public final ResourceLocation template;
        final int entranceX;
        Kind(String id, int entranceX) { this.template = io.github.longshou.mcyukkuri.MCYukkuri.id(id); this.entranceX = entranceX; }
        public static Kind byName(String name) {
            for (var k : values()) if (k.name().equalsIgnoreCase(name)) return k;
            return null;
        }
    }
    /** What a placement created: the footprint, the staff and the individuals that came with the template (all bound to their posts). */
    public record Placed(Kind kind, BoundingBox box, List<HumanNpc> staff, List<YukkuriEntity> animals) {}

    private static Rotation rotationFor(Direction front) {
        return switch (front) { case EAST -> Rotation.CLOCKWISE_90; case SOUTH -> Rotation.CLOCKWISE_180; case WEST -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
    }

    /**
     * Places a complete building. {@code entrance} is where a visitor stands on the street in front of the main door (feet position; the block below is
     * the ground the building is levelled to), {@code front} the direction the facade faces. With {@code clear} the footprint is cleared of trees and
     * terrain above the ground, and holes under it are filled with dirt. Returns null when the template is missing.
     * The template's own people and animals are created with it and bound to their posts at once (shop stock to the shopkeeper through the shop register,
     * factory animals to the foreman of their line), instead of waiting for the proximity binding of the slow tick.
     */
    public static Placed place(ServerLevel level, Kind kind, BlockPos entrance, Direction front, boolean clear) {
        var template = level.getStructureManager().get(kind.template).orElse(null);
        if (template == null) return null;
        var settings = new StructurePlaceSettings().setRotation(rotationFor(front)).setIgnoreEntities(false);
        BlockPos rel = StructureTemplate.calculateRelativePosition(settings, new BlockPos(kind.entranceX, 0, 0));
        BlockPos origin = entrance.below().subtract(rel);
        BoundingBox box = template.getBoundingBox(settings, origin);
        if (clear) levelGround(level, box, origin.getY());
        var before = new HashSet<UUID>();
        var area = AABB.of(box).inflate(1);
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, area)) before.add(e.getUUID());
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 2);
        var staff = new ArrayList<HumanNpc>();
        var animals = new ArrayList<YukkuriEntity>();
        for (var n : level.getEntitiesOfClass(HumanNpc.class, area)) if (!before.contains(n.getUUID())) staff.add(n);
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, area)) if (!before.contains(y.getUUID())) animals.add(y);
        for (var y : animals) {
            String role = Custody.view(y).pendingStaffRole();
            if (role.isEmpty()) continue;
            staff.stream().filter(n -> n.siteRole().equals(role)).findFirst().ifPresent(n -> HumanCommerce.bindPending(y, n));
        }
        staff.stream().filter(n -> n.job() == HumanNpc.Job.SHOPKEEPER)
                .forEach(n -> io.github.longshou.mcyukkuri.shop.ShopRegistry.get(level).register(n));
        if (kind == Kind.SHOP) { // a silver-badge Alice beside the shopkeeper, behind the counter, on the shelves like the booth pets
            staff.stream().filter(n -> n.job() == HumanNpc.Job.SHOPKEEPER).findFirst().ifPresent(keeper -> {
                Vec3 local = StructureTemplate.transformedVec3d(settings, new Vec3(13.5, 3.0, 14.5));
                var alice = addStock(level, keeper, YukkuriVariant.ALICE, 2, 70, local.add(origin.getX(), origin.getY(), origin.getZ()));
                if (alice != null) animals.add(alice);
            });
        }
        return new Placed(kind, box, staff, animals);
    }

    private static void levelGround(ServerLevel level, BoundingBox box, int groundY) {
        var pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX() - 3; x <= box.maxX() + 3; x++)
            for (int z = box.minZ() - 3; z <= box.maxZ() + 3; z++) {
                for (int y = groundY + 1; y <= box.maxY(); y++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
                boolean inside = x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
                for (int y = groundY - 1; y >= groundY - 4; y--) {
                    pos.set(x, y, z);
                    var state = level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.REPLACEABLE))
                        level.setBlock(pos, inside ? Blocks.DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    else break;
                }
            }
    }

    /**
     * One more shelf individual for this shopkeeper: a real yukkuri of the variant, with the badge grade (0 none .. 3 gold) written the way the shop writes it
     * for its own stock, bound to the shopkeeper (custody Owner and Use = stock, badge issued by the shop, entered in the shop register).
     */
    public static YukkuriEntity addStock(ServerLevel level, HumanNpc keeper, YukkuriVariant variant, int grade, int pedigree, Vec3 at) {
        if (keeper.job() != HumanNpc.Job.SHOPKEEPER) return null;
        var y = ModEntities.forVariant(variant).create(level);
        if (y == null) return null;
        y.moveTo(at.x, at.y, at.z, 0, 0);
        var tag = new CompoundTag();
        Custody.pendingStaffRole(tag, keeper.siteRole().isEmpty() ? "shopkeeper" : keeper.siteRole());
        Custody.role(tag, Custody.Role.STOCK);
        tag.putInt("Grade", Math.clamp(grade, 0, 3)); tag.putInt("Pedigree", Math.clamp(pedigree, 0, 100)); tag.putInt("FactoryGroup", 0);
        if (grade >= 2) tag.putInt("Medal", grade - 1);
        HumanCommerce.set(y, tag);
        y.setPersistenceRequired();
        y.finalizeSpawn(level, level.getCurrentDifficultyAt(y.blockPosition()), MobSpawnType.COMMAND, null);
        level.addFreshEntity(y);
        HumanCommerce.bindPending(y, keeper);
        return y;
    }

    /** A caretaker (aipai) with a pet of its own, out on the lead: the pet is its companion with a harness, and with {@code leashNow} the walk has already begun. */
    public static HumanNpc spawnWalker(ServerLevel level, Vec3 at, boolean sister, YukkuriVariant variant, boolean leashNow) {
        var npc = (sister ? HumanWorld.SISTER : HumanWorld.BROTHER).get().spawn(level, BlockPos.containing(at), MobSpawnType.COMMAND);
        if (npc == null) return null;
        npc.setAttitude(HumanNpc.Attitude.CARING);
        var pet = spawnPet(level, npc, variant);
        if (pet != null && leashNow) npc.beginWalk();
        return npc;
    }
    /** Used by natural spawns: gives the resident a random ordinary pet. */
    static void givePet(HumanNpc npc, ServerLevel level) {
        var pool = new ArrayList<YukkuriVariant>();
        for (var v : YukkuriVariant.values()) if (!SpeciesProfile.of(v).houseBody() && !SpeciesProfile.of(v).dangerToYukkuri()) pool.add(v);
        spawnPet(level, npc, pool.get(npc.getRandom().nextInt(pool.size())));
    }
    private static YukkuriEntity spawnPet(ServerLevel level, HumanNpc npc, YukkuriVariant variant) {
        var pet = ModEntities.forVariant(variant).create(level);
        if (pet == null) return null;
        pet.moveTo(npc.getX() + 1.2, npc.getY(), npc.getZ(), 0, 0);
        pet.finalizeSpawn(level, level.getCurrentDifficultyAt(npc.blockPosition()), MobSpawnType.COMMAND, null);
        pet.setPersistenceRequired();
        level.addFreshEntity(pet);
        npc.adoptPet(pet);
        return pet;
    }

    /** An Oniisan (Mohawk): a cruel resident. With {@code prey}, a wild Reimu stands a few blocks away for it to find (never a pet, never owned). */
    public static HumanNpc spawnCruel(ServerLevel level, Vec3 at, boolean prey) {
        var npc = HumanWorld.ONIISAN.get().spawn(level, BlockPos.containing(at), MobSpawnType.COMMAND);
        if (npc == null) return null;
        npc.setAttitude(HumanNpc.Attitude.CRUEL);
        if (prey) {
            var wild = ModEntities.REIMU.get().spawn(level, BlockPos.containing(at.add(5, 0, 0)), MobSpawnType.COMMAND);
            if (wild != null) wild.setPersistenceRequired();
        }
        return npc;
    }
}
