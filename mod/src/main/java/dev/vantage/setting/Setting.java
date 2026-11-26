package dev.vantage.setting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonElement;
import dev.vantage.core.Ids;

/**
 * One value a module exposes: a name and description for the user, a default, rules for what it accepts, and a stable id under
 * which it is saved.
 *
 * Every way a value can arrive goes through the same two steps, so there is one place per type that decides what is acceptable:
 * {@link #validate} for a typed value (the menu, a command, code) and {@link #parse} plus {@link #validate} for a saved one (the
 * config file, a profile, a shared code). A value that is refused leaves the setting as it was.
 */
public abstract class Setting<T> {
    /** Thrown by {@link #validate} and {@link #parse} for a value the setting cannot take; the message is shown to the user. */
    public static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Invalid(String reason) {
            super(reason, null, false, false); // an expected refusal, not a bug: no stack trace needed
        }
    }

    public final String name;
    public final String description;
    private String id;
    protected T value;
    protected final T defaultValue;
    private BooleanSupplier visible = () -> true;
    private final List<Runnable> listeners = new ArrayList<>(1);
    private boolean advanced;
    private dev.vantage.core.Module owner;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.id = idFor(name);
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

    /** A header may be a row of dashes or an arrow: it is never saved, so any placeholder id will do. */
    private static String idFor(String name) {
        try {
            return Ids.of(name);
        } catch (IllegalArgumentException e) {
            return "unnamed";
        }
    }

    /** What saved files call this setting. Made from the name; never changes once released. */
    public String id() {
        return id;
    }

    /** For a setting whose display name changed after release: the id stays what it was. */
    @SuppressWarnings("unchecked") // returns this as the caller's own type, for chaining at the field declaration
    public <S extends Setting<T>> S withId(String fixedId) {
        if (!Ids.valid(fixedId)) throw new IllegalArgumentException("bad setting id '" + fixedId + "'");
        id = fixedId;
        return (S) this;
    }

    /** Set by the module when the setting is added to it. */
    public void ownedBy(dev.vantage.core.Module module) {
        owner = module;
    }

    public dev.vantage.core.Module owner() {
        return owner;
    }

    /** The value it starts with (for the guide's settings tables). */
    public T defaultValue() {
        return defaultValue;
    }

    public T get() {
        return value;
    }

    /**
     * Checks a new value and returns the one to store, which may be adjusted (a number snapped to its step and kept inside its
     * range). Throws {@link Invalid} with a reason when the value cannot be used at all.
     */
    protected T validate(T v) {
        if (v == null) throw new Invalid("no value");
        return v;
    }

    /** Reads a saved value. Throws {@link Invalid} when the JSON is not what this type saves. */
    protected abstract T parse(JsonElement e);

    /** Sets a value from the menu, a command or code. A refused value changes nothing; use {@link #trySet} to learn why. */
    public void set(T v) {
        trySet(v);
    }

    /** Like {@link #set}, and says what was wrong: null when the value was accepted, otherwise the reason. */
    public String trySet(T v) {
        T ok;
        try {
            ok = validate(v);
        } catch (Invalid e) {
            return e.getMessage();
        }
        if (ok.equals(value)) return null;
        value = ok;
        applied();
        for (Runnable r : listeners) r.run();
        if (owner != null) owner.settingChanged(this);
        dev.vantage.core.Config.markDirty();
        return null;
    }

    /**
     * Takes a saved value. Returns null when it was accepted (or when there was none, which leaves the default), otherwise the
     * reason it was refused; the setting then keeps what it had. Loading does not run the {@link #onChange} listeners (a module
     * is not running yet while its config is read) and does not mark the config as changed; the module's settingChanged hook is
     * called so it can rebuild what it derives from the value.
     */
    public final String tryLoad(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        try {
            value = validate(parse(e));
        } catch (Invalid x) {
            return x.getMessage();
        } catch (RuntimeException x) {
            return "unreadable value";
        }
        applied();
        if (owner != null) owner.settingChanged(this);
        return null;
    }

    /** Takes a saved value and ignores one that is refused. Callers that can tell the user use {@link #tryLoad}. */
    public final void load(JsonElement e) {
        tryLoad(e);
    }

    /** Called after the value changed by any route, for types that keep something derived from it. */
    protected void applied() {}

    public void reset() {
        set(defaultValue);
    }

    public boolean isDefault() {
        return value.equals(defaultValue);
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleIf(BooleanSupplier b) {
        visible = b;
        return (S) this;
    }

    /** Runs after every change made with set (not while the config is loaded). Several listeners add up. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S onChange(Runnable r) {
        listeners.add(r);
        return (S) this;
    }

    /** Marks the setting as one most users never need; menus keep such settings folded away until asked. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S advanced() {
        advanced = true;
        return (S) this;
    }

    public boolean isAdvanced() {
        return advanced;
    }

    private BooleanSupplier stored = () -> true;

    /** The value is only written to config.json, saved configs and shared configs while this holds (a beta setting is not written for a locked user). */
    public void storedIf(BooleanSupplier b) {
        stored = b;
    }

    public boolean stored() {
        return stored.getAsBoolean();
    }

    public boolean visible() {
        return visible.getAsBoolean();
    }

    public abstract JsonElement save();
}
