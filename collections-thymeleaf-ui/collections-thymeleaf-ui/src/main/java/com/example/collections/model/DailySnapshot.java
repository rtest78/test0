package com.example.collections.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DailySnapshot(
        String businessDate,
        String sourceFile,
        Map<String, List<AccountRecord>> accountsByMember,
        long totalLines,
        long parsedLines,
        long rejectedLines
) {
    public DailySnapshot {
        accountsByMember = Collections.unmodifiableMap(new LinkedHashMap<>(accountsByMember));
    }

    public long memberCount() {
        return accountsByMember.size();
    }

    public long accountCount() {
        return accountsByMember.values().stream().mapToLong(List::size).sum();
    }
}
