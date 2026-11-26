package dev.vantage.core.command;

/** The result of reading one typed word: the value, or the reason in words why it cannot be used. */
public record Parse<T>(T value, String problem) {
    public static <T> Parse<T> ok(T value) {
        return new Parse<>(value, null);
    }

    public static <T> Parse<T> fail(String problem) {
        return new Parse<>(null, problem);
    }

    public boolean ok() {
        return problem == null;
    }
}
