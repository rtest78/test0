# Collections Daily UI (Spring Boot + Thymeleaf)

This application parses daily Collections JSON extracts and displays accounts by business date.

## Features

- Spring Boot 3 and Thymeleaf UI
- Reads large JSON Lines / NDJSON files one line at a time
- Parses escaped JSON inside `input.data` and `output.data`
- Uses `memberNumber` as the member key
- Supports multiple `accountNumber` values per member
- Daily dashboard showing members, accounts, rejected lines, new accounts, missing accounts, and changed accounts
- Daily account table
- Exact member-number filter
- Expandable request and response fields
- Missing-account section compared with Day 1

## Requirements

- Java 17+
- Maven 3.9+

## Configure the input folder

Edit `src/main/resources/application.properties`:

```properties
collections.data.directory=C:/Collections/Input
```

You can also override it at runtime:

```bash
java -jar target/collections-thymeleaf-ui-1.0.0.jar \
  --collections.data.directory=C:/Collections/Input
```

Supported files:

- `.json`
- `.jsonl`
- `.ndjson`
- `.txt`

The app derives the business date from names containing one of these formats:

- `2026-06-26`
- `20260626`
- `06262026`

## Start the application

```bash
mvn clean spring-boot:run
```

Then open:

```text
http://localhost:8080
```

## Pages

- `/` — daily dashboard
- `/accounts` — daily account view and member-number filter

## Comparison behavior

The earliest dated file is treated as Day 1. Every later day is compared with Day 1.

- `NEW`: account exists on the selected day but not Day 1
- `MISSING`: account existed on Day 1 but is absent on the selected day
- `CHANGED`: same member/account exists, but request or response fields differ
- `UNCHANGED`: no difference from Day 1

## Input assumption

Each physical line contains one outer JSON object. `input.data` and `output.data` can contain escaped nested JSON strings, as shown in the supplied extract screenshot.
