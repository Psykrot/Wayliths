package com.kotacraft.wayliths;

public enum WaylithVisibility {
    PRIVATE,
    PUBLIC,
    SERVER;

    public static WaylithVisibility fromConfig(String value) {
        if (value == null) return PRIVATE;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return PRIVATE;
        }
    }
}
