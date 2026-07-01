package com.example.collections.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DailySnapshot {
    private String businessDate;
    private String sourceFile;
    private Map<String, List<AccountRecord>> accountsByMember = new LinkedHashMap<>();
    private long totalLines;
    private long parsedLines;
    private long rejectedLines;

    public DailySnapshot() { }

    public DailySnapshot(String businessDate, String sourceFile, Map<String, List<AccountRecord>> accountsByMember,
                         long totalLines, long parsedLines, long rejectedLines) {
        this.businessDate = businessDate;
        this.sourceFile = sourceFile;
        setAccountsByMember(accountsByMember);
        this.totalLines = totalLines;
        this.parsedLines = parsedLines;
        this.rejectedLines = rejectedLines;
    }

    public String getBusinessDate() { return businessDate; }
    public void setBusinessDate(String businessDate) { this.businessDate = businessDate; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public Map<String, List<AccountRecord>> getAccountsByMember() { return accountsByMember; }
    public void setAccountsByMember(Map<String, List<AccountRecord>> accountsByMember) {
        this.accountsByMember = accountsByMember == null ? new LinkedHashMap<>()
                : Collections.unmodifiableMap(new LinkedHashMap<>(accountsByMember));
    }
    public long getTotalLines() { return totalLines; }
    public void setTotalLines(long totalLines) { this.totalLines = totalLines; }
    public long getParsedLines() { return parsedLines; }
    public void setParsedLines(long parsedLines) { this.parsedLines = parsedLines; }
    public long getRejectedLines() { return rejectedLines; }
    public void setRejectedLines(long rejectedLines) { this.rejectedLines = rejectedLines; }
    public long getMemberCount() { return accountsByMember.size(); }
    public long getAccountCount() { return accountsByMember.values().stream().mapToLong(List::size).sum(); }
}
