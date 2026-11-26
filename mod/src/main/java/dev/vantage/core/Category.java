package dev.vantage.core;

public enum Category {
    FINDER("Finder"), RENDER("Render"), COSMETIC("Cosmetic"), DONUT("Donut"), CLIENT("Client");

    public final String title;

    Category(String title) {
        this.title = title;
    }
}
