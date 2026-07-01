package com.example.collections.model;

public class DailySummary {
    private String businessDate;
    private String sourceFile;
    private long memberCount;
    private long accountCount;
    private long totalLines;
    private long parsedLines;
    private long rejectedLines;
    private long newAccounts;
    private long missingAccounts;
    private long changedAccounts;

    public DailySummary() { }

    public DailySummary(String businessDate, String sourceFile, long memberCount, long accountCount,
                        long totalLines, long parsedLines, long rejectedLines, long newAccounts,
                        long missingAccounts, long changedAccounts) {
        this.businessDate = businessDate; this.sourceFile = sourceFile; this.memberCount = memberCount;
        this.accountCount = accountCount; this.totalLines = totalLines; this.parsedLines = parsedLines;
        this.rejectedLines = rejectedLines; this.newAccounts = newAccounts;
        this.missingAccounts = missingAccounts; this.changedAccounts = changedAccounts;
    }

    public String getBusinessDate() { return businessDate; } public void setBusinessDate(String v) { businessDate=v; }
    public String getSourceFile() { return sourceFile; } public void setSourceFile(String v) { sourceFile=v; }
    public long getMemberCount() { return memberCount; } public void setMemberCount(long v) { memberCount=v; }
    public long getAccountCount() { return accountCount; } public void setAccountCount(long v) { accountCount=v; }
    public long getTotalLines() { return totalLines; } public void setTotalLines(long v) { totalLines=v; }
    public long getParsedLines() { return parsedLines; } public void setParsedLines(long v) { parsedLines=v; }
    public long getRejectedLines() { return rejectedLines; } public void setRejectedLines(long v) { rejectedLines=v; }
    public long getNewAccounts() { return newAccounts; } public void setNewAccounts(long v) { newAccounts=v; }
    public long getMissingAccounts() { return missingAccounts; } public void setMissingAccounts(long v) { missingAccounts=v; }
    public long getChangedAccounts() { return changedAccounts; } public void setChangedAccounts(long v) { changedAccounts=v; }
}
