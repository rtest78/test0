package com.example.collections.model;

public record AccountDayView(
        AccountRecord account,
        ChangeType changeType
) {
    public enum ChangeType {
        UNCHANGED,
        NEW,
        MISSING,
        CHANGED
    }
}
