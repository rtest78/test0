public String getSpecialHandlingReasons() {

    String value = responseValue("specialHandlingReasons");

    if (value == null || value.trim().isEmpty()) {
        value = responseValue("specialHandlingReason");
    }

    return normalizeListValue(value);
}

public String getChannel() {

    String value = responseValue("channel");

    return normalizeListValue(value);
}

private String normalizeListValue(String value) {

    if (value == null) {
        return "";
    }

    String normalized = value.trim();

    if (normalized.isEmpty()) {
        return "";
    }

    /*
     * Converts:
     * ["SENTRY"]
     *
     * to:
     * SENTRY
     */
    if (normalized.startsWith("[")
            && normalized.endsWith("]")) {

        normalized = normalized.substring(
                1,
                normalized.length() - 1
        );
    }

    normalized = normalized
            .replace("\\\"", "\"")
            .replace("\"", "")
            .trim();

    return normalized;
}
