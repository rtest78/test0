package com.example.collections.service;

import com.example.collections.model.AccountDayView;
import com.example.collections.model.AccountRecord;
import com.example.collections.model.DailySnapshot;
import com.example.collections.model.DailySummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CollectionsDataService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("json", "jsonl", "ndjson", "txt");

    private final Map<String, DailySnapshot> snapshotsByDate = new ConcurrentHashMap<>();

    @Value("${collections.data.directory:./data}")
    private String dataDirectory;

    @PostConstruct
    public void initialize() {
        try {
            reload();
        } catch (Exception ex) {
            System.err.println("Collections data load failed: " + ex.getMessage());
        }
    }

    public synchronized void reload() throws IOException {
        Path folder = Path.of(dataDirectory);
        Files.createDirectories(folder);

        Map<String, DailySnapshot> loaded = new TreeMap<>();
        for (Path file : discoverFiles(folder)) {
            DailySnapshot snapshot = parseSnapshot(file);
            loaded.put(snapshot.getBusinessDate(), snapshot);
        }

        snapshotsByDate.clear();
        snapshotsByDate.putAll(loaded);
    }

    public List<String> availableDates() {
        return snapshotsByDate.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    public Optional<DailySnapshot> snapshot(String businessDate) {
        return Optional.ofNullable(snapshotsByDate.get(businessDate));
    }

    public Optional<DailySnapshot> latestSnapshot() {
        return availableDates().stream().reduce((first, second) -> second).flatMap(this::snapshot);
    }

    public List<DailySummary> summaries() {
        List<String> dates = availableDates();
        if (dates.isEmpty()) {
            return List.of();
        }

        DailySnapshot baseline = snapshotsByDate.get(dates.get(0));
        return dates.stream()
                .map(date -> summaryFor(baseline, snapshotsByDate.get(date)))
                .collect(Collectors.toList());
    }

    public List<AccountDayView> accountsForDate(String businessDate, String memberNumber) {
        DailySnapshot current = snapshotsByDate.get(businessDate);
        if (current == null) {
            return List.of();
        }

        String baselineDate = availableDates().stream().findFirst().orElse(businessDate);
        DailySnapshot baseline = snapshotsByDate.get(baselineDate);

        Stream<AccountRecord> stream;
        if (memberNumber == null || memberNumber.isBlank()) {
            stream = current.getAccountsByMember().values().stream().flatMap(List::stream);
        } else {
            stream = current.getAccountsByMember().getOrDefault(memberNumber.trim(), List.of()).stream();
        }

        return stream
                .map(account -> new AccountDayView(account, classify(baseline, current, account)))
                .sorted(Comparator
                        .comparing((AccountDayView v) -> v.getAccount().getMemberNumber())
                        .thenComparing(v -> v.getAccount().getAccountNumber()))
                .collect(Collectors.toList());
    }

    public List<AccountDayView> missingAccountsForDate(String businessDate, String memberNumber) {
        List<String> dates = availableDates();
        if (dates.isEmpty()) {
            return List.of();
        }

        DailySnapshot baseline = snapshotsByDate.get(dates.get(0));
        DailySnapshot current = snapshotsByDate.get(businessDate);
        if (baseline == null || current == null || baseline == current) {
            return List.of();
        }

        Stream<AccountRecord> baselineAccounts = baseline.getAccountsByMember().values().stream().flatMap(List::stream);
        if (memberNumber != null && !memberNumber.isBlank()) {
            baselineAccounts = baseline.getAccountsByMember().getOrDefault(memberNumber.trim(), List.of()).stream();
        }

        return baselineAccounts
                .filter(account -> !containsAccount(current, account.getMemberNumber(), account.getAccountNumber()))
                .map(account -> new AccountDayView(account, AccountDayView.ChangeType.MISSING))
                .sorted(Comparator
                        .comparing((AccountDayView v) -> v.getAccount().getMemberNumber())
                        .thenComparing(v -> v.getAccount().getAccountNumber()))
                .collect(Collectors.toList());
    }

    private DailySummary summaryFor(DailySnapshot baseline, DailySnapshot current) {
        long newCount = 0;
        long missingCount = 0;
        long changedCount = 0;

        if (baseline != null && current != null && !Objects.equals(baseline.getBusinessDate(), current.getBusinessDate())) {
            for (List<AccountRecord> records : current.getAccountsByMember().values()) {
                for (AccountRecord record : records) {
                    if (!containsAccount(baseline, record.getMemberNumber(), record.getAccountNumber())) {
                        newCount++;
                    } else if (hasTrackedChanges(findAccount(baseline, record.getMemberNumber(), record.getAccountNumber()), record)) {
                        changedCount++;
                    }
                }
            }

            for (List<AccountRecord> records : baseline.getAccountsByMember().values()) {
                for (AccountRecord record : records) {
                    if (!containsAccount(current, record.getMemberNumber(), record.getAccountNumber())) {
                        missingCount++;
                    }
                }
            }
        }

        return new DailySummary(
                current.getBusinessDate(),
                current.getSourceFile(),
                current.getMemberCount(),
                current.getAccountCount(),
                current.getTotalLines(),
                current.getParsedLines(),
                current.getRejectedLines(),
                newCount,
                missingCount,
                changedCount
        );
    }

    private AccountDayView.ChangeType classify(DailySnapshot baseline, DailySnapshot current, AccountRecord account) {
        if (baseline == null || Objects.equals(baseline.getBusinessDate(), current.getBusinessDate())) {
            return AccountDayView.ChangeType.UNCHANGED;
        }

        AccountRecord baselineAccount = findAccount(baseline, account.getMemberNumber(), account.getAccountNumber());
        if (baselineAccount == null) {
            return AccountDayView.ChangeType.NEW;
        }
        if (hasTrackedChanges(baselineAccount, account)) {
            return AccountDayView.ChangeType.CHANGED;
        }
        return AccountDayView.ChangeType.UNCHANGED;
    }

    private boolean hasTrackedChanges(AccountRecord oldRecord, AccountRecord newRecord) {
        return !Objects.equals(oldRecord.getRequestFields(), newRecord.getRequestFields())
                || !Objects.equals(oldRecord.getResponseFields(), newRecord.getResponseFields());
    }

    private boolean containsAccount(DailySnapshot snapshot, String memberNumber, String accountNumber) {
        return findAccount(snapshot, memberNumber, accountNumber) != null;
    }

    private AccountRecord findAccount(DailySnapshot snapshot, String memberNumber, String accountNumber) {
        return snapshot.getAccountsByMember().getOrDefault(memberNumber, List.of()).stream()
                .filter(account -> Objects.equals(account.getAccountNumber(), accountNumber))
                .findFirst()
                .orElse(null);
    }

    private List<Path> discoverFiles(Path folder) throws IOException {
        try (Stream<Path> stream = Files.list(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted(Comparator
                            .comparing(this::extractDateFromFilename, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    private boolean isSupported(Path file) {
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && SUPPORTED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private DailySnapshot parseSnapshot(Path file) throws IOException {
        String businessDate = Optional.ofNullable(extractDateFromFilename(file))
                .map(LocalDate::toString)
                .orElse(file.getFileName().toString());

        Map<String, List<AccountRecord>> accountsByMember = new LinkedHashMap<>();
        long totalLines = 0;
        long parsedLines = 0;
        long rejectedLines = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (line.isBlank()) {
                    continue;
                }

                try {
                    JsonNode outer = MAPPER.readTree(line);
                    JsonNode inputPayload = extractPayload(outer, "input");
                    JsonNode outputPayload = extractPayload(outer, "output");

                    JsonNode request = inputPayload.path("request");
                    if (request.isMissingNode() || request.isNull()) {
                        request = inputPayload;
                    }

                    JsonNode response = outputPayload.path("response");
                    if (response.isMissingNode() || response.isNull()) {
                        response = outputPayload;
                    }

                    JsonNode customerData = request.path("customerData");
                    if (!customerData.isArray()) {
                        throw new IllegalArgumentException("request.customerData is missing or is not an array");
                    }

                    Map<String, String> responseFields = flatten(response);
                    for (JsonNode accountNode : customerData) {
                        String memberNumber = text(accountNode, "memberNumber");
                        String accountNumber = text(accountNode, "accountNumber");
                        if (memberNumber.isBlank() || accountNumber.isBlank()) {
                            throw new IllegalArgumentException("memberNumber or accountNumber is missing");
                        }

                        AccountRecord account = new AccountRecord(
                                memberNumber,
                                accountNumber,
                                flatten(accountNode),
                                responseFields,
                                file.getFileName().toString(),
                                businessDate,
                                totalLines
                        );

                        accountsByMember.computeIfAbsent(memberNumber, ignored -> new ArrayList<>()).add(account);
                    }
                    parsedLines++;
                } catch (Exception ex) {
                    rejectedLines++;
                }
            }
        }

        accountsByMember.replaceAll((member, accounts) -> accounts.stream()
                .sorted(Comparator.comparing(AccountRecord::getAccountNumber))
                .collect(Collectors.toList()));

        return new DailySnapshot(
                businessDate,
                file.getFileName().toString(),
                accountsByMember,
                totalLines,
                parsedLines,
                rejectedLines
        );
    }

    private JsonNode extractPayload(JsonNode outer, String fieldName) throws JsonProcessingException {
        JsonNode container = outer.path(fieldName);
        JsonNode data = container.path("data");
        if (data.isMissingNode() || data.isNull()) {
            data = container;
        }
        return unwrap(data);
    }

    private JsonNode unwrap(JsonNode node) throws JsonProcessingException {
        JsonNode current = node;
        for (int i = 0; i < 3; i++) {
            if (current == null || current.isNull() || current.isMissingNode() || !current.isTextual()) {
                return current;
            }
            String value = current.asText();
            if (value.isBlank()) {
                return current;
            }
            current = MAPPER.readTree(value);
        }
        return current;
    }

    private Map<String, String> flatten(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return values;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                values.put(entry.getKey(), "");
            } else if (value.isValueNode()) {
                values.put(entry.getKey(), value.asText());
            } else {
                values.put(entry.getKey(), value.toString());
            }
        });
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private LocalDate extractDateFromFilename(Path file) {
        String filename = file.getFileName().toString();
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyyMMdd"),
                DateTimeFormatter.ofPattern("MMddyyyy")
        );

        for (int start = 0; start < filename.length(); start++) {
            for (int length : List.of(10, 8)) {
                if (start + length > filename.length()) {
                    continue;
                }
                String candidate = filename.substring(start, start + length);
                for (DateTimeFormatter formatter : formatters) {
                    try {
                        return LocalDate.parse(candidate, formatter);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }
}
