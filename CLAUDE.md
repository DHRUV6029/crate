# CrateDB - Claude Code Guide

## Project Overview

CrateDB is a distributed SQL database written in Java with PostgreSQL wire protocol compatibility.

## Build & Development

```bash
# Compile
./mvnw clean compile

# Build distribution (skip tests)
./mvnw package -DskipTests=true

# Install modules locally
./mvnw install -DskipTests=true

# Code quality checks
./mvnw compile forbiddenapis:check
./mvnw compile checkstyle:checkstyle
```

## Running Tests

```bash
# All tests
./mvnw test

# Specific module
./mvnw test -pl server

# Specific test class
./mvnw test -pl server -Dtest=PlannerTest

# Specific test method
./mvnw test -pl server -Dtest=PlannerTest -Dtests.method=testSetTimeZone

# Parallel execution
./mvnw test -DforkCount=4

# Integration/documentation tests
./blackbox/bin/test-docs
ITEST_FILE_NAME_FILTER=filename.rst ./blackbox/bin/test-docs
```

## Architecture

SQL processing pipeline: **Parser → Analyzer → Planner → Executor**

1. **Parser** (`libs/sql-parser/`) - ANTLR-based SQL text → AST
2. **Analyzer** (`server/src/main/java/io/crate/analyze/`) - Semantic processing, type checking
3. **Planner** (`server/src/main/java/io/crate/planner/`) - Logical and physical query plans
4. **Executor** - Distributed execution across cluster

Key input interfaces:
- REST/HTTP API
- PostgreSQL wire protocol (`libs/pgwire/`)

## Key Directories

| Directory | Purpose |
|-----------|---------|
| `server/` | Core SQL engine and database server |
| `libs/sql-parser/` | ANTLR SQL parser |
| `libs/pgwire/` | PostgreSQL wire protocol |
| `app/` | Distribution assembly and Admin UI |
| `plugins/` | Optional extensions (S3, Azure, EC2, etc.) |
| `extensions/` | Feature extensions (UDFs, JMX monitoring) |
| `blackbox/` | Integration and documentation tests |
| `benchmarks/` | JMH performance benchmarks |
| `devs/docs/` | Developer documentation (RST) |

## Code Conventions

- Java 25, Maven 3.6.3+, Temurin JDK auto-provisioned
- Package structure: `io.crate.<component>` (e.g., `io.crate.analyze`, `io.crate.planner`)
- Test naming: `Test*.java`, `*Test.java`, `*Tests.java`, `*IT.java` (integration)
- Checkstyle enforced via `checkstyle.xml`
- Forbidden APIs checked via `forbidden-signatures.txt`

## Testing Guidelines

- **Prefer unit tests over integration tests.** Integration tests (`*IT.java`) spin up a full cluster and are expensive to run. Keep them minimal — only use integration tests for scenarios that genuinely require a running cluster (e.g., end-to-end SQL execution, cross-node behavior).
- **Cover logic with unit tests.** Column mappings, data filtering, OID generation, null handling, etc. should all be tested in lightweight unit tests that don't require a cluster.
- **Integration tests should only verify what unit tests cannot** — e.g., that the table is queryable via SQL, that joins work end-to-end, or that the table is visible in `pg_catalog`.

## Documentation

- Developer docs: `devs/docs/` (reStructuredText)
- User docs: `blackbox/docs/` (Sphinx + RST)
- Build docs: `./blackbox/bin/sphinx` or `./blackbox/bin/sphinx dev`

## Files to Never Commit

- `devs/design/` — Local design documents and LLDs for planning purposes only. Never commit this directory or any files within it.
