package dev.vantage.core;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.command.Arg;
import dev.vantage.core.command.Args;
import dev.vantage.core.command.Command;
import dev.vantage.core.command.Commands;
import dev.vantage.core.command.Help;
import dev.vantage.core.command.ModuleRef;
import dev.vantage.core.command.ModuleSource;
import dev.vantage.core.command.Names;
import dev.vantage.core.command.Parse;
import dev.vantage.core.command.Parsed;
import dev.vantage.core.command.Reply;
import dev.vantage.core.command.ServerCommands;
import dev.vantage.core.command.SettingRef;
import dev.vantage.core.event.Events;
import dev.vantage.core.event.TickEvent;
import dev.vantage.core.event.WorldLeaveEvent;
import dev.vantage.core.notify.Level;
import dev.vantage.core.notify.Notify;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.IntSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.Setting;
import dev.vantage.setting.TextSetting;
import net.minecraft.client.Minecraft;

/**
 * The commands that belong to the client itself (help, modules, toggle, set, reset, version, config, seed), the bridge between the
 * module and setting classes and the game-free command system, the one place answers are shown, and the wiring of
 * {@link ServerCommands} to the game. Commands of a module are declared by the module; the friend commands by {@link Friends}'s owner.
 */
public final class CoreCommands implements Events.Owner {
    private static final CoreCommands OWNER = new CoreCommands();
    private static final Events.Group GROUP = new Events.Group(OWNER);

    private CoreCommands() {}

    @Override
    public String ownerName() {
        return "commands";
    }

    @Override
    public int ownerRank() {
        return 0;
    }

    /** Where every command answer is shown: the chat box. Through {@link Notify#reply}: an answer to what the user typed is never rate limited, it only gets the level's look. */
    private static final class ChatReply implements Reply {
        @Override public void info(String text) { Notify.reply(Level.INFO, text); }
        @Override public void success(String text) { Notify.reply(Level.SUCCESS, text); }
        @Override public void error(String text) { Notify.reply(Level.ERROR, text); }
    }

    /** The modules a command may name: the visible ones that are not parts of another module. */
    public static final ModuleSource MODULES = () -> {
        List<ModuleRef> out = new ArrayList<>();
        for (Module m : Modules.all()) if (m.owner == null) out.add(new ModuleAdapter(m));
        return out;
    };

    private record ModuleAdapter(Module m) implements ModuleRef {
        @Override public String id() { return m.id; }
        @Override public String name() { return m.name; }
        @Override public String description() { return m.description; }
        @Override public String category() { return m.category.title; }
        @Override public boolean on() { return m.isEnabled(); }
        @Override public void setOn(boolean on) { m.setEnabled(on); }

        @Override public List<SettingRef> settings() {
            List<SettingRef> out = new ArrayList<>();
            for (Setting<?> s : m.settings) if (supported(s) && s.visible() && s.stored()) out.add(new SettingAdapter(s));
            return out;
        }
    }

