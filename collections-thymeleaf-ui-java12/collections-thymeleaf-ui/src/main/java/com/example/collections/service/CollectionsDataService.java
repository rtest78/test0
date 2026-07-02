package com.example.collections.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CollectionsDataService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, DailySnapshot> snapshotsByDate =
            new ConcurrentHashMap<String, DailySnapshot>();

    /*
     * Cache full day-to-day comparisons so the dashboard does not
     * repeatedly compare all 28,000+ accounts.
     */
    private final Map<String, List<AccountDayView>> comparisonCache =
            new ConcurrentHashMap<String, List<AccountDayView>>();

    @Value("${collections.data.directory:./data}")
    private String dataDirectory;

    @PostConstruct
    public void initialize() {

        try {
            reload();
        } catch (Exception ex) {
            System.err.println(
                    "Collections data initialization failed: "
                            + ex.getMessage()
            );

            ex.printStackTrace();
        }
    }

    /*
     * =========================================================
     * FILE LOADING
     * =========================================================
     */

    public synchronized void reload() throws IOException {

        Path folder = Path.of(dataDirectory);

        System.out.println(
                "Loading Collections files from: "
                        + folder.toAbsolutePath()
        );

        Files.createDirectories(folder);

        List<Path> files = discoverFiles(folder);

        System.out.println(
                "Files discovered: " + files.size()
        );

        Map<String, DailySnapshot> loaded =
                new TreeMap<String, DailySnapshot>();

        for (Path file : files) {

            System.out.println(
                    "Processing file: "
                            + file.toAbsolutePath()
            );

            parseFile(file, loaded);
        }

        snapshotsByDate.clear();
        snapshotsByDate.putAll(loaded);

        comparisonCache.clear();

        System.out.println(
                "Business dates loaded: "
                        + new TreeSet<String>(
                                snapshotsByDate.keySet()
                        )
        );

        Map<String, DailySnapshot> sorted =
                new TreeMap<String, DailySnapshot>(
                        snapshotsByDate
                );

        for (Map.Entry<String, DailySnapshot> entry
                : sorted.entrySet()) {

            DailySnapshot snapshot =
                    entry.getValue();

            System.out.println(
                    "Date="
                            + entry.getKey()
                            + ", members="
                            + snapshot.getMemberCount()
                            + ", accounts="
                            + snapshot.getAccountCount()
                            + ", source records="
                            + snapshot.getSourceRecordCount()
                            + ", source files="
                            + snapshot.getSourceFiles()
            );
        }
    }

    private List<Path> discoverFiles(Path folder)
            throws IOException {

        try (Stream<Path> stream = Files.walk(folder)) {

            return stream
                    .filter(Files::isRegularFile)
                    .filter(path ->
                            !path.getFileName()
                                    .toString()
                                    .startsWith(".")
                    )
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private void parseFile(
            Path file,
            Map<String, DailySnapshot> loaded)
            throws IOException {

        long lineNumber = 0;
        long successfulLines = 0;
        long failedLines = 0;

        try (BufferedReader reader =
                     Files.newBufferedReader(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            String line;

            while ((line = reader.readLine()) != null) {

                lineNumber++;

                if (line.trim().isEmpty()) {
                    continue;
                }

                try {

                    parseLine(
                            file,
                            lineNumber,
                            line,
                            loaded
                    );

                    successfulLines++;

                } catch (Exception ex) {

                    failedLines++;

                    System.err.println(
                            "Parse failed. File="
                                    + file.getFileName()
                                    + ", line="
                                    + lineNumber
                                    + ", error="
                                    + ex.getMessage()
                    );
                }
            }
        }

        System.out.println(
                "Completed file="
                        + file.getFileName()
                        + ", total lines="
                        + lineNumber
                        + ", successful="
                        + successfulLines
                        + ", failed="
                        + failedLines
        );
    }

    /*
     * Expected line:
     *
     * 2026-06-26,{"input":{...},"output":{...}}
     */
    private void parseLine(
            Path sourceFile,
            long lineNumber,
            String line,
            Map<String, DailySnapshot> loaded)
            throws IOException {

        int firstComma = line.indexOf(',');

        if (firstComma < 0) {
            throw new IllegalArgumentException(
                    "Business-date separator comma was not found"
            );
        }

        String businessDate =
                line.substring(0, firstComma).trim();

        String outerJsonText =
                line.substring(firstComma + 1).trim();

        if (businessDate.isEmpty()) {
            throw new IllegalArgumentException(
                    "Business date is empty"
            );
        }

        if (outerJsonText.isEmpty()) {
            throw new IllegalArgumentException(
                    "JSON content is empty"
            );
        }

        JsonNode outerNode =
                objectMapper.readTree(outerJsonText);

        JsonNode inputPayload =
                extractPayload(
                        outerNode,
                        "input"
                );

        JsonNode requestNode =
                findRequestNode(inputPayload);

        JsonNode customerDataNode =
                requestNode.path("customerData");

        if (!customerDataNode.isArray()) {

            throw new IllegalArgumentException(
                    "customerData was not found under "
                            + "collections.request.customerData"
            );
        }

        JsonNode outputPayload =
                extractPayload(
                        outerNode,
                        "output"
                );

        JsonNode responseNode =
                findResponseNode(outputPayload);

        boolean responseAvailable =
                isUsableNode(responseNode)
                        && responseNode.isObject()
                        && responseNode.size() > 0;

        /*
         * When the formal response wrapper is absent,
         * retain the complete output for display.
         */
        JsonNode responseForDisplay =
                responseAvailable
                        ? responseNode
                        : outputPayload;

        DailySnapshot snapshot =
                loaded.computeIfAbsent(
                        businessDate,
                        date -> new DailySnapshot(date)
                );

        snapshot.getSourceFiles().add(
                sourceFile.getFileName().toString()
        );

        Map<String, String> responseFields =
                flattenJson(responseForDisplay);

        for (JsonNode accountNode : customerDataNode) {

            String memberNumber =
                    getText(
                            accountNode,
                            "memberNumber"
                    );

            String accountNumber =
                    getText(
                            accountNode,
                            "accountNumber"
                    );

            if (memberNumber.isEmpty()) {

                System.err.println(
                        "Skipping record without memberNumber. "
                                + "File="
                                + sourceFile.getFileName()
                                + ", line="
                                + lineNumber
                );

                continue;
            }

            if (accountNumber.isEmpty()) {

                System.err.println(
                        "Skipping record without accountNumber. "
                                + "File="
                                + sourceFile.getFileName()
                                + ", line="
                                + lineNumber
                                + ", memberNumber="
                                + memberNumber
                );

                continue;
            }

            AccountRecord accountRecord =
                    new AccountRecord();

            accountRecord.setBusinessDate(
                    businessDate
            );

            accountRecord.setSourceFile(
                    sourceFile.getFileName().toString()
            );

            accountRecord.setSourceLine(
                    lineNumber
            );

            accountRecord.setMemberNumber(
                    memberNumber
            );

            accountRecord.setAccountNumber(
                    accountNumber
            );

            accountRecord.setRequestFields(
                    flattenJson(accountNode)
            );

            accountRecord.setResponseFields(
                    responseFields
            );

            accountRecord.setAccountJson(
                    prettyJson(accountNode)
            );

            accountRecord.setRequestJson(
                    prettyJson(requestNode)
            );

            accountRecord.setResponseJson(
                    prettyJson(responseForDisplay)
            );

            accountRecord.setResponseAvailable(
                    responseAvailable
            );

            snapshot.addAccount(accountRecord);
        }

        snapshot.setSourceRecordCount(
                snapshot.getSourceRecordCount() + 1
        );
    }

    /*
     * =========================================================
     * JSON EXTRACTION
     * =========================================================
     */

    private JsonNode extractPayload(
            JsonNode outerNode,
            String section)
            throws IOException {

        JsonNode sectionNode =
                outerNode.path(section);

        if (!isUsableNode(sectionNode)) {
            return objectMapper.createObjectNode();
        }

        JsonNode dataNode =
                sectionNode.path("data");

        if (!isUsableNode(dataNode)) {
            return sectionNode;
        }

        return parseNestedJson(dataNode);
    }

    private JsonNode parseNestedJson(
            JsonNode dataNode)
            throws IOException {

        if (!isUsableNode(dataNode)) {
            return objectMapper.createObjectNode();
        }

        if (dataNode.isObject()
                || dataNode.isArray()) {

            return dataNode;
        }

        if (!dataNode.isTextual()) {
            return dataNode;
        }

        String jsonText = dataNode.asText();

        if (jsonText == null
                || jsonText.trim().isEmpty()) {

            return objectMapper.createObjectNode();
        }

        JsonNode parsedNode =
                objectMapper.readTree(jsonText);

        int attempts = 0;

        while (parsedNode.isTextual()
                && attempts < 3) {

            String nestedText =
                    parsedNode.asText();

            if (nestedText == null
                    || nestedText.trim().isEmpty()) {

                break;
            }

            parsedNode =
                    objectMapper.readTree(
                            nestedText
                    );

            attempts++;
        }

        return parsedNode;
    }

    private JsonNode findRequestNode(
            JsonNode inputPayload) {

        JsonNode requestNode =
                inputPayload
                        .path("collections")
                        .path("request");

        if (isUsableNode(requestNode)) {
            return requestNode;
        }

        requestNode =
                inputPayload.path("request");

        if (isUsableNode(requestNode)) {
            return requestNode;
        }

        JsonNode recursive =
                findObjectRecursively(
                        inputPayload,
                        "request"
                );

        if (isUsableNode(recursive)) {
            return recursive;
        }

        return objectMapper.createObjectNode();
    }

    private JsonNode findResponseNode(
            JsonNode outputPayload) {

        if (!isUsableNode(outputPayload)) {
            return objectMapper.createObjectNode();
        }

        JsonNode responseNode =
                outputPayload
                        .path("collections")
                        .path("response");

        if (isUsableNode(responseNode)) {
            return responseNode;
        }

        responseNode =
                outputPayload.path("response");

        if (isUsableNode(responseNode)) {
            return responseNode;
        }

        JsonNode collectionsNode =
                outputPayload.path("collections");

        if (collectionsNode.isObject()
                && hasKnownResponseField(
                        collectionsNode
                )) {

            return collectionsNode;
        }

        if (outputPayload.isObject()
                && hasKnownResponseField(
                        outputPayload
                )) {

            return outputPayload;
        }

        responseNode =
                findObjectRecursively(
                        outputPayload,
                        "response"
                );

        if (isUsableNode(responseNode)) {
            return responseNode;
        }

        return objectMapper.createObjectNode();
    }

    private boolean hasKnownResponseField(
            JsonNode node) {

        return node.has("actionType")
                || node.has("channel")
                || node.has("exceptionMessage")
                || node.has("randomNumber")
                || node.has("version")
                || node.has("utilizationPercent")
                || node.has("accountStatus2");
    }

    private JsonNode findObjectRecursively(
            JsonNode node,
            String fieldName) {

        if (!isUsableNode(node)) {
            return objectMapper.createObjectNode();
        }

        if (node.isObject()) {

            JsonNode directMatch =
                    node.get(fieldName);

            if (isUsableNode(directMatch)) {
                return directMatch;
            }

            Iterator<Map.Entry<String, JsonNode>> fields =
                    node.fields();

            while (fields.hasNext()) {

                Map.Entry<String, JsonNode> field =
                        fields.next();

                JsonNode result =
                        findObjectRecursively(
                                field.getValue(),
                                fieldName
                        );

                if (isUsableNode(result)) {
                    return result;
                }
            }
        }

        if (node.isArray()) {

            for (JsonNode child : node) {

                JsonNode result =
                        findObjectRecursively(
                                child,
                                fieldName
                        );

                if (isUsableNode(result)) {
                    return result;
                }
            }
        }

        return objectMapper.createObjectNode();
    }

    private boolean isUsableNode(JsonNode node) {

        return node != null
                && !node.isMissingNode()
                && !node.isNull();
    }

    private Map<String, String> flattenJson(
            JsonNode node) {

        Map<String, String> values =
                new LinkedHashMap<String, String>();

        if (node == null
                || !node.isObject()) {

            return values;
        }

        node.fields().forEachRemaining(
                entry -> {

                    JsonNode value =
                            entry.getValue();

                    if (value == null
                            || value.isNull()) {

                        values.put(
                                entry.getKey(),
                                ""
                        );

                    } else if (value.isValueNode()) {

                        values.put(
                                entry.getKey(),
                                value.asText("")
                        );

                    } else {

                        values.put(
                                entry.getKey(),
                                value.toString()
                        );
                    }
                }
        );

        return values;
    }

    private String prettyJson(JsonNode node) {

        if (!isUsableNode(node)) {
            return "";
        }

        try {

            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node);

        } catch (Exception ex) {

            return node.toString();
        }
    }

    private String getText(
            JsonNode node,
            String fieldName) {

        if (node == null) {
            return "";
        }

        JsonNode valueNode =
                node.path(fieldName);

        if (!isUsableNode(valueNode)) {
            return "";
        }

        return valueNode.asText("");
    }

    /*
     * =========================================================
     * AMOUNT HELPERS
     * =========================================================
     */

    private BigDecimal parseAmount(String value) {

        if (value == null
                || value.trim().isEmpty()) {

            return BigDecimal.ZERO;
        }

        try {

            return new BigDecimal(
                    value
                            .replace(",", "")
                            .replace("$", "")
                            .trim()
            );

        } catch (NumberFormatException ex) {

            return BigDecimal.ZERO;
        }
    }

    private String formatAmount(
            BigDecimal amount) {

        if (amount == null) {
            return "0";
        }

        return amount
                .stripTrailingZeros()
                .toPlainString();
    }

    /*
     * =========================================================
     * DATE AND SNAPSHOT METHODS
     * =========================================================
     */

    public List<String> getAvailableDates() {

        List<String> dates =
                new ArrayList<String>(
                        snapshotsByDate.keySet()
                );

        Collections.sort(dates);

        return dates;
    }

    public String resolveDate(
            String requestedDate) {

        if (requestedDate != null
                && !requestedDate.trim().isEmpty()
                && snapshotsByDate.containsKey(
                        requestedDate.trim()
                )) {

            return requestedDate.trim();
        }

        List<String> dates =
                getAvailableDates();

        if (dates.isEmpty()) {
            return null;
        }

        return dates.get(
                dates.size() - 1
        );
    }

    private String getPreviousDate(
            String currentDate) {

        List<String> dates =
                getAvailableDates();

        int index =
                dates.indexOf(currentDate);

        if (index <= 0) {
            return null;
        }

        return dates.get(index - 1);
    }

    public DailySnapshot getSnapshot(
            String businessDate) {

        String resolvedDate =
                resolveDate(businessDate);

        if (resolvedDate == null) {
            return null;
        }

        return snapshotsByDate.get(
                resolvedDate
        );
    }

    /*
     * =========================================================
     * COMPARISON METHODS
     * =========================================================
     */

    public List<AccountDayView> findAccountDayViews(
            String businessDate,
            String memberNumber) {

        String currentDate =
                resolveDate(businessDate);

        if (currentDate == null) {
            return new ArrayList<AccountDayView>();
        }

        String normalizedMember =
                memberNumber == null
                        ? ""
                        : memberNumber.trim();

        /*
         * Exact member lookup avoids comparing all members.
         */
        if (!normalizedMember.isEmpty()) {

            return buildAccountDayViews(
                    currentDate,
                    normalizedMember
            );
        }

        List<AccountDayView> cached =
                comparisonCache.get(currentDate);

        if (cached != null) {
            return new ArrayList<AccountDayView>(cached);
        }

        List<AccountDayView> views =
                buildAccountDayViews(
                        currentDate,
                        null
                );

        comparisonCache.put(
                currentDate,
                new ArrayList<AccountDayView>(views)
        );

        return views;
    }

    private List<AccountDayView> buildAccountDayViews(
            String currentDate,
            String exactMemberNumber) {

        String previousDate =
                getPreviousDate(currentDate);

        DailySnapshot currentSnapshot =
                snapshotsByDate.get(currentDate);

        DailySnapshot previousSnapshot =
                previousDate == null
                        ? null
                        : snapshotsByDate.get(previousDate);

        Set<String> memberNumbers =
                new TreeSet<String>();

        if (exactMemberNumber != null
                && !exactMemberNumber.isEmpty()) {

            memberNumbers.add(exactMemberNumber);

        } else {

            if (currentSnapshot != null) {

                memberNumbers.addAll(
                        currentSnapshot
                                .getAccountsByMember()
                                .keySet()
                );
            }

            if (previousSnapshot != null) {

                memberNumbers.addAll(
                        previousSnapshot
                                .getAccountsByMember()
                                .keySet()
                );
            }
        }

        List<AccountDayView> views =
                new ArrayList<AccountDayView>();

        for (String memberNumber : memberNumbers) {

            Map<String, AccountRecord> currentAccounts =
                    getMemberAccounts(
                            currentSnapshot,
                            memberNumber
                    );

            Map<String, AccountRecord> previousAccounts =
                    getMemberAccounts(
                            previousSnapshot,
                            memberNumber
                    );

            Set<String> accountNumbers =
                    new TreeSet<String>();

            accountNumbers.addAll(
                    currentAccounts.keySet()
            );

            accountNumbers.addAll(
                    previousAccounts.keySet()
            );

            for (String accountNumber : accountNumbers) {

                AccountRecord currentAccount =
                        currentAccounts.get(accountNumber);

                AccountRecord previousAccount =
                        previousAccounts.get(accountNumber);

                AccountDayView view =
                        createAccountDayView(
                                currentDate,
                                previousDate,
                                currentAccount,
                                previousAccount
                        );

                views.add(view);
            }
        }

        views.sort(
                Comparator
                        .comparing(
                                view ->
                                        view.getAccount()
                                                .getMemberNumber(),
                                Comparator.nullsLast(
                                        String::compareTo
                                )
                        )
                        .thenComparing(
                                view ->
                                        view.getAccount()
                                                .getAccountNumber(),
                                Comparator.nullsLast(
                                        String::compareTo
                                )
                        )
        );

        return views;
    }

    private AccountDayView createAccountDayView(
            String currentDate,
            String previousDate,
            AccountRecord currentAccount,
            AccountRecord previousAccount) {

        AccountDayView view =
                new AccountDayView();

        view.setCurrentDate(currentDate);
        view.setPreviousDate(previousDate);
        view.setPreviousAccount(previousAccount);

        String previousBalanceText =
                previousAccount == null
                        ? ""
                        : previousAccount.getAccountBalance();

        String currentBalanceText =
                currentAccount == null
                        ? ""
                        : currentAccount.getAccountBalance();

        BigDecimal previousBalance =
                parseAmount(previousBalanceText);

        BigDecimal currentBalance =
                parseAmount(currentBalanceText);

        BigDecimal balanceDifference =
                currentBalance.subtract(
                        previousBalance
                );

        view.setPreviousBalance(
                previousBalanceText
        );

        view.setCurrentBalance(
                currentBalanceText
        );

        boolean balanceChanged =
                previousAccount != null
                        && currentAccount != null
                        && balanceDifference.compareTo(
                                BigDecimal.ZERO
                        ) != 0;

        view.setBalanceChanged(
                balanceChanged
        );

        view.setBalanceChange(
                balanceChanged
                        ? formatAmount(
                                balanceDifference
                        )
                        : ""
        );

        /*
         * Missing accounts use the previous day's channel.
         */
        if (currentAccount != null) {

            view.setChannel(
                    currentAccount.getChannel()
            );

        } else if (previousAccount != null) {

            view.setChannel(
                    previousAccount.getChannel()
            );
        }

        if (currentAccount != null
                && previousAccount == null) {

            view.setAccount(currentAccount);
            view.setChangeType(ChangeType.NEW);

        } else if (currentAccount == null
                && previousAccount != null) {

            view.setAccount(previousAccount);
            view.setChangeType(ChangeType.MISSING);

        } else {

            List<FieldChange> changedFields =
                    findChangedFields(
                            previousAccount,
                            currentAccount
                    );

            view.setChangedFields(changedFields);
            view.setChangedFieldCount(
                    changedFields.size()
            );

            if (!changedFields.isEmpty()) {

                view.setAccount(currentAccount);
                view.setChangeType(ChangeType.CHANGED);

            } else {

                view.setAccount(currentAccount);
                view.setChangeType(ChangeType.UNCHANGED);
            }
        }

        return view;
    }

    private List<FieldChange> findChangedFields(
            AccountRecord previous,
            AccountRecord current) {

        List<FieldChange> changes =
                new ArrayList<FieldChange>();

        if (previous == null || current == null) {
            return changes;
        }

        compareFieldMaps(
                "Request",
                previous.getRequestFields(),
                current.getRequestFields(),
                changes
        );

        compareFieldMaps(
                "Response",
                previous.getResponseFields(),
                current.getResponseFields(),
                changes
        );

        return changes;
    }

    private void compareFieldMaps(
            String section,
            Map<String, String> previousFields,
            Map<String, String> currentFields,
            List<FieldChange> changes) {

        Set<String> fieldNames =
                new TreeSet<String>();

        if (previousFields != null) {
            fieldNames.addAll(
                    previousFields.keySet()
            );
        }

        if (currentFields != null) {
            fieldNames.addAll(
                    currentFields.keySet()
            );
        }

        for (String fieldName : fieldNames) {

            String previousValue =
                    previousFields == null
                            ? ""
                            : previousFields.getOrDefault(
                                    fieldName,
                                    ""
                            );

            String currentValue =
                    currentFields == null
                            ? ""
                            : currentFields.getOrDefault(
                                    fieldName,
                                    ""
                            );

            if (!Objects.equals(
                    previousValue,
                    currentValue
            )) {

                FieldChange change =
                        new FieldChange();

                change.setSection(section);
                change.setFieldName(fieldName);
                change.setPreviousValue(previousValue);
                change.setCurrentValue(currentValue);

                changes.add(change);
            }
        }
    }

    private Map<String, AccountRecord> getMemberAccounts(
            DailySnapshot snapshot,
            String memberNumber) {

        if (snapshot == null) {
            return Collections.emptyMap();
        }

        Map<String, AccountRecord> accounts =
                snapshot
                        .getAccountsByMember()
                        .get(memberNumber);

        if (accounts == null) {
            return Collections.emptyMap();
        }

        return accounts;
    }

    /*
     * =========================================================
     * COUNTS AND SUMMARIES
     * =========================================================
     */

    public ComparisonSummary getComparisonSummary(
            String businessDate,
            String memberNumber) {

        List<AccountDayView> views =
                findAccountDayViews(
                        businessDate,
                        memberNumber
                );

        return summarizeViews(views);
    }

    private ComparisonSummary summarizeViews(
            List<AccountDayView> views) {

        ComparisonSummary summary =
                new ComparisonSummary();

        BigDecimal totalBalanceChange =
                BigDecimal.ZERO;

        for (AccountDayView view : views) {

            summary.setTotalAccountCount(
                    summary.getTotalAccountCount() + 1
            );

            if (view.getChangeType()
                    == ChangeType.NEW) {

                summary.setNewAccountCount(
                        summary.getNewAccountCount() + 1
                );
            }

            if (view.getChangeType()
                    == ChangeType.MISSING) {

                summary.setMissingAccountCount(
                        summary.getMissingAccountCount() + 1
                );
            }

            if (view.getChangeType()
                    == ChangeType.CHANGED) {

                summary.setChangedAccountCount(
                        summary.getChangedAccountCount() + 1
                );
            }

            if (view.getChangeType()
                    == ChangeType.UNCHANGED) {

                summary.setUnchangedAccountCount(
                        summary.getUnchangedAccountCount() + 1
                );
            }

            if (view.isBalanceChanged()) {

                summary.setBalanceChangedAccountCount(
                        summary.getBalanceChangedAccountCount() + 1
                );

                totalBalanceChange =
                        totalBalanceChange.add(
                                parseAmount(
                                        view.getBalanceChange()
                                )
                        );
            }

            summary.setChangedFieldCount(
                    summary.getChangedFieldCount()
                            + view.getChangedFieldCount()
            );
        }

        summary.setTotalBalanceChange(
                formatAmount(totalBalanceChange)
        );

        return summary;
    }

    public List<DailySummary> getDailySummaries() {

        List<DailySummary> summaries =
                new ArrayList<DailySummary>();

        List<String> dates =
                getAvailableDates();

        for (String businessDate : dates) {

            DailySnapshot snapshot =
                    snapshotsByDate.get(businessDate);

            DailySummary summary =
                    new DailySummary();

            summary.setBusinessDate(businessDate);

            summary.setMemberCount(
                    snapshot.getMemberCount()
            );

            summary.setAccountCount(
                    snapshot.getAccountCount()
            );

            summary.setSourceRecordCount(
                    snapshot.getSourceRecordCount()
            );

            summary.setSourceFiles(
                    String.join(
                            ", ",
                            snapshot.getSourceFiles()
                    )
            );

            if (getPreviousDate(businessDate) == null) {

                summary.setNewAccountCount(0);
                summary.setMissingAccountCount(0);
                summary.setChangedAccountCount(0);
                summary.setUnchangedAccountCount(0);
                summary.setBalanceChangedAccountCount(0);
                summary.setChangedFieldCount(0);
                summary.setTotalBalanceChange("0");

            } else {

                ComparisonSummary comparison =
                        getComparisonSummary(
                                businessDate,
                                null
                        );

                summary.setNewAccountCount(
                        comparison.getNewAccountCount()
                );

                summary.setMissingAccountCount(
                        comparison.getMissingAccountCount()
                );

                summary.setChangedAccountCount(
                        comparison.getChangedAccountCount()
                );

                summary.setUnchangedAccountCount(
                        comparison.getUnchangedAccountCount()
                );

                summary.setBalanceChangedAccountCount(
                        comparison.getBalanceChangedAccountCount()
                );

                summary.setChangedFieldCount(
                        comparison.getChangedFieldCount()
                );

                summary.setTotalBalanceChange(
                        comparison.getTotalBalanceChange()
                );
            }

            summaries.add(summary);
        }

        return summaries;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public int getLoadedDateCount() {
        return snapshotsByDate.size();
    }

    /*
     * =========================================================
     * INNER MODEL CLASSES
     * =========================================================
     */

    public static class AccountRecord {

        private String memberNumber;
        private String accountNumber;

        private Map<String, String> requestFields =
                new LinkedHashMap<String, String>();

        private Map<String, String> responseFields =
                new LinkedHashMap<String, String>();

        private String requestJson;
        private String responseJson;
        private String accountJson;

        private String sourceFile;
        private String businessDate;
        private long sourceLine;

        private boolean responseAvailable;

        public AccountRecord() {
        }

        public String getMemberNumber() {
            return memberNumber;
        }

        public void setMemberNumber(
                String memberNumber) {

            this.memberNumber = memberNumber;
        }

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(
                String accountNumber) {

            this.accountNumber = accountNumber;
        }

        public Map<String, String> getRequestFields() {
            return requestFields;
        }

        public void setRequestFields(
                Map<String, String> requestFields) {

            this.requestFields =
                    requestFields == null
                            ? new LinkedHashMap<String, String>()
                            : new LinkedHashMap<String, String>(
                                    requestFields
                            );
        }

        public Map<String, String> getResponseFields() {
            return responseFields;
        }

        public void setResponseFields(
                Map<String, String> responseFields) {

            this.responseFields =
                    responseFields == null
                            ? new LinkedHashMap<String, String>()
                            : new LinkedHashMap<String, String>(
                                    responseFields
                            );
        }

        public String getRequestJson() {
            return requestJson;
        }

        public void setRequestJson(String requestJson) {
            this.requestJson = requestJson;
        }

        public String getResponseJson() {
            return responseJson;
        }

        public void setResponseJson(String responseJson) {
            this.responseJson = responseJson;
        }

        public String getAccountJson() {
            return accountJson;
        }

        public void setAccountJson(String accountJson) {
            this.accountJson = accountJson;
        }

        public String getSourceFile() {
            return sourceFile;
        }

        public void setSourceFile(String sourceFile) {
            this.sourceFile = sourceFile;
        }

        public String getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(String businessDate) {
            this.businessDate = businessDate;
        }

        public long getSourceLine() {
            return sourceLine;
        }

        public void setSourceLine(long sourceLine) {
            this.sourceLine = sourceLine;
        }

        public boolean isResponseAvailable() {
            return responseAvailable;
        }

        public void setResponseAvailable(
                boolean responseAvailable) {

            this.responseAvailable = responseAvailable;
        }

        public String requestValue(
                String fieldName) {

            return requestFields == null
                    ? ""
                    : requestFields.getOrDefault(
                            fieldName,
                            ""
                    );
        }

        public String responseValue(
                String fieldName) {

            return responseFields == null
                    ? ""
                    : responseFields.getOrDefault(
                            fieldName,
                            ""
                    );
        }

        public String getAccountStatus() {
            return requestValue("accountStatus");
        }

        public String getAmountPastDue() {
            return requestValue("amountPastDue");
        }

        public String getLoanDelinquentDayCount() {
            return requestValue("numDaysCount");
        }

        public String getAccountBalance() {
            return requestValue("accountBalance");
        }

        public String getCreditLimit() {
            return requestValue("creditLimit");
        }

        public String getFicoScore() {
            return requestValue("fico09Score");
        }

        public String getActionType() {
            return responseValue("actionType");
        }

        public String getChannel() {

            String channel =
                    responseValue("channel");

            if (channel.startsWith("[")
                    && channel.endsWith("]")) {

                channel = channel
                        .substring(
                                1,
                                channel.length() - 1
                        )
                        .replace("\"", "")
                        .trim();
            }

            return channel;
        }

        public String getResponseStatus() {

            return responseAvailable
                    ? "Available"
                    : "Wrapper not found";
        }
    }

    public static class AccountDayView {

        private AccountRecord account;
        private AccountRecord previousAccount;

        private ChangeType changeType;

        private String currentDate;
        private String previousDate;

        private String previousBalance;
        private String currentBalance;
        private String balanceChange;

        private boolean balanceChanged;

        private String channel;

        private List<FieldChange> changedFields =
                new ArrayList<FieldChange>();

        private int changedFieldCount;

        public AccountDayView() {
        }

        public AccountRecord getAccount() {
            return account;
        }

        public void setAccount(AccountRecord account) {
            this.account = account;
        }

        public AccountRecord getPreviousAccount() {
            return previousAccount;
        }

        public void setPreviousAccount(
                AccountRecord previousAccount) {

            this.previousAccount = previousAccount;
        }

        public ChangeType getChangeType() {
            return changeType;
        }

        public void setChangeType(
                ChangeType changeType) {

            this.changeType = changeType;
        }

        public String getCurrentDate() {
            return currentDate;
        }

        public void setCurrentDate(String currentDate) {
            this.currentDate = currentDate;
        }

        public String getPreviousDate() {
            return previousDate;
        }

        public void setPreviousDate(String previousDate) {
            this.previousDate = previousDate;
        }

        public String getPreviousBalance() {
            return previousBalance;
        }

        public void setPreviousBalance(
                String previousBalance) {

            this.previousBalance = previousBalance;
        }

        public String getCurrentBalance() {
            return currentBalance;
        }

        public void setCurrentBalance(
                String currentBalance) {

            this.currentBalance = currentBalance;
        }

        public String getBalanceChange() {
            return balanceChange;
        }

        public void setBalanceChange(
                String balanceChange) {

            this.balanceChange = balanceChange;
        }

        public boolean isBalanceChanged() {
            return balanceChanged;
        }

        public void setBalanceChanged(
                boolean balanceChanged) {

            this.balanceChanged = balanceChanged;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public List<FieldChange> getChangedFields() {
            return changedFields;
        }

        public void setChangedFields(
                List<FieldChange> changedFields) {

            this.changedFields =
                    changedFields == null
                            ? new ArrayList<FieldChange>()
                            : changedFields;
        }

        public int getChangedFieldCount() {
            return changedFieldCount;
        }

        public void setChangedFieldCount(
                int changedFieldCount) {

            this.changedFieldCount = changedFieldCount;
        }
    }

    public static class FieldChange {

        private String section;
        private String fieldName;
        private String previousValue;
        private String currentValue;

        public FieldChange() {
        }

        public String getSection() {
            return section;
        }

        public void setSection(String section) {
            this.section = section;
        }

        public String getFieldName() {
            return fieldName;
        }

        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }

        public String getPreviousValue() {
            return previousValue;
        }

        public void setPreviousValue(
                String previousValue) {

            this.previousValue = previousValue;
        }

        public String getCurrentValue() {
            return currentValue;
        }

        public void setCurrentValue(
                String currentValue) {

            this.currentValue = currentValue;
        }
    }

    public enum ChangeType {
        NEW,
        MISSING,
        CHANGED,
        UNCHANGED
    }

    public static class DailySnapshot {

        private String businessDate;

        private Map<String, Map<String, AccountRecord>>
                accountsByMember =
                new TreeMap<String, Map<String, AccountRecord>>();

        private long sourceRecordCount;

        private Set<String> sourceFiles =
                new TreeSet<String>();

        public DailySnapshot() {
        }

        public DailySnapshot(String businessDate) {
            this.businessDate = businessDate;
        }

        public void addAccount(
                AccountRecord accountRecord) {

            accountsByMember
                    .computeIfAbsent(
                            accountRecord.getMemberNumber(),
                            member ->
                                    new TreeMap<String, AccountRecord>()
                    )
                    .put(
                            accountRecord.getAccountNumber(),
                            accountRecord
                    );
        }

        public String getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(
                String businessDate) {

            this.businessDate = businessDate;
        }

        public Map<String, Map<String, AccountRecord>>
        getAccountsByMember() {

            return accountsByMember;
        }

        public void setAccountsByMember(
                Map<String, Map<String, AccountRecord>>
                        accountsByMember) {

            this.accountsByMember =
                    accountsByMember == null
                            ? new TreeMap<String, Map<String, AccountRecord>>()
                            : accountsByMember;
        }

        public long getSourceRecordCount() {
            return sourceRecordCount;
        }

        public void setSourceRecordCount(
                long sourceRecordCount) {

            this.sourceRecordCount = sourceRecordCount;
        }

        public Set<String> getSourceFiles() {
            return sourceFiles;
        }

        public void setSourceFiles(
                Set<String> sourceFiles) {

            this.sourceFiles =
                    sourceFiles == null
                            ? new TreeSet<String>()
                            : sourceFiles;
        }

        public long getMemberCount() {
            return accountsByMember.size();
        }

        public long getAccountCount() {

            long count = 0;

            for (Map<String, AccountRecord> accounts
                    : accountsByMember.values()) {

                count += accounts.size();
            }

            return count;
        }
    }

    public static class ComparisonSummary {

        private long totalAccountCount;
        private long newAccountCount;
        private long missingAccountCount;
        private long changedAccountCount;
        private long unchangedAccountCount;
        private long balanceChangedAccountCount;
        private long changedFieldCount;
        private String totalBalanceChange = "0";

        public ComparisonSummary() {
        }

        public long getTotalAccountCount() {
            return totalAccountCount;
        }

        public void setTotalAccountCount(
                long totalAccountCount) {

            this.totalAccountCount = totalAccountCount;
        }

        public long getNewAccountCount() {
            return newAccountCount;
        }

        public void setNewAccountCount(
                long newAccountCount) {

            this.newAccountCount = newAccountCount;
        }

        public long getMissingAccountCount() {
            return missingAccountCount;
        }

        public void setMissingAccountCount(
                long missingAccountCount) {

            this.missingAccountCount = missingAccountCount;
        }

        public long getChangedAccountCount() {
            return changedAccountCount;
        }

        public void setChangedAccountCount(
                long changedAccountCount) {

            this.changedAccountCount = changedAccountCount;
        }

        public long getUnchangedAccountCount() {
            return unchangedAccountCount;
        }

        public void setUnchangedAccountCount(
                long unchangedAccountCount) {

            this.unchangedAccountCount = unchangedAccountCount;
        }

        public long getBalanceChangedAccountCount() {
            return balanceChangedAccountCount;
        }

        public void setBalanceChangedAccountCount(
                long balanceChangedAccountCount) {

            this.balanceChangedAccountCount =
                    balanceChangedAccountCount;
        }

        public long getChangedFieldCount() {
            return changedFieldCount;
        }

        public void setChangedFieldCount(
                long changedFieldCount) {

            this.changedFieldCount = changedFieldCount;
        }

        public String getTotalBalanceChange() {
            return totalBalanceChange;
        }

        public void setTotalBalanceChange(
                String totalBalanceChange) {

            this.totalBalanceChange = totalBalanceChange;
        }
    }

    public static class DailySummary {

        private String businessDate;
        private long memberCount;
        private long accountCount;
        private long sourceRecordCount;
        private String sourceFiles;

        private long newAccountCount;
        private long missingAccountCount;
        private long changedAccountCount;
        private long unchangedAccountCount;
        private long balanceChangedAccountCount;
        private long changedFieldCount;

        private String totalBalanceChange;

        public DailySummary() {
        }

        public String getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(
                String businessDate) {

            this.businessDate = businessDate;
        }

        public long getMemberCount() {
            return memberCount;
        }

        public void setMemberCount(long memberCount) {
            this.memberCount = memberCount;
        }

        public long getAccountCount() {
            return accountCount;
        }

        public void setAccountCount(long accountCount) {
            this.accountCount = accountCount;
        }

        public long getSourceRecordCount() {
            return sourceRecordCount;
        }

        public void setSourceRecordCount(
                long sourceRecordCount) {

            this.sourceRecordCount = sourceRecordCount;
        }

        public String getSourceFiles() {
            return sourceFiles;
        }

        public void setSourceFiles(String sourceFiles) {
            this.sourceFiles = sourceFiles;
        }

        public long getNewAccountCount() {
            return newAccountCount;
        }

        public void setNewAccountCount(
                long newAccountCount) {

            this.newAccountCount = newAccountCount;
        }

        public long getMissingAccountCount() {
            return missingAccountCount;
        }

        public void setMissingAccountCount(
                long missingAccountCount) {

            this.missingAccountCount = missingAccountCount;
        }

        public long getChangedAccountCount() {
            return changedAccountCount;
        }

        public void setChangedAccountCount(
                long changedAccountCount) {

            this.changedAccountCount = changedAccountCount;
        }

        public long getUnchangedAccountCount() {
            return unchangedAccountCount;
        }

        public void setUnchangedAccountCount(
                long unchangedAccountCount) {

            this.unchangedAccountCount = unchangedAccountCount;
        }

        public long getBalanceChangedAccountCount() {
            return balanceChangedAccountCount;
        }

        public void setBalanceChangedAccountCount(
                long balanceChangedAccountCount) {

            this.balanceChangedAccountCount =
                    balanceChangedAccountCount;
        }

        public long getChangedFieldCount() {
            return changedFieldCount;
        }

        public void setChangedFieldCount(
                long changedFieldCount) {

            this.changedFieldCount = changedFieldCount;
        }

        public String getTotalBalanceChange() {
            return totalBalanceChange;
        }

        public void setTotalBalanceChange(
                String totalBalanceChange) {

            this.totalBalanceChange = totalBalanceChange;
        }
    }
}
