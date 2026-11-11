package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import io.github.longshou.mcyukkuri.interaction.CareLineage;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,MCYukkuri.MOD_ID);
    public static final DeferredHolder<MenuType<?>,MenuType<CareMenu>> CARE=MENUS.register("care_actions",
            ()->IMenuTypeExtension.create((id,inventory,buffer)->{
                int target=buffer.readVarInt();
                boolean cutter=buffer.readBoolean();
                return new CareMenu(id,inventory,target,cutter,cutter?null:CareLineage.read(buffer));
            }));
    private ModMenus(){}
}
