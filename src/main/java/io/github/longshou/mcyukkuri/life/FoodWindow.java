package io.github.longshou.mcyukkuri.life;

final class FoodWindow {
    private FoodWindow() {}

    static double heldFoodRange(float nutrition, int boldness) {
        double need = Math.clamp((LifeMeal.FULL - nutrition) / (LifeMeal.FULL - 15.0), 0, 1);
        // Notice hands across a six-block street while peckish, or across a twelve-block frontage when hungry.
        // Boldness shifts either end by up to two blocks; the real pane and supported approach still decide contact.
        return 6 + 6 * need + (boldness - 50) * .04;
    }
}
