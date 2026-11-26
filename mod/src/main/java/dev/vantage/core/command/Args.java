package dev.vantage.core.command;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** The argument types commands are built from. */
public final class Args {
    private Args() {}

    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    /**
     * Reads a plain decimal number: digits with an optional sign and point. "NaN", "Infinity", exponents and suffixes such as "1f"
     * are refused (Double.parseDouble accepts all of them, and "NaN" passes every range check because every comparison with it is false).
     */
    public static double number(String text) {
        if (!DECIMAL.matcher(text).matches()) throw new NumberFormatException("not a number: " + text);
        double d = Double.parseDouble(text);
        if (!Double.isFinite(d)) throw new NumberFormatException("not a finite number: " + text);
        return d;
    }

    public static boolean isNumber(String text) {
        return DECIMAL.matcher(text).matches();
    }

    public static boolean isWhole(String text) {
        return WHOLE.matcher(text).matches();
    }

    /** One word, anything. */
    public static Arg<String> word(String name, String description) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "a word"; }
            @Override public Parse<String> parse(String text, Parsed before) { return text.isEmpty() ? Parse.fail("it is empty") : Parse.ok(text); }
        };
    }

    /** The rest of the line, spaces included. Surrounding double quotes are dropped. */
    public static Arg<String> text(String name, String description) {
        return new RestText(name, description, List.of());
    }

    /** The rest of the line, with words offered for its first word (sub-commands). */
    public static Arg<String> text(String name, String description, List<String> firstWords) {
        return new RestText(name, description, firstWords);
    }

    private static final class RestText extends Arg<String> {
        private final List<String> firstWords;

        RestText(String name, String description, List<String> firstWords) {
            super(name, description);
            this.firstWords = firstWords;
        }

        @Override public boolean rest() { return true; }
        @Override public String expects() { return "text, spaces allowed"; }
        @Override public Parse<String> parse(String text, Parsed before) { return Parse.ok(text); }

        @Override public List<String> suggest(String partial, Parsed before) {
            for (int i = 0; i < partial.length(); i++) if (Character.isWhitespace(partial.charAt(i))) return List.of();
            return Names.startingWith(partial, firstWords);
        }
    }

    /** A whole number from min to max. */
    public static Arg<Integer> integer(String name, String description, int min, int max) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "a whole number from " + min + " to " + max; }

            @Override public Parse<Integer> parse(String text, Parsed before) {
                if (!WHOLE.matcher(text).matches()) return Parse.fail("'" + text + "' is not a whole number");
                long v;
                try {
                    v = Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
                } catch (NumberFormatException e) {
                    return Parse.fail("'" + text + "' is far too large");
                }
                if (v < min) return Parse.fail(text + " is below the smallest allowed value, " + min);
                if (v > max) return Parse.fail(text + " is above the largest allowed value, " + max);
                return Parse.ok((int) v);
            }
        };
    }

    /** A number with decimals from min to max. */
    public static Arg<Double> decimal(String name, String description, double min, double max) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "a number from " + trim(min) + " to " + trim(max); }

            @Override public Parse<Double> parse(String text, Parsed before) {
                if (!DECIMAL.matcher(text).matches()) return Parse.fail("'" + text + "' is not a number");
                double v = Double.parseDouble(text);
                if (!Double.isFinite(v)) return Parse.fail("'" + text + "' is far too large");
                if (v < min) return Parse.fail(text + " is below the smallest allowed value, " + trim(min));
                if (v > max) return Parse.fail(text + " is above the largest allowed value, " + trim(max));
                return Parse.ok(v);
            }
        };
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** on or off (also true, false, yes, no). */
    public static Arg<Boolean> onOff(String name, String description) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "on or off"; }

            @Override public Parse<Boolean> parse(String text, Parsed before) {
                Boolean b = bool(text);
                return b == null ? Parse.fail("'" + text + "' is not on or off") : Parse.ok(b);
            }

            @Override public List<String> suggest(String partial, Parsed before) { return Names.startingWith(partial, List.of("on", "off")); }
        };
    }

    /** on, true, yes give true; off, false, no give false; anything else null. */
    public static Boolean bool(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> Boolean.TRUE;
            case "off", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** One of a fixed list, in any case; the value is the listed spelling. */
    public static Arg<String> oneOf(String name, String description, List<String> options) {
        return oneOf(name, description, () -> options);
    }

    /** One of a list that is read when it is needed (it may grow after the command was declared). */
    public static Arg<String> oneOf(String name, String description, Supplier<List<String>> options) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "one of: " + String.join(", ", options.get()); }

            @Override public Parse<String> parse(String text, Parsed before) {
                for (String o : options.get()) if (o.equalsIgnoreCase(text)) return Parse.ok(o);
                return Parse.fail("'" + text + "' is not one of the choices");
            }

            @Override public List<String> suggest(String partial, Parsed before) { return Names.startingWith(partial, options.get()); }
        };
    }

    /** A module, by stable id or display name without spaces. */
    public static Arg<ModuleRef> module(String name, String description, ModuleSource source) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "a module: its id (storage-esp) or its name without spaces (StorageESP)"; }

            @Override public Parse<ModuleRef> parse(String text, Parsed before) { return Names.find(text, source.modules(), "module", null); }

            @Override public List<String> suggest(String partial, Parsed before) { return Names.suggestNamed(partial, source.modules()); }
        };
    }

    /** A setting of the module named by the argument at {@code moduleIndex}. */
    public static Arg<SettingRef> setting(String name, String description, int moduleIndex) {
        return new Arg<>(name, description) {
            @Override public String expects() { return "a setting of that module: its id or its name without spaces"; }

            @Override public Parse<SettingRef> parse(String text, Parsed before) {
                ModuleRef m = before.get(moduleIndex);
                if (m == null) return Parse.fail("no module was named before it");
                return Names.find(text, m.settings(), "setting", m.name());
            }

            @Override public List<String> suggest(String partial, Parsed before) {
                ModuleRef m = before.get(moduleIndex);
                return m == null ? List.of() : Names.suggestNamed(partial, m.settings());
            }
        };
    }

    /** What the setting at {@code settingIndex} takes. It is the rest of the line, so a text setting can hold spaces. The value is the text; the command applies it. */
    public static Arg<String> value(String name, String description, int settingIndex) {
        return new Arg<>(name, description) {
            @Override public boolean rest() { return true; }
            @Override public String expects() { return "a value that fits the setting"; }

            @Override public String expects(Parsed before) {
                SettingRef s = before.get(settingIndex);
                return s == null ? expects() : s.expects();
            }

            @Override public Parse<String> parse(String text, Parsed before) {
                SettingRef s = before.get(settingIndex);
                if (s == null) return Parse.fail("no setting was named before it");
                String problem = s.problem(text);
                return problem == null ? Parse.ok(text) : Parse.fail(problem);
            }

            @Override public List<String> suggest(String partial, Parsed before) {
                SettingRef s = before.get(settingIndex);
                return s == null ? List.of() : s.suggest(partial);
            }
        };
    }
}
