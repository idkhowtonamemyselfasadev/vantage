package dev.vantage.module.finder;

import java.util.List;

import dev.vantage.setting.BoolSetting;

/** Fully grown amethyst clusters: highlight, arrow to the nearest one, chat ping per geode. */
public class AmethystFinder extends ArrowFinder {
    public final BoolSetting buds = add(new BoolSetting("Large buds too", "Also count large amethyst buds", false))
            .onChange(() -> blocks.set(buds() ? List.of("amethyst_cluster", "large_amethyst_bud") : List.of("amethyst_cluster")));

    private boolean buds() { return buds.on(); }

    public AmethystFinder() {
        super("Amethyst Finder", "Arrow + highlight to fully grown amethyst clusters", List.of("amethyst_cluster"),
                "Amethyst cluster", "AMETHYST", "cluster", "clusters", 24, 0xFFAD879B);
    }
}
