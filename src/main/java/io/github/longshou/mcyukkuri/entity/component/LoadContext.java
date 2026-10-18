package io.github.longshou.mcyukkuri.entity.component;

import net.minecraft.nbt.CompoundTag;

/** The original entity root, including legacy sibling compounds (notably Pet.Stress). */
public record LoadContext(CompoundTag root, int version) {}
