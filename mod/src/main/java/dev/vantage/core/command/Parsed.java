package dev.vantage.core.command;

import java.util.ArrayList;
import java.util.List;

/** The values of the arguments read so far, in the order the command declares them. An optional argument that was left out is null. */
public final class Parsed {
    private final List<Object> values = new ArrayList<>(4);

    void add(Object value) {
        values.add(value);
    }

    public int size() {
        return values.size();
    }

    /** True when argument {@code index} was typed. */
    public boolean has(int index) {
        return index >= 0 && index < values.size() && values.get(index) != null;
    }

    /** The value of argument {@code index}, or null when it was left out. The caller knows the type its command declared. */
    @SuppressWarnings("unchecked") // each command reads its own arguments with the types it declared
    public <T> T get(int index) {
        return index < values.size() ? (T) values.get(index) : null;
    }
}
