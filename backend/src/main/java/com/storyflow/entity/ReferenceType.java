package com.storyflow.entity;

public enum ReferenceType {
    first_frame,
    last_frame,
    other;

    public static ReferenceType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return other;
        }
        String value = raw.trim();
        try {
            return ReferenceType.valueOf(value.toLowerCase());
        } catch (Exception ignored) {
            // fall through — legacy ordinal storage ("0"/"1"/"2")
        }
        try {
            int ordinal = Integer.parseInt(value);
            ReferenceType[] values = ReferenceType.values();
            if (ordinal >= 0 && ordinal < values.length) {
                return values[ordinal];
            }
        } catch (Exception ignored) {
            // ignore
        }
        return other;
    }
}
