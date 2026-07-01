package com.example.collectionsui.service;

import com.example.collectionsui.model.AccountData;
import com.example.collectionsui.model.DailySnapshot;
import com.example.collectionsui.model.DailySummary;
import com.example.collectionsui.model.MemberData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import java.io.BufferedReader;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CollectionsDataService {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /*
     * Key   = business date, for example 2026-06-26
     * Value = all members and accounts found for that date
     */
    private final Map<String, DailySnapshot> snapshotsByDate =
            new ConcurrentHashMap<String, DailySnapshot>();

    /*
     * application.properties:
     *
     * collections.data.directory=C:/opensource/eclipse-workspace/test2/data
     *
     * When the property is missing, ./data is used.
     */
    @Value("${collections.data.directory:./data}")
    private String dataDirectory;

    @PostConstruct
    public void initialize() {

        try {

            reload();

        } catch (Exception ex) {

            System.err.println(
                    "Collections data load failed: "
                            + ex.getMessage()
            );

            ex.printStackTrace();
        }
    }

    /**
     * Reloads every file in the configured directory.
     *
     * Multiple files can contain records for the same business date.
     * Records from those files are merged into one DailySnapshot.
     */
    public synchronized void reload()
            throws IOException {

        Path folder =
                Path.of(dataDirectory);

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

        List<Path> files =
                discoverFiles(folder);

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
                        + snapshotsByDate.keySet()
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
                    entry.getKey()
                            + " members="
                            + snapshot
                                    .getMembersByNumber()
                                    .size()
                            + ", accounts="
                            + snapshot.getAccountCount()
                            + ", source records="
                            + snapshot
                                    .getSourceRecordCount()
            );
        }
    }

    /**
     * Finds all regular files under the configured data directory.
     *
     * Files do not need a .json extension.
     */
    private List<Path> discoverFiles(
            Path folder)
            throws IOException {

        try (Stream<Path> stream =
                     Files.walk(folder)) {

            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {

                        String filename =
                                path.getFileName()
                                        .toString();

                        return !filename.startsWith(".");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * Reads one extract file line by line.
     *
     * Expected line:
     *
     * 2026-06-26,{"input":{...},"output":{...}}
     */
    private void parseFile(
            Path file,
            Map<String, DailySnapshot> loaded)
            throws IOException {

        long totalLines = 0;
        long successfulLines = 0;
        long failedLines = 0;

        try (BufferedReader reader =
                     Files.newBufferedReader(
                             file,
                             StandardCharsets.UTF_8)) {

            String line;

            while ((line = reader.readLine())
                    != null) {

                totalLines++;

                if (line.trim().isEmpty()) {
                    continue;
                }

                try {

                    parseLine(
                            file,
                            totalLines,
                            line,
                            loaded
                    );

                    successfulLines++;

                } catch (Exception ex) {

                    failedLines++;

                    System.err.println(
                            "Unable to parse file="
                                    + file.getFileName()
                                    + ", line="
                                    + totalLines
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
                        + totalLines
                        + ", successful="
                        + successfulLines
                        + ", failed="
                        + failedLines
        );
    }

    /**
     * Parses one dated JSON line.
     *
     * Structure:
     *
     * business date
     *     ↓
     * outer JSON
     *     ↓
     * input.data escaped JSON
     *     ↓
     * collections.request.customerData
     *
     * outer JSON
     *     ↓
     * output.data escaped JSON
     *     ↓
     * collections.response
     */
    private void parseLine(
            Path sourceFile,
            long lineNumber,
            String line,
            Map<String, DailySnapshot> loaded)
            throws IOException {

        int firstComma =
                line.indexOf(',');

        if (firstComma < 0) {

            throw new IllegalArgumentException(
                    "Business date separator comma "
                            + "was not found"
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
                    "Outer JSON is empty"
            );
        }

        JsonNode outerNode =
                objectMapper.readTree(
                        outerJsonText
                );

        /*
         * Read and parse input.data.
         */
        JsonNode inputDataNode =
                outerNode
                        .path("input")
                        .path("data");

        JsonNode inputPayload =
                parseNestedJson(
                        inputDataNode
                );

        /*
         * Your extract has:
         *
         * input.data
         *   -> collections
         *   -> request
         */
        JsonNode requestNode =
                inputPayload
                        .path("collections")
                        .path("request");

        /*
         * Fallback in case another dataset stores
         * request directly under input.data.
         */
        if (requestNode.isMissingNode()
                || requestNode.isNull()) {

            requestNode =
                    inputPayload.path(
                            "request"
                    );
        }

        JsonNode customerDataNode =
                requestNode.path(
                        "customerData"
                );

        if (!customerDataNode.isArray()) {

            throw new IllegalArgumentException(
                    "customerData was not found at "
                            + "collections.request.customerData"
            );
        }

        /*
         * Read and parse output.data.
         */
        JsonNode outputDataNode =
                outerNode
                        .path("output")
                        .path("data");

        JsonNode outputPayload =
                parseNestedJson(
                        outputDataNode
                );

        /*
         * Your extract has:
         *
         * output.data
         *   -> collections
         *   -> response
         */
        JsonNode responseNode =
                outputPayload
                        .path("collections")
                        .path("response");

        /*
         * Fallback for datasets where response is
         * directly below output.data.
         */
        if (responseNode.isMissingNode()
                || responseNode.isNull()) {

            responseNode =
                    outputPayload.path(
                            "response"
                    );
        }

        /*
         * Merge all records from all files that
         * belong to the same business date.
         */
        DailySnapshot snapshot =
                loaded.computeIfAbsent(
                        businessDate,
                        date ->
                                new DailySnapshot(
                                        date
                                )
                );

        for (JsonNode accountNode
                : customerDataNode) {

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
                        "Skipping record with no "
                                + "memberNumber. File="
                                + sourceFile
                                        .getFileName()
                                + ", line="
                                + lineNumber
                );

                continue;
            }

            if (accountNumber.isEmpty()) {

                System.err.println(
                        "Skipping record with no "
                                + "accountNumber. File="
                                + sourceFile
                                        .getFileName()
                                + ", line="
                                + lineNumber
                                + ", memberNumber="
                                + memberNumber
                );

                continue;
            }

            MemberData memberData =
                    snapshot
                            .getMembersByNumber()
                            .computeIfAbsent(
                                    memberNumber,
                                    key ->
                                            new MemberData(
                                                    memberNumber
                                            )
                            );

            AccountData accountData =
                    createAccountData(
                            businessDate,
                            sourceFile,
                            accountNode,
                            requestNode,
                            responseNode
                    );

            /*
             * Account number is the key inside
             * the member.
             *
             * If the same member/account appears
             * again on the same date, the later
             * record replaces the earlier record.
             */
            memberData
                    .getAccountsByNumber()
                    .put(
                            accountNumber,
                            accountData
                    );
        }

        snapshot.setSourceRecordCount(
                snapshot.getSourceRecordCount()
                        + 1
        );
    }

    /**
     * Parses JSON stored inside input.data or output.data.
     *
     * Supports:
     * - escaped JSON strings
     * - normal JSON objects
     * - JSON that was encoded more than once
     */
    private JsonNode parseNestedJson(
            JsonNode dataNode)
            throws IOException {

        if (dataNode == null
                || dataNode.isMissingNode()
                || dataNode.isNull()) {

            return objectMapper
                    .createObjectNode();
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

            return objectMapper
                    .createObjectNode();
        }

        JsonNode parsedNode =
                objectMapper.readTree(
                        jsonText
                );

        int attempts = 0;

        while (parsedNode.isTextual()
                && attempts < 3) {

            String nestedText =
                    parsedNode.asText();

            if (nestedText == null
                    || nestedText
                            .trim()
                            .isEmpty()) {

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

    /**
     * Creates the account object shown in Thymeleaf.
     *
     * It preserves:
     * - the individual account JSON
     * - the complete request JSON
     * - the complete response JSON
     */
    private AccountData createAccountData(
            String businessDate,
            Path sourceFile,
            JsonNode accountNode,
            JsonNode requestNode,
            JsonNode responseNode) {

        AccountData accountData =
                new AccountData();

        accountData.setBusinessDate(
                businessDate
        );

        accountData.setSourceFile(
                sourceFile
                        .getFileName()
                        .toString()
        );

        accountData.setMemberNumber(
                getText(
                        accountNode,
                        "memberNumber"
                )
        );

        accountData.setAccountNumber(
                getText(
                        accountNode,
                        "accountNumber"
                )
        );

        accountData.setAccountStatus(
                getText(
                        accountNode,
                        "accountStatus"
                )
        );

        accountData.setAmountPastDue(
                getText(
                        accountNode,
                        "amountPastDue"
                )
        );

        accountData.setAccountBalance(
                getText(
                        accountNode,
                        "accountBalance"
                )
        );

        accountData.setCreditLimit(
                getText(
                        accountNode,
                        "creditLimit"
                )
        );

        accountData.setFicoScore(
                getText(
                        accountNode,
                        "fico09Score"
                )
        );

        accountData.setAccountJson(
                prettyJson(
                        accountNode
                )
        );

        accountData.setRequestJson(
                prettyJson(
                        requestNode
                )
        );

        accountData.setResponseJson(
                prettyJson(
                        responseNode
                )
        );

        return accountData;
    }

    private String prettyJson(
            JsonNode node) {

        if (node == null
                || node.isMissingNode()
                || node.isNull()) {

            return "";
        }

        try {

            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(
                            node
                    );

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

    /**
     * Returns all loaded business dates.
     */
    public List<String> getAvailableDates() {

        List<String> dates =
                new ArrayList<String>(
                        snapshotsByDate.keySet()
                );

        Collections.sort(dates);

        return dates;
    }

    /**
     * Returns dashboard totals for each business date.
     */
    public List<DailySummary> getDailySummaries() {

        List<DailySummary> summaries =
                new ArrayList<DailySummary>();

        Map<String, DailySnapshot> sorted =
                new TreeMap<String, DailySnapshot>(
                        snapshotsByDate
                );

        for (Map.Entry<String, DailySnapshot> entry
                : sorted.entrySet()) {

            DailySnapshot snapshot =
                    entry.getValue();

            DailySummary summary =
                    new DailySummary();

            summary.setBusinessDate(
                    entry.getKey()
            );

            summary.setMemberCount(
                    snapshot
                            .getMembersByNumber()
                            .size()
            );

            summary.setAccountCount(
                    snapshot.getAccountCount()
            );

            summary.setSourceRecordCount(
                    snapshot
                            .getSourceRecordCount()
            );

            summaries.add(summary);
        }

        return summaries;
    }

    /**
     * Returns accounts for the selected date.
     *
     * When memberNumber is supplied, only that member is returned.
     */
    public List<AccountData> findAccounts(
            String businessDate,
            String memberNumber) {

        DailySnapshot snapshot =
                getSnapshot(
                        businessDate
                );

        if (snapshot == null) {

            return new ArrayList<AccountData>();
        }

        List<AccountData> accounts =
                new ArrayList<AccountData>();

        String normalizedMemberNumber =
                memberNumber == null
                        ? ""
                        : memberNumber.trim();

        if (!normalizedMemberNumber.isEmpty()) {

            MemberData member =
                    snapshot
                            .getMembersByNumber()
                            .get(
                                    normalizedMemberNumber
                            );

            if (member != null) {

                accounts.addAll(
                        member
                                .getAccountsByNumber()
                                .values()
                );
            }

        } else {

            for (MemberData member
                    : snapshot
                            .getMembersByNumber()
                            .values()) {

                accounts.addAll(
                        member
                                .getAccountsByNumber()
                                .values()
                );
            }
        }

        accounts.sort(
                Comparator
                        .comparing(
                                AccountData::getMemberNumber,
                                Comparator.nullsLast(
                                        String::compareTo
                                )
                        )
                        .thenComparing(
                                AccountData::getAccountNumber,
                                Comparator.nullsLast(
                                        String::compareTo
                                )
                        )
        );

        return accounts;
    }

    /**
     * Returns one snapshot.
     *
     * When no date is supplied, the latest available date is used.
     */
    public DailySnapshot getSnapshot(
            String businessDate) {

        String resolvedDate =
                resolveDate(
                        businessDate
                );

        if (resolvedDate == null) {
            return null;
        }

        return snapshotsByDate.get(
                resolvedDate
        );
    }

    /**
     * Returns the requested date or the latest date.
     */
    public String resolveDate(
            String requestedDate) {

        if (requestedDate != null
                && !requestedDate
                        .trim()
                        .isEmpty()
                && snapshotsByDate
                        .containsKey(
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

    public String getDataDirectory() {
        return dataDirectory;
    }

    public int getLoadedDateCount() {
        return snapshotsByDate.size();
    }
}
