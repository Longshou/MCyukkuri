package io.github.longshou.mcyukkuri.notebook;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.house.HouseholdProtection;
import io.github.longshou.mcyukkuri.item.HouseholdSprayItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.subspecies.AquaticShore;
import io.github.longshou.mcyukkuri.subspecies.FirstVoyage;
import io.github.longshou.mcyukkuri.subspecies.HatBoatCargo;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.Locale;

/** Short vanilla book pages: controls, real recipes and separate implementation status. */
public final class NotebookGuide {
    public static List<Component> pages(ItemStack notebook) {
        List<Component> pages = new ArrayList<>();
        for (String chapter : List.of("start", "route", "discovery", "care", "water", "aquatic", "aquatic_family", "social", "home", "family", "birth",
                "childhood", "house", "house_start", "house_supplies", "house_cleanup", "house_guard", "body", "tools", "play", "storage", "retrieval",
                "commerce", "progress", "status", "accessories", "community", "season"))
            pages.add(chapter.equals("house_guard")?Component.translatable("notebook.mcyukkuri.chapter.house_guard",
                    HouseholdSprayItem.USES,HouseholdProtection.RADIUS,HouseholdProtection.HEIGHT,HouseholdProtection.DURATION/1200)
                    :Component.translatable("notebook.mcyukkuri.chapter." + chapter));
        CompoundTag seen = notebook.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("ObservedSpecies");
        for (YukkuriVariant variant : YukkuriVariant.values()) {
            pages.add(Component.translatable("notebook.mcyukkuri.chapter.species",
                    Component.translatable("entity.mcyukkuri." + variant.assetId()),
                    Component.translatable(seen.getBoolean(variant.assetId()) ? "notebook.mcyukkuri.chapter.seen" : "notebook.mcyukkuri.chapter.unseen"),
                    Component.translatable("notebook.mcyukkuri.chapter." +
                            (SpeciesProfile.of(variant).dangerToYukkuri() ? "predator" : SpeciesProfile.of(variant).houseBody() ? "house_species" : "standard_species"))));
        }
        CompoundTag forms = notebook.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("ObservedForms");
        for (String form : List.of("kind.shell_marisa", "kind.aquatic_marisa", "kind.wasa_reimu", "acquired.deibu", "congenital.sparse_hair", "congenital.face_asymmetry", "congenital.bean_body"))
            pages.add(Component.translatable("notebook.mcyukkuri.chapter.form_entry", Component.translatable("subspecies.mcyukkuri."+form),
                    Component.translatable(forms.getBoolean(form)?"notebook.mcyukkuri.chapter.seen":"notebook.mcyukkuri.chapter.unseen")));
        return List.copyOf(pages);
    }
    public static void record(ItemStack notebook, YukkuriEntity entity) {
        CustomData.update(DataComponents.CUSTOM_DATA, notebook, data -> {
            CompoundTag seen = data.getCompound("ObservedSpecies");
            seen.putBoolean(entity.getVariant().assetId(), true); data.put("ObservedSpecies", seen);
            CompoundTag forms=data.getCompound("ObservedForms");
            forms.putBoolean("kind."+entity.subspecies().kind().name().toLowerCase(Locale.ROOT),true);
            forms.putBoolean("congenital."+entity.subspecies().congenital().name().toLowerCase(Locale.ROOT),true);
            if(entity.subspecies().deibu())forms.putBoolean("acquired.deibu",true);
            data.put("ObservedForms",forms);
        });
    }
    public static Component form(YukkuriEntity entity) {
        var shape=entity.subspecies();var boat=HatBoatEntity.find(entity);
        return Component.translatable("notebook.mcyukkuri.chapter.form",
                shape.deibu()?Component.translatable("subspecies.mcyukkuri.acquired.deibu").append(" / ").append(Component
                        .translatable("subspecies.mcyukkuri.kind."+shape.kind().name().toLowerCase(Locale.ROOT))):Component
                        .translatable("subspecies.mcyukkuri.kind."+shape.kind().name().toLowerCase(Locale.ROOT)),
                Component.translatable("subspecies.mcyukkuri.congenital."+shape.congenital().name().toLowerCase(Locale.ROOT)),
                Component.translatable(boat==null?"notebook.mcyukkuri.chapter.no_boat":"subspecies.mcyukkuri.boat."+boat.state().name().toLowerCase(Locale.ROOT)),
                shape.boatExperienced()?"✓":"—");
    }
    public static Component pregnancy(YukkuriEntity entity) {
        var brood = entity.family().brood();
        if (brood == null || brood.cancelled || brood.complete()) return Component.translatable("notebook.mcyukkuri.chapter.no_brood");
        return Component.translatable("notebook.mcyukkuri.chapter.brood",
                Component.translatable("notebook.mcyukkuri.chapter.birth_" + brood.kind.name().toLowerCase(Locale.ROOT)),
                Math.round(brood.progress() * 100), brood.unborn(),
                brood.father == null ? Component.translatable("family.mcyukkuri.unknown") : Component.literal(brood.father.toString().substring(0, 8)));
    }
    public static Component childhood(YukkuriEntity entity) {
        var family=entity.family();var home=entity.habitat().shelter();
        boolean natural=home!=null&&home.kind()==ShelterBlock.Kind.BURROW&&home.owns(entity.getUUID());
        String nest=entity.independence().independent()?"independent":!natural?"other":home.privateNest(entity.getUUID())?"private":
                family.readyForOwnNest()?"ready":"shared";
        return Component.translatable("notebook.mcyukkuri.childhood",
                Component.translatable("notebook.mcyukkuri.childhood."+nest),
                Math.min(100,Math.round(100f*family.age()/family.maturityAge())),
                Math.min(100,Math.round(100f*family.nourishment()/family.maturityAge())),
                family.carrying().isEmpty()?Component.translatable("notebook.mcyukkuri.empty_mouth"):
                        InfantFood.isMash(family.carrying())?Component.translatable("notebook.mcyukkuri.childhood.mash",family.carrying().getHoverName()):family.carrying().getHoverName(),
                Component.translatable("notebook.mcyukkuri.childhood."+(family.needsMash()?"dependent":"self_feeding")))
                .append(Component.translatable("notebook.mcyukkuri.childhood.skills",
                        entity.childhood().firstToiletDone()?"✓":"—", entity.childhood().canJump()?"✓":"—",
                        entity.childhood().canExercise()?"✓":"—", entity.childhood().canLearn()?"✓":"—",
                        entity.childhood().canLiveIndependently()?"✓":"—"));
    }
    public static Component aquatic(YukkuriEntity entity) {
        var boat=HatBoatEntity.find(entity);
        if(boat==null)return Component.translatable("notebook.mcyukkuri.chapter.aquatic");
        return Component.translatable("notebook.mcyukkuri.aquatic_status",
                Component.translatable("subspecies.mcyukkuri.boat."+boat.state().name().toLowerCase(Locale.ROOT)),
                boat.cargo().paddle()?"✓":"—",boat.capacityUsed(),HatBoatCargo.CAPACITY,
                boat.youngAboard(),Component.translatable("notebook.mcyukkuri.hat_space."+
                        (boat.overcrowded()?"crowded":boat.hasRoomForYoung()?"available":"full")),
                Component.translatable("activity.mcyukkuri."+entity.getBehaviorId()));
    }
    public static Component aquaticFamily(YukkuriEntity entity) {
        var owner = entity.getVehicle() instanceof HatBoatEntity boat
                && boat.getControllingPassenger() instanceof YukkuriEntity parent ? parent : entity;
        var life = owner.aquaticLife(); var care = life.care();
        return Component.translatable("notebook.mcyukkuri.aquatic_family",life.provisions(),care.meals(),care.comforts(),care.songs(),
                care.serving().isEmpty()?Component.translatable("notebook.mcyukkuri.empty_mouth"):care.serving().getHoverName())
                .append(Component.translatable("notebook.mcyukkuri.first_voyage",
                        Math.round(entity.family().scale()*100),Math.round(FirstVoyage.MIN_SCALE*100),
                        Component.translatable("notebook.mcyukkuri.first_voyage."+entity.aquaticLife().firstVoyage().status())))
                .append(Component.translatable("notebook.mcyukkuri.aquatic_shore",entity.aquaticLife().shore().landTicks()/20,
                        AquaticShore.VISIT_TICKS/20));
    }
    private NotebookGuide() {}
}
