package io.github.longshou.mcyukkuri.life;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.world.item.DyeColor;

/**
 * Dye-selected looks of the placed pet goods. A style only changes the drawn mesh (block id, or block id + "_" + style);
 * the default style "" keeps the plain mesh name. Mesh names must match
 * standards/yukkuri-library/drafts/care-props/placed.py STYLES.
 */
public final class CareStyles {
    public record Style(String id, String label) {}
    private static final Map<String, Map<DyeColor, Style>> TABLE = new LinkedHashMap<>();
    static {
        add("food_bowl", DyeColor.CYAN, "", "奶油薄荷", DyeColor.RED, "hearts", "红心", DyeColor.PINK, "polka", "樱粉波点",
                DyeColor.LIGHT_BLUE, "sailor", "水手条纹", DyeColor.YELLOW, "paws", "柠檬爪印", DyeColor.ORANGE, "terracotta", "赤陶流釉",
                DyeColor.LIGHT_GRAY, "steel", "不锈钢");
        add("yukkuri_nest", DyeColor.RED, "", "草莓", DyeColor.LIGHT_BLUE, "gingham", "蓝格", DyeColor.PINK, "polka", "粉波点",
                DyeColor.LIME, "floral", "薄荷碎花", DyeColor.PURPLE, "lavender", "薰衣草绗缝", DyeColor.BROWN, "teddy", "小熊绒",
                DyeColor.BLUE, "stars", "星夜");
        add("large_yukkuri_nest", DyeColor.RED, "", "草莓编织", DyeColor.BROWN, "wicker", "原色藤编", DyeColor.CYAN, "mint", "薄荷格纹",
                DyeColor.PURPLE, "lavender", "薰衣草");
        add("yukkuri_toilet", DyeColor.CYAN, "", "薄荷", DyeColor.PINK, "pink", "樱粉", DyeColor.YELLOW, "lemon", "柠檬",
                DyeColor.LIGHT_BLUE, "sky", "天蓝");
        add("ceramic_toilet", DyeColor.WHITE, "", "奶油薄荷", DyeColor.PINK, "polka", "樱粉波点", DyeColor.LIGHT_BLUE, "sailor", "水手条纹");
        add("covered_toilet", DyeColor.CYAN, "", "薄荷盖", DyeColor.PINK, "pink", "粉点盖", DyeColor.BLUE, "navy", "星夜盖");
    }
    private static void add(String block, Object... entries) {
        var styles = new LinkedHashMap<DyeColor, Style>();
        for (int i = 0; i < entries.length; i += 3) styles.put((DyeColor) entries[i], new Style((String) entries[i + 1], (String) entries[i + 2]));
        TABLE.put(block, Collections.unmodifiableMap(styles));
    }
    private CareStyles() {}

    public static boolean styled(String block) { return TABLE.containsKey(block); }
    /** Style for this dye on this block, or null when the block has no style of that colour. */
    public static Style forDye(String block, DyeColor dye) {
        var styles = TABLE.get(block);
        return styles == null ? null : styles.get(dye);
    }
    public static boolean valid(String block, String style) {
        var styles = TABLE.get(block);
        return styles != null && styles.values().stream().anyMatch(s -> s.id().equals(style));
    }
    public static String label(String block, String style) {
        var styles = TABLE.get(block);
        return styles == null ? style : styles.values().stream().filter(s -> s.id().equals(style)).map(Style::label).findFirst().orElse(style);
    }
    /** Dyes accepted by this block, e.g. "青色=奶油薄荷、红色=红心". */
    public static String hint(String block) {
        var styles = TABLE.get(block);
        if (styles == null) return "";
        return styles.entrySet().stream().map(e -> dyeName(e.getKey()) + "=" + e.getValue().label()).collect(Collectors.joining("、"));
    }
    public static String model(String base, String style) { return style == null || style.isEmpty() ? base : base + "_" + style; }
    private static String dyeName(DyeColor dye) {
        return switch (dye) {
            case WHITE -> "白色"; case ORANGE -> "橙色"; case MAGENTA -> "品红"; case LIGHT_BLUE -> "淡蓝"; case YELLOW -> "黄色";
            case LIME -> "黄绿"; case PINK -> "粉红"; case GRAY -> "灰色"; case LIGHT_GRAY -> "淡灰"; case CYAN -> "青色";
            case PURPLE -> "紫色"; case BLUE -> "蓝色"; case BROWN -> "棕色"; case GREEN -> "绿色"; case RED -> "红色"; case BLACK -> "黑色";
        };
    }
}
