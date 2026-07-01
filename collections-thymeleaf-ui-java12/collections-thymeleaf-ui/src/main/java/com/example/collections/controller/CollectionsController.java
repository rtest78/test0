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

    /*
     * Key: business date
     * Value: complete daily snapshot
     */
    private final Map<String, DailySnapshot> snapshotsByDate =
            new ConcurrentHashMap<String, DailySnapshot>();

    /*
     * application.properties example:
     *
     * collections.data.directory=C:/opensource/eclipse-workspace/test2/data
     */
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

        System.out.println(
                "Folder exists: "
                        + Files.exists(folder)
        );

        System.out.println(
                "Is directory: "
                        + Files.isDirectory(folder)
        );

        Files.createDirectories(folder);

        List<Path> files = discoverFiles(folder);

        System.out.println(
                "Files discovered: "
                        + files.size()
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

            DailySnapshot snapshot = entry.getValue();

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
                    .filter(path -> {

                        String filename =
                                path.getFileName().toString();

                        return !filename.startsWith(".");
                    })
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
                line.substring(
                        0,
                        firstComma
                ).trim();

        String outerJsonText =
                line.substring(
                        firstComma + 1
                ).trim();

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
                objectMapper.readTree(
                        outerJsonText
                );

        JsonNode inputPayload =
                extractPayload(
                        outerNode,
                        "input"
                );

        JsonNode requestNode =
                findRequestNode(
                        inputPayload
                );

        JsonNode customerDataNode =
                requestNode.path(
                        "customerData"
                );

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
                findResponseNode(
                        outputPayload
                );

        DailySnapshot snapshot =
                loaded.computeIfAbsent(
                        businessDate,
                        date -> new DailySnapshot(date)
                );

        snapshot.getSourceFiles().add(
                sourceFile.getFileName().toString()
        );

        Map<String, String> responseFields =
                flattenJson(
                        responseNode
                );

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
                    prettyJson(responseNode)
            );

            snapshot.addAccount(
                    accountRecord
            );
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

        if (sectionNode.isMissingNode()
                || sectionNode.isNull()) {

            return objectMapper.createObjectNode();
        }

        JsonNode dataNode =
                sectionNode.path("data");

        if (dataNode.isMissingNode()
                || dataNode.isNull()) {

            return sectionNode;
        }

        return parseNestedJson(dataNode);
    }

    private JsonNode parseNestedJson(
            JsonNode dataNode)
            throws IOException {

        if (dataNode == null
                || dataNode.isMissingNode()
                || dataNode.isNull()) {

            return objectMapper.createObjectNode();
        }

        if (dataNode.isObject()
                || dataNode.isArray()) {

            return dataNode;
        }

        if (!dataNode.isTextual()) {
            return dataNode;
        }

        String jsonText =
                dataNode.asText();

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

        return objectMapper.createObjectNode();
    }

    private JsonNode findResponseNode(
            JsonNode outputPayload) {

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

        if (node == null
                || node.isMissingNode()
                || node.isNull()) {

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

        if (valueNode.isMissingNode()
                || valueNode.isNull()) {

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
     * PUBLIC DATA METHODS
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

    public List<DailySummary> getDailySummaries() {

        List<DailySummary> summaries =
                new ArrayList<DailySummary>();

        List<String> dates =
                getAvailableDates();

        for (String businessDate : dates) {

            DailySnapshot snapshot =
                    snapshotsByDate.get(
                            businessDate
                    );

            DailySummary summary =
                    new DailySummary();

            summary.setBusinessDate(
                    businessDate
            );

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

            String previousDate =
                    getPreviousDate(
                            businessDate
                    );

            if (previousDate == null) {

                summary.setNewAccountCount(0);
                summary.setMissingAccountCount(0);
                summary.setChangedAccountCount(0);
                summary.setBalanceChangedAccountCount(0);
                summary.setTotalBalanceChange("0");

            } else {

                List<AccountDayView> views =
                        findAccountDayViews(
                                businessDate,
                                null
                        );

                long newCount = 0;
                long missingCount = 0;
                long changedCount = 0;
                long balanceChangedCount = 0;

                BigDecimal totalBalanceChange =
                        BigDecimal.ZERO;

                for (AccountDayView view : views) {

                    if (view.getChangeType()
                            == ChangeType.NEW) {

                        newCount++;
                    }

                    if (view.getChangeType()
                            == ChangeType.MISSING) {

                        missingCount++;
                    }

                    if (view.getChangeType()
                            == ChangeType.CHANGED) {

                        changedCount++;
                    }

                    if (view.isBalanceChanged()) {

                        balanceChangedCount++;

                        totalBalanceChange =
                                totalBalanceChange.add(
                                        parseAmount(
                                                view.getBalanceChange()
                                        )
                                );
                    }
                }

                summary.setNewAccountCount(
                        newCount
                );

                summary.setMissingAccountCount(
                        missingCount
                );

                summary.setChangedAccountCount(
                        changedCount
                );

                summary.setBalanceChangedAccountCount(
                        balanceChangedCount
                );

                summary.setTotalBalanceChange(
                        formatAmount(
                                totalBalanceChange
                        )
                );
            }

            summaries.add(summary);
        }

        return summaries;
    }

    public List<AccountDayView> findAccountDayViews(
            String businessDate,
            String memberNumber) {

        String currentDate =
                resolveDate(businessDate);

        if (currentDate == null) {

            return new ArrayList<AccountDayView>();
        }

        String previousDate =
                getPreviousDate(currentDate);

        DailySnapshot currentSnapshot =
                snapshotsByDate.get(currentDate);

        DailySnapshot previousSnapshot =
                previousDate == null
                        ? null
                        : snapshotsByDate.get(
                                previousDate
                        );

        String normalizedMember =
                memberNumber == null
                        ? ""
                        : memberNumber.trim();

        Set<String> memberNumbers =
                new TreeSet<String>();

        if (!normalizedMember.isEmpty()) {

            memberNumbers.add(
                    normalizedMember
            );

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

        for (String currentMemberNumber
                : memberNumbers) {

            Map<String, AccountRecord> currentAccounts =
                    getMemberAccounts(
                            currentSnapshot,
                            currentMemberNumber
                    );

            Map<String, AccountRecord> previousAccounts =
                    getMemberAccounts(
                            previousSnapshot,
                            currentMemberNumber
                    );

            Set<String> accountNumbers =
                    new TreeSet<String>();

            accountNumbers.addAll(
                    currentAccounts.keySet()
            );

            accountNumbers.addAll(
                    previousAccounts.keySet()
            );

            for (String accountNumber
                    : accountNumbers) {

                AccountRecord currentAccount =
                        currentAccounts.get(
                                accountNumber
                        );

                AccountRecord previousAccount =
                        previousAccounts.get(
                                accountNumber
                        );

                AccountDayView view =
                        new AccountDayView();

                view.setCurrentDate(
                        currentDate
                );

                view.setPreviousDate(
                        previousDate
                );

                view.setPreviousAccount(
                        previousAccount
                );

                String previousBalanceText =
                        previousAccount == null
                                ? ""
                                : previousAccount
                                        .getAccountBalance();

                String currentBalanceText =
                        currentAccount == null
                                ? ""
                                : currentAccount
                                        .getAccountBalance();

                BigDecimal previousBalance =
                        parseAmount(
                                previousBalanceText
                        );

                BigDecimal currentBalance =
                        parseAmount(
                                currentBalanceText
                        );

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
                 * Missing account:
                 * obtain channel from previous day's response.
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

                    view.setAccount(
                            currentAccount
                    );

                    view.setChangeType(
                            ChangeType.NEW
                    );

                } else if (currentAccount == null
                        && previousAccount != null) {

                    view.setAccount(
                            previousAccount
                    );

                    view.setChangeType(
                            ChangeType.MISSING
                    );

                } else if (accountsDifferent(
                        previousAccount,
                        currentAccount
                )) {

                    view.setAccount(
                            currentAccount
                    );

                    view.setChangeType(
                            ChangeType.CHANGED
                    );

                } else {

                    view.setAccount(
                            currentAccount
                    );

                    view.setChangeType(
                            ChangeType.UNCHANGED
                    );
                }

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

    private boolean accountsDifferent(
            AccountRecord previous,
            AccountRecord current) {

        if (previous == null
                && current == null) {

            return false;
        }

        if (previous == null
                || current == null) {

            return true;
        }

        return !Objects.equals(
                previous.getRequestFields(),
                current.getRequestFields()
        )
                || !Objects.equals(
                        previous.getResponseFields(),
                        current.getResponseFields()
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

        public void setRequestJson(
                String requestJson) {

            this.requestJson = requestJson;
        }

        public String getResponseJson() {
            return responseJson;
        }

        public void setResponseJson(
                String responseJson) {

            this.responseJson = responseJson;
        }

        public String getAccountJson() {
            return accountJson;
        }

        public void setAccountJson(
                String accountJson) {

            this.accountJson = accountJson;
        }

        public String getSourceFile() {
            return sourceFile;
        }

        public void setSourceFile(
                String sourceFile) {

            this.sourceFile = sourceFile;
        }

        public String getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(
                String businessDate) {

            this.businessDate = businessDate;
        }

        public long getSourceLine() {
            return sourceLine;
        }

        public void setSourceLine(
                long sourceLine) {

            this.sourceLine = sourceLine;
        }

        public String requestValue(
                String fieldName) {

            if (requestFields == null) {
                return "";
            }

            return requestFields.getOrDefault(
                    fieldName,
                    ""
            );
        }

        public String responseValue(
                String fieldName) {

            if (responseFields == null) {
                return "";
            }

            return responseFields.getOrDefault(
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

            /*
             * Some response structures store channel as an array.
             * The flattened value may appear as ["SENTRY"].
             */
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

        public String getExceptionMessage() {
            return responseValue("exceptionMessage");
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

        public AccountDayView() {
        }

        public AccountRecord getAccount() {
            return account;
        }

        public void setAccount(
                AccountRecord account) {

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

        public void setCurrentDate(
                String currentDate) {

            this.currentDate = currentDate;
        }

        public String getPreviousDate() {
            return previousDate;
        }

        public void setPreviousDate(
                String previousDate) {

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

        public void setChannel(
                String channel) {

            this.channel = channel;
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

        /*
         * First key: member number
         * Second key: account number
         */
        private Map<String, Map<String, AccountRecord>>
                accountsByMember =
                new TreeMap<String, Map<String, AccountRecord>>();

        private long sourceRecordCount;

        private Set<String> sourceFiles =
                new TreeSet<String>();

        public DailySnapshot() {
        }

        public DailySnapshot(
                String businessDate) {

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

    public static class DailySummary {

        private String businessDate;
        private long memberCount;
        private long accountCount;
        private long sourceRecordCount;
        private String sourceFiles;

        private long newAccountCount;
        private long missingAccountCount;
        private long changedAccountCount;
        private long balanceChangedAccountCount;

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

        public void setMemberCount(
                long memberCount) {

            this.memberCount = memberCount;
        }

        public long getAccountCount() {
            return accountCount;
        }

        public void setAccountCount(
                long accountCount) {

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

        public void setSourceFiles(
                String sourceFiles) {

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

        public long getBalanceChangedAccountCount() {
            return balanceChangedAccountCount;
        }

        public void setBalanceChangedAccountCount(
                long balanceChangedAccountCount) {

            this.balanceChangedAccountCount =
                    balanceChangedAccountCount;
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
