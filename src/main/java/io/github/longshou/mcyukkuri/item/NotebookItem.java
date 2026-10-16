package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A normal survival item using vanilla read-only book pages, populated by the server on observation. */
public final class NotebookItem extends Item {
    public NotebookItem(Properties properties) { super(properties); }
    private static void open(Player player, InteractionHand hand, List<Component> pages) {
        if (!(player instanceof ServerPlayer server)) return;
        ItemStack book = player.getItemInHand(hand);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Yukkuri Notes"), "MCyukkuri", 0,
                pages.stream().map(Filterable::passThrough).toList(), true));
        server.containerMenu.broadcastChanges(); server.inventoryMenu.broadcastChanges();
        // Vanilla's BookAccess reads the component on any item; openItemGui itself is restricted to written books.
        server.connection.send(new ClientboundOpenBookPacket(hand));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel serverLevel) {
            var pages = new java.util.ArrayList<>(io.github.longshou.mcyukkuri.notebook.NotebookGuide.pages(player.getItemInHand(hand)));
            pages.add(0, io.github.longshou.mcyukkuri.season.SeasonCalendar.page(serverLevel, player.blockPosition()));
            open(player, hand, pages);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var shelter=io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(context.getLevel(),context.getClickedPos());
        if (shelter==null && (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.NEST))
            return InteractionResult.PASS;
        if (!context.getLevel().isClientSide) {
            CustomData.update(DataComponents.CUSTOM_DATA, context.getItemInHand(), data -> {
                data.putString("HomeDimension", context.getLevel().dimension().location().toString()); data.putLong("HomePos", (shelter==null?context.getClickedPos():shelter.getBlockPos()).asLong());
            });
            if (context.getPlayer() != null) context.getPlayer().displayClientMessage(Component.translatable("care.mcyukkuri.nest.selected"), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // Creative entity interactions supply a copy; discovery belongs to the player's real notebook.
        stack = player.getItemInHand(hand);
        if (!stack.is(this)) return InteractionResult.PASS;
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (!player.level().isClientSide) {
            CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (player.isSecondaryUseActive() && data.contains("HomePos")) {
                ResourceLocation dimension = ResourceLocation.tryParse(data.getString("HomeDimension"));
                boolean assigned = dimension != null && entity.life().assignHome(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(data.getLong("HomePos"))));
                player.displayClientMessage(Component.translatable(assigned ? "care.mcyukkuri.nest.assigned" : "care.mcyukkuri.nest.invalid"), true);
                if (assigned) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(player, "shelter");
            } else {
                io.github.longshou.mcyukkuri.notebook.NotebookGuide.record(stack, entity);
                io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(player, "observe");
                open(player, hand, observe(entity, player));
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    public static List<Component> observe(YukkuriEntity entity, Player player) {
        var life = entity.life(); var personality = life.personality();
        Component home = life.home() == null ? Component.translatable("notebook.mcyukkuri.homeless") : Component.literal(
                life.home().dimension().location() + "\n" + life.home().pos().toShortString());
        var pages = new java.util.ArrayList<Component>(List.of(
                io.github.longshou.mcyukkuri.notebook.NotebookGuide.form(entity),
                io.github.longshou.mcyukkuri.settlement.SettlementObservation.page(entity),
                io.github.longshou.mcyukkuri.settlement.SettlementObservation.ordersPage(entity),
                entity.independence().observation(),
                life.temperature().observation(),
                io.github.longshou.mcyukkuri.season.SeasonCalendar.page((ServerLevel) entity.level(), entity.blockPosition()),
                io.github.longshou.mcyukkuri.season.AutumnStock.observation(entity),
                entity.wintering().observation(),
                entity.habitat().shelter()==null?Component.translatable("shelter.mcyukkuri.none"):entity.habitat().shelter().description(),
                Component.translatable("notebook.mcyukkuri.stats", entity.getName(), Math.round(life.nutrition()), Math.round(life.energy()),
                        Math.round(life.hygiene()), Math.round(life.wetness()), Math.round(life.bladder()),
                        Component.translatable("activity.mcyukkuri." + entity.getBehaviorId())),
                Component.translatable("notebook.mcyukkuri.home", home, life.mouth().isEmpty() ? Component.translatable("notebook.mcyukkuri.empty_mouth") : life.mouth().getHoverName(),
                        life.hasAccessory() ? Component.translatable("notebook.mcyukkuri.accessory.present") : Component.translatable("notebook.mcyukkuri.accessory.absent")),
                Component.translatable("notebook.mcyukkuri.emotional_needs",entity.dailyLife().affectionNeed(),entity.dailyLife().boredom(),
                        entity.dailyLife().nuzzles(),entity.dailyLife().songs(),entity.dailyLife().exercises()),
                Component.translatable("notebook.mcyukkuri.dispositions",personality.selfishness(),personality.aggression(),personality.attachment(),personality.selfControl(),
                        personality.environment().friendliness(),Component.translatable("decision.mcyukkuri."+personality.lastDecision())),
                Component.translatable("notebook.mcyukkuri.memory", personality.trust(player.getUUID()), personality.fear(player.getUUID()), personality.interactions(player.getUUID()), personality.lessons()),
                Component.translatable("notebook.mcyukkuri.growth",Component.translatable("stage.mcyukkuri."+entity.family().stage().name().toLowerCase(java.util.Locale.ROOT)),
                        Math.round(entity.family().scale()*100),entity.family().age()/1200,entity.family().lifespan()/1200,
                        Component.translatable(entity.family().youngVoice()?"voice.mcyukkuri.young":"voice.mcyukkuri.adult")),
                Component.translatable("notebook.mcyukkuri.family",identity(entity.family().mother()),identity(entity.family().father()),
                        identity(entity.family().partner()),entity.family().brood()==null?0:entity.family().brood().unborn(),entity.family().recovery()/20),
                io.github.longshou.mcyukkuri.notebook.NotebookGuide.pregnancy(entity),
                Component.translatable("notebook.mcyukkuri.body",entity.body().wound(),entity.body().infection(),entity.body().filling(),entity.body().mold()),
                Component.translatable("notebook.mcyukkuri.consumables",Math.round(life.dryness()),life.constipated()?"✓":"—",life.spicyTicks()/20,life.sprayDelay()/20,life.drugSleep()/20,entity.family().sexDrive()/20,entity.family().babyTalk()?"✓":"—"),
                Component.translatable("notebook.mcyukkuri.house",entity.house().nestPosition()==null?"—":entity.house().nestPosition().toShortString(),
                        entity.house().carrying().isEmpty()?"—":entity.house().carrying().getHoverName()),
                io.github.longshou.mcyukkuri.cognition.CognitionObservation.page(entity)));
        if (io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)
                || entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity) {
            pages.add(1, io.github.longshou.mcyukkuri.notebook.NotebookGuide.aquatic(entity));
            pages.add(2, io.github.longshou.mcyukkuri.notebook.NotebookGuide.aquaticFamily(entity));
        }
        Component claim = io.github.longshou.mcyukkuri.event.HomeClaim.page(entity);
        if (claim != null) pages.add(claim);
        return List.copyOf(pages);
    }
    private static Component identity(java.util.UUID id) {
        return id==null?Component.translatable("family.mcyukkuri.unknown"):Component.literal(id.toString().substring(0,8));
    }
}
