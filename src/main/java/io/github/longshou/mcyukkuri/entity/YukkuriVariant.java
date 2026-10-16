package io.github.longshou.mcyukkuri.entity;

/** Pure registration metadata. No registry holders or client classes may be initialized from here.
 * Keep this order: family/stem persistence currently encodes these nine ordinals in four bits. */
public enum YukkuriVariant {
    REIMU("reimu", "reimu", false, false, true, .75F, .53F, .28F, Accessory.RIBBON, 0xF1D5AA, 0xB52E36),
    MARISA("marisa", "marisa", false, false, true, .75F, .53F, .28F, Accessory.HAT, 0xF1D5AA, 0x342D38),
    HOUSE_REIMU("house_reimu", "reimu", true, false, false, .27F, .23875F, .14F, Accessory.RIBBON, 0xD9C39D, 0xB52E36),
    HOUSE_MARISA("house_marisa", "marisa", true, false, false, .27F, .23875F, .14F, Accessory.HAT, 0xD9C39D, 0x342D38),
    CHEN("chen", "chen", false, false, true, .75F, .53F, .28F, Accessory.CHEN_HAT, 0xF1D5AA, 0x497647),
    ALICE("alice", "alice", false, false, true, .75F, .53F, .28F, Accessory.ALICE_HEADBAND, 0xF1D5AA, 0xC93D4E),
    PATCHOULI("patchouli", "patchouli", false, false, true, .75F, .53F, .28F, Accessory.PATCHOULI_BONNET, 0xF1D5AA, 0xAD87BA),
    REMILIA("remilia", "remilia", false, true, true, .80F, .60F, .34F, Accessory.REMILIA_BONNET, 0xA8C4E8, 0xC63642),
    FLANDRE("flandre", "flandre", false, true, true, .80F, .60F, .34F, Accessory.FLANDRE_BONNET, 0xEFCA65, 0xD03848);

    /** Shared by ordinary and house forms. Shell Marisa remains an entity-level subspecies override. */
    public enum Accessory {
        RIBBON("reimu_ribbon"), HAT("marisa_hat"), CHEN_HAT("chen_hat"), ALICE_HEADBAND("alice_headband"),
        PATCHOULI_BONNET("patchouli_bonnet"), REMILIA_BONNET("remilia_bonnet"), FLANDRE_BONNET("flandre_bonnet");
        private final String id;
        Accessory(String id) { this.id = id; }
        public String id() { return id; }
    }
    private final String assetId, dialogueSpecies;
    private final boolean house, predator, naturalSpawn;
    private final float width, height, eyeHeight;
    private final Accessory accessory;
    private final int eggPrimaryColor, eggSecondaryColor;

    YukkuriVariant(String assetId, String dialogueSpecies, boolean house, boolean predator, boolean naturalSpawn,
                   float width, float height, float eyeHeight, Accessory accessory, int eggPrimaryColor, int eggSecondaryColor) {
        this.assetId = assetId; this.dialogueSpecies = dialogueSpecies;
        this.house = house; this.predator = predator; this.naturalSpawn = naturalSpawn;
        this.width = width; this.height = height; this.eyeHeight = eyeHeight; this.accessory = accessory;
        this.eggPrimaryColor = eggPrimaryColor; this.eggSecondaryColor = eggSecondaryColor;
    }

    public String assetId() { return assetId; }
    public String dialogueSpecies() { return dialogueSpecies; }
    public boolean isHouse() { return house; }
    public boolean isPredator() { return predator; }
    public boolean naturalSpawn() { return naturalSpawn; }
    public float width() { return width; }
    public float height() { return height; }
    public float eyeHeight() { return eyeHeight; }
    public Accessory accessory() { return accessory; }
    public String spawnEggId() { return assetId + "_spawn_egg"; }
    public int eggPrimaryColor() { return eggPrimaryColor; }
    public int eggSecondaryColor() { return eggSecondaryColor; }
}
