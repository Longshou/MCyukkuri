package io.github.longshou.mcyukkuri.notebook;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Short vanilla book pages: controls, real recipes and separate implementation status. */
public final class NotebookGuide {
    public static List<Component> pages(ItemStack notebook) {
        List<Component> pages = new ArrayList<>();
        for (String chapter : List.of("start", "route", "discovery", "care", "water", "social", "home", "family", "birth", "house", "body", "tools", "play", "storage", "retrieval", "commerce", "progress", "status", "accessories", "community"))
            pages.add(Component.translatable("notebook.mcyukkuri.chapter." + chapter));
        CompoundTag seen = notebook.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("ObservedSpecies");
        for (YukkuriVariant variant : YukkuriVariant.values()) {
            pages.add(Component.translatable("notebook.mcyukkuri.chapter.species",
                    Component.translatable("entity.mcyukkuri." + variant.assetId()),
                    Component.translatable(seen.getBoolean(variant.assetId()) ? "notebook.mcyukkuri.chapter.seen" : "notebook.mcyukkuri.chapter.unseen"),
                    Component.translatable("notebook.mcyukkuri.chapter." + (variant.isPredator() ? "predator" : variant.isHouse() ? "house_species" : "standard_species"))));
        }
        CompoundTag forms = notebook.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("ObservedForms");
        for (String form : List.of("kind.shell_marisa", "kind.wasa_reimu", "congenital.sparse_hair", "congenital.face_asymmetry", "congenital.bean_body"))
            pages.add(Component.translatable("notebook.mcyukkuri.chapter.form_entry", Component.translatable("subspecies.mcyukkuri."+form),
                    Component.translatable(forms.getBoolean(form)?"notebook.mcyukkuri.chapter.seen":"notebook.mcyukkuri.chapter.unseen")));
        return List.copyOf(pages);
    }
    public static void record(ItemStack notebook, YukkuriEntity entity) {
        CustomData.update(DataComponents.CUSTOM_DATA, notebook, data -> {
            CompoundTag seen = data.getCompound("ObservedSpecies");
            seen.putBoolean(entity.getVariant().assetId(), true); data.put("ObservedSpecies", seen);
            CompoundTag forms=data.getCompound("ObservedForms");
            forms.putBoolean("kind."+entity.subspecies().kind().name().toLowerCase(java.util.Locale.ROOT),true);
            forms.putBoolean("congenital."+entity.subspecies().congenital().name().toLowerCase(java.util.Locale.ROOT),true);
            data.put("ObservedForms",forms);
        });
    }
    public static Component form(YukkuriEntity entity) {
        var shape=entity.subspecies();var boat=io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.find(entity);
        return Component.translatable("notebook.mcyukkuri.chapter.form",
                Component.translatable("subspecies.mcyukkuri.kind."+shape.kind().name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable("subspecies.mcyukkuri.congenital."+shape.congenital().name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable(boat==null?"notebook.mcyukkuri.chapter.no_boat":"subspecies.mcyukkuri.boat."+boat.state().name().toLowerCase(java.util.Locale.ROOT)),
                shape.boatExperienced()?"✓":"—");
    }
    public static Component pregnancy(YukkuriEntity entity) {
        var brood = entity.family().brood();
        if (brood == null || brood.cancelled || brood.complete()) return Component.translatable("notebook.mcyukkuri.chapter.no_brood");
        return Component.translatable("notebook.mcyukkuri.chapter.brood",
                Component.translatable("notebook.mcyukkuri.chapter.birth_" + brood.kind.name().toLowerCase(java.util.Locale.ROOT)),
                Math.round(brood.progress() * 100), brood.unborn(),
                brood.father == null ? Component.translatable("family.mcyukkuri.unknown") : Component.literal(brood.father.toString().substring(0, 8)));
    }
    private NotebookGuide() {}
}
