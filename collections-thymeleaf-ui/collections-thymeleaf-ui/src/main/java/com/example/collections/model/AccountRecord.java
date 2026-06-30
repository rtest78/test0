package com.example.collections.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AccountRecord(
        String memberNumber,
        String accountNumber,
        Map<String, String> requestFields,
        Map<String, String> responseFields,
        String sourceFile,
        String businessDate,
        long sourceLine
) {
    public AccountRecord {
        requestFields = Collections.unmodifiableMap(new LinkedHashMap<>(requestFields));
        responseFields = Collections.unmodifiableMap(new LinkedHashMap<>(responseFields));
    }

    public String requestValue(String field) {
        return requestFields.getOrDefault(field, "");
    }

    public String responseValue(String field) {
        return responseFields.getOrDefault(field, "");
    }
}
