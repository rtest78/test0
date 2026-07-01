package com.example.collections.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AccountRecord {
    private String memberNumber;
    private String accountNumber;
    private Map<String, String> requestFields = new LinkedHashMap<>();
    private Map<String, String> responseFields = new LinkedHashMap<>();
    private String sourceFile;
    private String businessDate;
    private long sourceLine;

    public AccountRecord() { }

    public AccountRecord(String memberNumber, String accountNumber, Map<String, String> requestFields,
                         Map<String, String> responseFields, String sourceFile, String businessDate, long sourceLine) {
        this.memberNumber = memberNumber;
        this.accountNumber = accountNumber;
        setRequestFields(requestFields);
        setResponseFields(responseFields);
        this.sourceFile = sourceFile;
        this.businessDate = businessDate;
        this.sourceLine = sourceLine;
    }

    public String getMemberNumber() { return memberNumber; }
    public void setMemberNumber(String memberNumber) { this.memberNumber = memberNumber; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public Map<String, String> getRequestFields() { return requestFields; }
    public void setRequestFields(Map<String, String> requestFields) {
        this.requestFields = requestFields == null ? new LinkedHashMap<>()
                : Collections.unmodifiableMap(new LinkedHashMap<>(requestFields));
    }
    public Map<String, String> getResponseFields() { return responseFields; }
    public void setResponseFields(Map<String, String> responseFields) {
        this.responseFields = responseFields == null ? new LinkedHashMap<>()
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseFields));
    }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public String getBusinessDate() { return businessDate; }
    public void setBusinessDate(String businessDate) { this.businessDate = businessDate; }
    public long getSourceLine() { return sourceLine; }
    public void setSourceLine(long sourceLine) { this.sourceLine = sourceLine; }

    public String requestValue(String field) { return requestFields.getOrDefault(field, ""); }
    public String responseValue(String field) { return responseFields.getOrDefault(field, ""); }
}
