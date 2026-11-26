package dev.vantage.core.command;

import java.util.ArrayList;
import java.util.List;

/** Stand-ins for modules and settings, and a reply that writes everything down. */
final class Fakes {
    private Fakes() {}

    static final class Recorder implements Reply {
        final List<String> lines = new ArrayList<>();

        @Override public void info(String text) { lines.add("info: " + text); }
        @Override public void success(String text) { lines.add("success: " + text); }
        @Override public void error(String text) { lines.add("error: " + text); }

        String last() { return lines.get(lines.size() - 1); }
    }

    /** A setting that takes a whole number between 1 and 10, and says what it was given. */
    static final class FakeSetting implements SettingRef {
        final String id, name;
        final List<String> modes;
        String value = "5";
        String applied;

        FakeSetting(String id, String name, String... modes) {
            this.id = id;
            this.name = name;
            this.modes = List.of(modes);
        }

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public String description() { return "the " + name; }
        @Override public String expects() { return modes.isEmpty() ? "a whole number from 1 to 10" : "one of: " + String.join(", ", modes); }
        @Override public String current() { return value; }

        @Override public String problem(String text) {
            if (!modes.isEmpty()) return Names.startingWith(text, modes).contains(text) || modes.stream().anyMatch(m -> m.equalsIgnoreCase(text)) ? null : "'" + text + "' is not one of the choices";
            return Args.isWhole(text) ? null : "'" + text + "' is not a whole number";
        }

        @Override public String apply(String text) {
            String bad = problem(text);
            if (bad != null) return bad;
            if (modes.isEmpty() && Integer.parseInt(text) > 10) return "too big";
            applied = text;
            value = text;
            return null;
        }

        @Override public List<String> suggest(String partial) { return Names.startingWith(partial, modes); }
        @Override public void reset() { value = "5"; }
        @Override public boolean isDefault() { return value.equals("5"); }
    }

    static final class FakeModule implements ModuleRef {
        final String id, name;
        boolean on;
        final List<FakeSetting> settings = new ArrayList<>();

        FakeModule(String id, String name, FakeSetting... s) {
            this.id = id;
            this.name = name;
            settings.addAll(List.of(s));
        }

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public String description() { return "the " + name + " module"; }
        @Override public String category() { return "Render"; }
        @Override public boolean on() { return on; }
        @Override public void setOn(boolean v) { on = v; }
        @Override public List<FakeSetting> settings() { return settings; }
    }

    static final FakeModule RADAR = new FakeModule("radar", "Radar", new FakeSetting("range", "Range"), new FakeSetting("style", "Style", "Dots", "Arrows", "Icons"));
    static final FakeModule STORAGE = new FakeModule("storage-esp", "Storage ESP", new FakeSetting("box-colour", "Box Colour"), new FakeSetting("box_colour", "Box  colour"), new FakeSetting("fill", "Fill"));
    static final ModuleSource SOURCE = () -> List.of(RADAR, STORAGE);

    /** A registry with toggle, set, ranged numbers and a rest argument, the shapes real commands use. */
    static Commands registry() {
        Commands c = new Commands();
        c.register(Command.named("toggle", "Switch a module").arg(Args.module("module", "the module", SOURCE)).runs((a, r) -> {
            ModuleRef m = a.get(0);
            m.setOn(!m.on());
            r.success(m.name() + (m.on() ? " on" : " off"));
        }));
        c.register(Command.named("set", "Change a setting").alias("s")
                .arg(Args.module("module", "the module", SOURCE)).arg(Args.setting("setting", "the setting", 0)).arg(Args.value("value", "the value", 1))
                .note("Names are written as ids or without spaces.")
                .runs((a, r) -> {
                    String why = a.<SettingRef>get(1).apply(a.get(2));
                    if (why == null) r.success("set"); else r.error(why);
                }));
        c.register(Command.named("go", "Go somewhere").arg(Args.integer("x", "x", -100, 100)).arg(Args.decimal("speed", "speed", 0.5, 3)).arg(Args.onOff("fast", "fast mode").optional())
                .runs((a, r) -> r.info("go " + a.get(0) + " " + a.get(1) + " " + a.get(2))));
        c.register(Command.named("say", "Say something").arg(Args.word("who", "who")).arg(Args.text("what", "the words")).runs((a, r) -> r.info(a.get(0) + ": " + a.get(1))));
        c.register(Command.named("mode", "Pick one").arg(Args.oneOf("kind", "kind", List.of("fast", "slow", "steady")).optional()).runs((a, r) -> r.info("mode " + a.get(0))));
        c.register(Command.named("friend", "A friend").runs((a, r) -> r.info("friend")));
        return c;
    }
}
