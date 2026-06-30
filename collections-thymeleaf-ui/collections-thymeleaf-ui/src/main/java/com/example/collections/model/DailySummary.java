package com.example.collections.model;

public record DailySummary(
        String businessDate,
        String sourceFile,
        long memberCount,
        long accountCount,
        long totalLines,
        long parsedLines,
        long rejectedLines,
        long newAccounts,
        long missingAccounts,
        long changedAccounts
) {
}
