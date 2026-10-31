package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.BodyConstants;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.EnumMap;

/** Immutable capabilities and presentation coefficients. Never dispatches an ecology routine. */
public record SpeciesProfile(Ecology ecology, BreedingPool pool, FamilyData.BirthKind birth,
                             boolean aquatic, boolean wildSociety, boolean landSettler, boolean dangerToYukkuri,
                             boolean boundBySocialRules, boolean hatVessel, boolean jumps, boolean diurnal,
                             int metabolismDivisor, float modelScale, float portionScale, SpeciesTraits traits) {
    public enum Ecology { WILD, HOUSE, PREDATOR, AQUATIC }
    public enum BreedingPool { LAND, AQUATIC, HOUSE, PREDATOR }

    private static final EnumMap<YukkuriVariant, EnumMap<SubspeciesLife.Kind, SpeciesProfile>> PROFILES = create();

    /** null birth means the existing pair-specific LIVE/STEM decision must still run. */
    public static SpeciesProfile of(YukkuriVariant variant, SubspeciesLife.Kind kind) {
        return PROFILES.get(variant).get(kind);
    }
    public static SpeciesProfile of(YukkuriVariant variant) { return of(variant, SubspeciesLife.Kind.NORMAL); }

    private static EnumMap<YukkuriVariant, EnumMap<SubspeciesLife.Kind, SpeciesProfile>> create() {
        var profiles = new EnumMap<YukkuriVariant, EnumMap<SubspeciesLife.Kind, SpeciesProfile>>(YukkuriVariant.class);
        for (var variant : YukkuriVariant.values()) {
            var forms = new EnumMap<SubspeciesLife.Kind, SpeciesProfile>(SubspeciesLife.Kind.class);
            for (var kind : SubspeciesLife.Kind.values()) {
                boolean house = variant.isHouse(), predator = variant.isPredator();
                boolean aquatic = kind.aquatic();
                Ecology ecology = house ? Ecology.HOUSE : predator ? Ecology.PREDATOR : aquatic ? Ecology.AQUATIC : Ecology.WILD;
                BreedingPool pool = house ? BreedingPool.HOUSE : predator ? BreedingPool.PREDATOR : aquatic ? BreedingPool.AQUATIC : BreedingPool.LAND;
                forms.put(kind, new SpeciesProfile(ecology, pool,
                        house ? FamilyData.BirthKind.EGG : predator ? FamilyData.BirthKind.STEM : null,
                        aquatic, !house && !predator, !house && !predator && !aquatic, predator, !predator,
                        variant == YukkuriVariant.MARISA, !house, !house, house ? 4 : 1,
                        house ? BodyConstants.HOUSE_SOURCE_SCALE : 1,
                        house ? BodyConstants.HOUSE_WOUND_SIZE : 1, SpeciesTraits.of(variant)));
            }
            profiles.put(variant, forms);
        }
        return profiles;
    }
    /** Exact identity predicates for existing asymmetric capability checks. */
    public boolean houseBody() { return ecology == Ecology.HOUSE; }
    public boolean surfaceForager() { return !houseBody(); }
    public float woundSize() { return portionScale; }
    public float shadowRadius() { return houseBody() ? .12f : .32f; }
    public double renderMargin() { return houseBody() ? .4 : 1.2; }
    public double bodyOverlayTop() { return houseBody() ? .35 : .87; }
    public float operationTipScale() { return houseBody() ? .25f : .65f; }
    /** The legacy hunger interval multiplies by this value; it does not divide the interval. */
    public int hungerIntervalMultiplier() { return metabolismDivisor; }
}
