package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

/** Holds the GUI key (2) and the look. Pressing its key opens the GUI instead of toggling. */
public class ClickGuiModule extends Module {
    public final ModeSetting theme = add(new ModeSetting("Theme", "Colour preset for the whole client: menu, HUD and screens follow the accent", "Vantage", dev.vantage.core.Theme.names()));
    public final ColorSetting accent = add(new ColorSetting("Accent", "Highlight colour everywhere (Custom theme)", 0xFF6CBC7A)).visibleIf(() -> theme.is("Custom"));
    public final NumberSetting size = add(new NumberSetting("Menu size", "How big the menu is on screen", 1, 0.7, 1.5, 0.05));
    public final BoolSetting blur = add(new BoolSetting("Dim world", "Darken the game behind the GUI", true));
    public final BoolSetting descriptions = add(new BoolSetting("Descriptions", "Help line at the bottom", true));
    public final BoolSetting animations = add(new BoolSetting("Animations", "Fades and slides in the menu", true));
    public final dev.vantage.setting.BetaSwitch betaFeatures = add(new dev.vantage.setting.BetaSwitch("Beta features", "Unfinished features. They can be wrong, change or disappear. Applies at the next game start"));
    public final dev.vantage.setting.InfoSetting betaRestart = add(new dev.vantage.setting.InfoSetting("Restart needed", "The switch above is saved, but the modules are only added when the game starts", () -> "Restart the game to apply")).visibleIf(dev.vantage.core.Modules::betaNeedsRestart);
    public final dev.vantage.setting.ActionSetting sendFeedback = add(new dev.vantage.setting.ActionSetting("Send feedback", "Write to the developer: only your text and the version numbers are sent, nothing about you", () -> mc.setScreen(new dev.vantage.gui.FeedbackScreen(mc.screen))));
    public final BoolSetting screens = add(new BoolSetting("Vantage screens", "Title, singleplayer and multiplayer screens in the menu's look; off = vanilla screens", true));
    public final BoolSetting presence = add(new BoolSetting("Presence", "Other Vantage users see a mark on you. Sends your UUID and a server hash once a minute; the owner can see them", false)).onChange(() -> dev.vantage.core.PresenceConsent.onToggle(this.presence.on()));
    public final dev.vantage.setting.InfoSetting presenceAnswer = add(new dev.vantage.setting.InfoSetting("Presence answer", "Your answer to the one-time question", dev.vantage.core.PresenceConsent::statusLine));
    public final BoolSetting presenceBadge = add(new BoolSetting("Presence badge", "The Vantage mark in front of names in the tab list, name tags and Shard Watch", true)).visibleIf(presence::on);
    public final BoolSetting updates = add(new BoolSetting("Check for updates", "Look on GitHub for a newer build at every start", true));
    public final BoolSetting auto = add(new BoolSetting("Auto update", "Fetch a newer build before the game opens and start straight into it; off = ask on the title screen", true));

    public final dev.vantage.setting.InfoSetting updateStatus = add(new dev.vantage.setting.InfoSetting("Update status", "Only builds whose checksum (SHA-256) matches the release get installed", () -> {
        String t = dev.vantage.core.Updater.state();
        return t.isEmpty() ? "no check yet" : t;
    }));
    public final dev.vantage.setting.InfoSetting verifyBuild = add(new dev.vantage.setting.InfoSetting("Verify this build (beta)", "Your jar vs the latest release digest", dev.vantage.core.Updater::verifyLine));

    /** One character that starts a client command in chat. The setting refuses a letter, a digit, a space and the slash, so a bad value never reaches {@link dev.vantage.core.command.CommandLine}. */
    public final dev.vantage.setting.TextSetting commandPrefix = add(new dev.vantage.setting.TextSetting("Command prefix", "The character that starts a client command in chat (default .). One character; not a letter, a digit, a space or /", ".") {
        @Override
        protected String validate(String v) {
            String bad = dev.vantage.core.command.CommandLine.problemWith(v);
            if (bad != null) throw new Invalid(bad);
            return v;
        }
    });

    public final BoolSetting calm = add(new BoolSetting("Calm (beta)", "Beta: cosmetics stop flashing, pulsing and blinking and fade instead (low health, find pulse, hit marker, damage numbers, pay pop, kill effect, loot sparkle, pet hops, star twinkle)", false)).visibleIf(dev.vantage.core.Modules::betaUnlocked);

    /** Calm (beta) is on: only while beta is unlocked. */
    public static boolean calmOn() {
        var m = dev.vantage.core.Modules.get(ClickGuiModule.class);
        return m != null && dev.vantage.core.Modules.betaUnlocked() && m.calm.on();
    }

    @Override
    protected void onSettingChanged(dev.vantage.setting.Setting<?> setting) {
        if (setting == commandPrefix) dev.vantage.core.command.CommandLine.setPrefix(commandPrefix.text().charAt(0));
    }

    public ClickGuiModule() {
        super("ClickGUI", "The menu. Its key opens it", Category.CLIENT);
        onByDefault();
        bind = GLFW.GLFW_KEY_RIGHT_SHIFT; // was the 2 key until 1.0.7; a saved key is kept, only new installs get this
        dev.vantage.core.command.CommandLine.setPrefix(commandPrefix.text().charAt(0));
        command(dev.vantage.core.command.Command.named("prefix", "Change the character that starts a command").usableWhileOff()
                .arg(dev.vantage.core.command.Args.text("character", "one character: not a letter, a digit, a space or /"))
                .runs((a, r) -> {
                    String why = commandPrefix.trySet(a.get(0));
                    char p = dev.vantage.core.command.CommandLine.prefix();
                    if (why != null) r.error("The prefix was not changed: " + why);
                    else r.success("Commands now start with " + p + ". Type " + p + "help for the list, " + p + "prefix . to go back to the dot");
                }));
        add(new dev.vantage.setting.Header("About (beta)")).visibleIf(dev.vantage.core.Modules::betaUnlocked);
        add(new dev.vantage.setting.ActionSetting("About this build (beta)", "Version, the running jar's short SHA-256, the config folder", () -> mc.setScreen(new dev.vantage.gui.AboutScreen(mc.screen)))).visibleIf(dev.vantage.core.Modules::betaUnlocked);
        add(new dev.vantage.setting.ActionSetting("What's new (beta)", "The changelog bundled in this build (no web link)", () -> mc.setScreen(new dev.vantage.gui.ChangelogScreen(mc.screen)))).visibleIf(dev.vantage.core.Modules::betaUnlocked);
        add(new dev.vantage.setting.ActionSetting("Welcome screen (beta)", "Open the first-run welcome again", () -> mc.setScreen(new dev.vantage.gui.WelcomeScreen(mc.screen)))).visibleIf(dev.vantage.core.Modules::betaUnlocked);
    }
}
