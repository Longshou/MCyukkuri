package io.github.longshou.mcyukkuri.entity;

/** Stable asset selection comes from EntityType, without an extra network field. */
public enum YukkuriVariant {
    REIMU("reimu", false), MARISA("marisa", false),
    HOUSE_REIMU("house_reimu", true), HOUSE_MARISA("house_marisa", true),
    CHEN("chen", false), ALICE("alice", false), PATCHOULI("patchouli", false),
    REMILIA("remilia", false), FLANDRE("flandre", false);

    private final String assetId;
    private final boolean house;

    YukkuriVariant(String assetId, boolean house) {
        this.assetId = assetId;
        this.house = house;
    }

    public String assetId() { return assetId; }
    public String dialogueSpecies() { return switch (this) {
        case HOUSE_REIMU -> "reimu"; case HOUSE_MARISA -> "marisa"; default -> assetId;
    }; }
    public boolean isHouse() { return house; }
    public boolean isPredator() { return this == REMILIA || this == FLANDRE; }
}