    private static boolean supported(Setting<?> s) {
        return s instanceof BoolSetting || s instanceof IntSetting || s instanceof NumberSetting || s instanceof ModeSetting || s instanceof TextSetting;
    }

    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private record SettingAdapter(Setting<?> s) implements SettingRef {
        @Override public String id() { return s.id(); }
        @Override public String name() { return s.name; }
        @Override public String description() { return s.description; }

        @Override public String expects() {
            if (s instanceof BoolSetting) return "on or off";
            if (s instanceof IntSetting i) return "a whole number from " + i.min + " to " + i.max;
            if (s instanceof NumberSetting n) return "a number from " + plain(n.min) + " to " + plain(n.max) + " (it is rounded to steps of " + plain(n.step) + ")";
            if (s instanceof ModeSetting m) return "one of: " + String.join(", ", m.modes);
            return "text";
        }

        @Override public String current() {
            if (s instanceof BoolSetting b) return b.on() ? "on" : "off";
            if (s instanceof NumberSetting n) return n.text();
            return String.valueOf(s.get());
        }

        @Override public String problem(String text) {
            if (s instanceof BoolSetting) return Args.bool(text) == null ? "'" + text + "' is not on or off" : null;
            if (s instanceof IntSetting) return Args.isWhole(text) ? (whole(text) == null ? "'" + text + "' is far too large" : null) : "'" + text + "' is not a whole number";
            if (s instanceof NumberSetting) return Args.isNumber(text) && Double.isFinite(Double.parseDouble(text)) ? null : "'" + text + "' is not a number";
            if (s instanceof ModeSetting m) return mode(m, text) == null ? "'" + text + "' is not one of the choices" : null;
            return null;
        }

        private static Integer whole(String text) {
            try {
                return Integer.parseInt(text.startsWith("+") ? text.substring(1) : text);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String mode(ModeSetting m, String text) {
            for (String o : m.modes) if (o.equalsIgnoreCase(text)) return o;
            return null;
        }

        @Override public String apply(String text) {
            String bad = problem(text);
            if (bad != null) return bad;
            if (s instanceof BoolSetting b) return b.trySet(Args.bool(text));
            if (s instanceof IntSetting i) return i.trySet(whole(text));
            if (s instanceof NumberSetting n) return n.trySet(Double.parseDouble(text));
            if (s instanceof ModeSetting m) return m.trySet(mode(m, text));
            return ((TextSetting) s).trySet(text);
        }

        @Override public List<String> suggest(String partial) {
            if (s instanceof BoolSetting) return Names.startingWith(partial, List.of("on", "off"));
            if (s instanceof ModeSetting m) return Names.startingWith(partial, m.modes);
            return List.of();
        }

        @Override public void reset() { s.reset(); }
        @Override public boolean isDefault() { return s.isDefault(); }
    }

    /** Declares the core commands, hooks answers to the chat box and the server-command gate to the game. Once, before the modules are created. */
    public static void register() {
        if (GROUP.declared() > 0) return;
        Commands reg = Commands.main();
        reg.setReply(new ChatReply());
        Minecraft mc = Minecraft.getInstance();
        ServerCommands.install(t -> mc.player.connection.sendCommand(t), () -> mc.level != null && mc.player != null && mc.getConnection() != null);
        GROUP.listen(TickEvent.class, e -> ServerCommands.tick());
        GROUP.listen(WorldLeaveEvent.class, e -> ServerCommands.clear());
        GROUP.attach();

        reg.register(Command.named("help", "List every command, or explain one")
                .arg(Args.oneOf("command", "the command to explain", CoreCommands::commandWords).optional())
                .runs((a, r) -> r.info(a.has(0) ? Help.explain(reg.find(a.get(0))) : Help.listing(reg))));
        reg.register(Command.named("modules", "List the modules with their on/off state")
                .arg(Args.oneOf("category", "only this category", CoreCommands::categories).optional())
                .runs((a, r) -> r.info(moduleList(a.get(0)))));
        reg.register(Command.named("toggle", "Switch a module on or off")
                .arg(Args.module("module", "the module to switch", MODULES))
                .runs((a, r) -> toggle(a.get(0), r)));
        reg.register(Command.named("set", "Change a setting of a module")
                .arg(Args.module("module", "the module the setting belongs to", MODULES))
                .arg(Args.setting("setting", "the setting to change", 0))
                .arg(Args.value("value", "the new value; what fits depends on the setting", 1))
                .note("Names: the stable id always works (storage-esp, menu-size). The display name without spaces works too, in any case (storageesp, StorageESP, MenuSize).")
                .note("Only settings that are shown in the menu and can be typed are offered: on/off, numbers, one of a list, text. Colours, keys and lists are set in the menu.")
                .note("A value that does not fit is refused with the reason, and nothing changes. Numbers outside the range are moved inside it.")
                .runs((a, r) -> set(a.get(0), a.get(1), a.get(2), r)));
        reg.register(Command.named("reset", "Put one setting, or every setting of a module, back to its default")
                .arg(Args.module("module", "the module", MODULES))
                .arg(Args.setting("setting", "the setting; leave it out to reset all of them", 0).optional())
                .note("Module and setting names are written like in the set command: the id, or the name without spaces.")
                .runs((a, r) -> reset(a.get(0), a.get(1), r)));
        reg.register(Command.named("version", "Show the Vantage and Minecraft versions")
                .runs((a, r) -> r.info(Vantage.NAME + " " + versionOf("vantage") + " for Minecraft " + versionOf("minecraft"))));
        reg.register(dev.vantage.core.hud.HudCommand.command());
        reg.register(debugCommand());
        reg.register(Profiles.asCommand());
        reg.register(SeedCheck.asCommand());
    }

    /** The words {@code debug} takes that are not a source. */
    private static final List<String> DEBUG_WORDS = List.of("list", "off");

    /** A module (id or name without spaces) or a core source; the value is the source name the log knows it by, or "list" / "off". */
    private static Arg<String> debugTarget() {
        return new Arg<>("source", "a module, a core source such as chunks, or list / off") {
            @Override public String expects() { return "a module (its id or its name without spaces), a core source (chunks, ...), list or off"; }

            @Override public Parse<String> parse(String text, Parsed before) {
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                if (DEBUG_WORDS.contains(lower)) return Parse.ok(lower);
                Parse<ModuleRef> m = Names.find(text, MODULES.modules(), "module", null);
                if (m.ok()) return Parse.ok(m.value().id());
                if (dev.vantage.core.log.Log.hub().knows(lower)) return Parse.ok(lower);
                return Parse.fail(m.problem().replace("no module called", "no module or core source called"));
            }

            @Override public List<String> suggest(String partial, Parsed before) {
                List<String> out = new ArrayList<>(Names.startingWith(partial, DEBUG_WORDS));
                out.addAll(Names.suggestNamed(partial, MODULES.modules()));
                for (String s : Names.startingWith(partial, dev.vantage.core.log.Log.hub().sourceNames())) if (!out.contains(s)) out.add(s);
                return out;
            }
        };
    }

    private static Command debugCommand() {
        return Command.named("debug", "Switch debug logging on or off for one module or core source")
                .arg(debugTarget())
                .arg(Args.onOff("state", "on or off").optional())
                .note("debug <module> on|off  writes that module's debug lines to config/vantage/logs/vantage.log. debug list shows what is on, debug off switches everything off.")
                .note("Not saved: debug is off again at the next start. Core sources: " + String.join(", ", dev.vantage.core.log.Log.hub().sourceNames()) + ".")
                .runs((a, r) -> {
                    String target = a.get(0);
                    var hub = dev.vantage.core.log.Log.hub();
                    if (target.equals("list")) {
                        List<String> on = hub.debugSources();
                        r.info(on.isEmpty() ? "Debug is off everywhere" : "Debug is on for: " + String.join(", ", on));
                    } else if (target.equals("off") && !a.has(1)) {
                        int n = hub.allDebugOff();
                        r.success(n == 0 ? "Debug was already off everywhere" : "Debug is off again for " + n + (n == 1 ? " source" : " sources"));
                    } else if (!a.has(1)) {
                        r.error("Say on or off: .debug " + target + " on");
                    } else {
                        boolean on = a.get(1);
                        hub.setDebug(target, on);
                        r.success("Debug for " + target + " is now " + (on ? "on" : "off"));
                    }
                });
    }

    private static String versionOf(String modId) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private static List<String> commandWords() {
        List<String> out = new ArrayList<>();
        for (Command c : Commands.main().available()) {
            out.add(c.name);
            out.addAll(c.aliases());
        }
        return out;
    }

    private static List<String> categories() {
        List<String> out = new ArrayList<>();
        for (Category c : Category.values()) out.add(c.title);
        return out;
    }

    private static String moduleList(String category) {
        StringBuilder sb = new StringBuilder();
        for (Category c : Category.values()) {
            if (category != null && !c.title.equals(category)) continue;
            StringBuilder line = new StringBuilder();
            int n = 0;
            for (ModuleRef m : MODULES.modules()) {
                if (!m.category().equals(c.title)) continue;
                line.append(n++ == 0 ? "" : ", ").append(m.name()).append(m.on() ? " [on]" : " [off]");
            }
            if (n > 0) sb.append(sb.length() == 0 ? "" : "\n").append(c.title).append(": ").append(line);
        }
        return sb.length() == 0 ? "No modules" : sb.toString();
    }

    private static void toggle(ModuleRef m, Reply r) {
        boolean was = m.on();
        m.setOn(!was);
        if (m.on() == was) r.error(m.name() + " could not be switched " + (was ? "off" : "on"));
        else r.success(m.name() + " is now " + (m.on() ? "on" : "off"));
    }

    private static void set(ModuleRef m, SettingRef s, String text, Reply r) {
        String why = s.apply(text);
        if (why != null) {
            r.error(m.name() + " " + s.name() + " was not changed: " + why);
            return;
        }
        String now = s.current();
        boolean same = now.equalsIgnoreCase(text) || (Args.isNumber(text) && Args.isNumber(now) && Double.parseDouble(text) == Double.parseDouble(now));
        r.success(m.name() + " " + s.name() + " is now " + now + (same ? "" : " (the closest value it accepts)"));
    }

    private static void reset(ModuleRef m, SettingRef s, Reply r) {
        if (s != null) {
            s.reset();
            r.success(m.name() + " " + s.name() + " is back to " + s.current());
            return;
        }
        int n = 0;
        for (SettingRef each : m.settings()) {
            if (each.isDefault()) continue;
            // consent and update choices are never rewritten by a bulk reset: they have to be changed one by one, on purpose
            if (m.name().equalsIgnoreCase("ClickGui") || m.name().equalsIgnoreCase("Click GUI")) {
                String sn = each.name();
                if (sn.equalsIgnoreCase("Presence") || sn.equalsIgnoreCase("Auto update") || sn.equalsIgnoreCase("Check for updates")) continue;
            }
            each.reset();
            n++;
        }
        r.success(n == 0 ? m.name() + " was already on its defaults" : "Reset " + n + (n == 1 ? " setting" : " settings") + " of " + m.name() + " to the defaults");
    }
}
