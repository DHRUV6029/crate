# LLD: `pg_catalog.pg_user` Table Implementation

**Issue:** [#19165 — pgcompat: `Relation 'pg_catalog.pg_user' unknown`](https://github.com/crate/crate/issues/19165)
**Date:** 2026-03-26
**Status:** Proposed

---

## 1. Problem Statement

When connecting to CrateDB from PostgreSQL-compatible tools like DbVisualizer, the following introspection query fails because `pg_catalog.pg_user` does not exist:

```sql
SELECT n.nspname AS "Name",
       r.usename AS "Owner",
       pg_catalog.obj_description(n.oid, 'pg_namespace') AS "Comment"
FROM pg_catalog.pg_namespace n
LEFT JOIN pg_catalog.pg_user r ON n.nspowner = r.usesysid
```

**Error:** `Relation 'pg_catalog.pg_user' unknown`

This blocks basic schema browsing in third-party database clients.

---

## 2. PostgreSQL Reference

In PostgreSQL, `pg_user` is a **public view** over `pg_shadow`, which is itself a view over `pg_authid`. The chain:

```
pg_authid (catalog table, superuser-only)
  └── pg_shadow (view, superuser-only, exposes real password hash)
       └── pg_user (view, public, masks password as '********')
```

### 2.1 PostgreSQL `pg_user` View Definition

```sql
CREATE VIEW pg_user AS
    SELECT
        usename,
        usesysid,
        usecreatedb,
        usesuper,
        userepl,
        usebypassrls,
        '********'::text AS passwd,
        valuntil,
        useconfig
    FROM pg_shadow;
```

### 2.2 PostgreSQL `pg_shadow` View Definition (for reference)

```sql
CREATE VIEW pg_shadow AS
    SELECT
        rolname          AS usename,
        oid              AS usesysid,
        rolcreatedb      AS usecreatedb,
        rolsuper         AS usesuper,
        rolrepl          AS userepl,
        rolbypassrls     AS usebypassrls,
        rolpassword      AS passwd,
        rolvaliduntil    AS valuntil,
        setconfig        AS useconfig
    FROM pg_authid LEFT JOIN pg_db_role_setting s
        ON (pg_authid.oid = s.setrole AND s.setdatabase = 0)
    WHERE rolcanlogin;
```

**Key:** `pg_user` only shows roles where `rolcanlogin = true` (i.e., actual login users, not abstract roles).

---

## 3. Target Schema

### 3.1 Column Specification

| # | Column | PG Type | CrateDB Type | Source | Notes |
|---|--------|---------|--------------|--------|-------|
| 1 | `usename` | `name` | `STRING` | `Role::name` | Username |
| 2 | `usesysid` | `oid` | `INTEGER` | `OidHash.userOid(name)` | Deterministic OID |
| 3 | `usecreatedb` | `bool` | `BOOLEAN` | `ignored -> null` | No `CREATE DATABASE` in CrateDB |
| 4 | `usesuper` | `bool` | `BOOLEAN` | `Role::isSuperUser` | Superuser flag |
| 5 | `userepl` | `bool` | `BOOLEAN` | `roles::hasALPrivileges` | Replication capability |
| 6 | `usebypassrls` | `bool` | `BOOLEAN` | `ignored -> null` | No RLS in CrateDB |
| 7 | `passwd` | `text` | `STRING` | `PASSWORD_PLACEHOLDER` or `null` | Always masked as `********` |
| 8 | `valuntil` | `timestamptz` | `TIMESTAMPZ` | `ignored -> null` | No password expiry in CrateDB |
| 9 | `useconfig` | `text[]` | `STRING_ARRAY` | `ignored -> null` | No per-user session defaults |

### 3.2 Data Filtering

Only roles where `Role::isUser() == true` are included. This matches PostgreSQL's `WHERE rolcanlogin` filter.

### 3.3 Null Columns Rationale

Following the established CrateDB pattern (same as `PgRolesTable`, `PgDatabaseTable`, `PgNamespaceTable`):
- Column exists with correct name and type for schema compatibility
- Returns `null` when CrateDB doesn't support the underlying feature
- Tools handle `null` gracefully since these are nullable in PostgreSQL too

---

## 4. Implementation Plan

### 4.1 Files to Create

#### File 1: `PgUserTable.java`

**Path:** `server/src/main/java/io/crate/metadata/pgcatalog/PgUserTable.java`

**Purpose:** Defines the `pg_user` table schema and column expressions.

**Pattern:** Follows `PgRolesTable.java` — dynamic table that takes `Roles` service as dependency.

```java
/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.pgcatalog;

import static io.crate.role.metadata.SysUsersTableInfo.PASSWORD_PLACEHOLDER;
import static io.crate.types.DataTypes.BOOLEAN;
import static io.crate.types.DataTypes.INTEGER;
import static io.crate.types.DataTypes.STRING;
import static io.crate.types.DataTypes.STRING_ARRAY;
import static io.crate.types.DataTypes.TIMESTAMPZ;

import io.crate.metadata.RelationName;
import io.crate.metadata.SystemTable;
import io.crate.role.Role;
import io.crate.role.Roles;

public final class PgUserTable {

    public static final RelationName IDENT = new RelationName(PgCatalogSchemaInfo.NAME, "pg_user");

    private PgUserTable() {}

    public static SystemTable<Role> create(Roles roles) {
        return SystemTable.<Role>builder(IDENT)
            .add("usename", STRING, Role::name)
            .add("usesysid", INTEGER, r -> OidHash.userOid(r.name()))
            .add("usecreatedb", BOOLEAN, ignored -> null) // There is no create database functionality
            .add("usesuper", BOOLEAN, Role::isSuperUser)
            .add("userepl", BOOLEAN, roles::hasALPrivileges)
            .add("usebypassrls", BOOLEAN, ignored -> null) // No row-level security in CrateDB
            .add("passwd", STRING, r -> r.password() != null ? PASSWORD_PLACEHOLDER : null)
            .add("valuntil", TIMESTAMPZ, ignored -> null)
            .add("useconfig", STRING_ARRAY, ignored -> null)
            .build();
    }
}
```

### 4.2 Files to Modify

#### File 2: `PgCatalogSchemaInfo.java`

**Path:** `server/src/main/java/io/crate/metadata/pgcatalog/PgCatalogSchemaInfo.java`

**Change:** Register `pg_user` in the `tableInfoMap`.

```java
// Add this entry to the Map.ofEntries() block (after PgViewsTable entry):
Map.entry(PgUserTable.IDENT.name(), PgUserTable.create(roles)),
```

**Add import:**
```java
// No new import needed — PgUserTable is in the same package
```

#### File 3: `PgCatalogTableDefinitions.java`

**Path:** `server/src/main/java/io/crate/metadata/pgcatalog/PgCatalogTableDefinitions.java`

**Change:** Register the data source for `pg_user`.

```java
// Add this entry to the Map.ofEntries() block (after PgRolesTable entry):
Map.entry(PgUserTable.IDENT, new StaticTableDefinition<>(
    () -> completedFuture(
        roles.roles().stream()
            .filter(Role::isUser)
            .toList()
    ),
    PgUserTable.create(roles).expressions(),
    false
)),
```

**Key difference from `PgRolesTable`:** The data source filters with `.filter(Role::isUser)` to only include login-capable roles, matching PostgreSQL's `WHERE rolcanlogin` semantics.

### 4.3 Test Files

> **Testing philosophy:** Integration tests (`*IT.java`) are expensive — they spin up a full cluster. Keep them minimal. Cover logic (column mappings, filtering, null handling) with **unit tests**. Only use integration tests for what unit tests cannot verify (e.g., table is queryable via SQL end-to-end).

#### File 4: Unit Test — `PgUserTableTest.java` (PRIMARY)

**Path:** `server/src/test/java/io/crate/metadata/pgcatalog/PgUserTableTest.java`

**Purpose:** Test all column expressions, data filtering, null handling without starting a cluster.

**Pattern:** Follows `OidHashTest.java` — extends `CrateDummyClusterServiceUnitTest`, uses `RolesHelper` for test role creation.

**Key test utilities:**
- `RolesHelper.userOf(name)` — creates a login-capable user (no password)
- `RolesHelper.userOf(name, password)` — creates a user with password
- `RolesHelper.roleOf(name)` — creates a non-login role (`isUser() == false`)
- `RolesHelper.getSecureHash(password)` — creates a `SecureHash` from a password string
- `Role.CRATE_USER` — built-in superuser

```java
/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.pgcatalog;

import static io.crate.role.metadata.SysUsersTableInfo.PASSWORD_PLACEHOLDER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;

import org.junit.Test;

import io.crate.metadata.ColumnIdent;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.role.metadata.RolesHelper;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;

public class PgUserTableTest extends CrateDummyClusterServiceUnitTest {

    private static Roles rolesOf(Collection<Role> roleList) {
        return () -> roleList;
    }

    @Test
    public void test_usename_returns_role_name() {
        var user = RolesHelper.userOf("testuser");
        var table = PgUserTable.create(rolesOf(List.of(user)));
        var expr = table.expressions().get(ColumnIdent.of("usename")).create();
        expr.setNextRow(user);
        assertThat(expr.value()).isEqualTo("testuser");
    }

    @Test
    public void test_usesysid_returns_oid_hash() {
        var user = RolesHelper.userOf("testuser");
        var table = PgUserTable.create(rolesOf(List.of(user)));
        var expr = table.expressions().get(ColumnIdent.of("usesysid")).create();
        expr.setNextRow(user);
        assertThat(expr.value()).isEqualTo(OidHash.userOid("testuser"));
    }

    @Test
    public void test_usesuper_true_for_crate_superuser() {
        var table = PgUserTable.create(rolesOf(List.of(Role.CRATE_USER)));
        var expr = table.expressions().get(ColumnIdent.of("usesuper")).create();
        expr.setNextRow(Role.CRATE_USER);
        assertThat(expr.value()).isEqualTo(true);
    }

    @Test
    public void test_usesuper_false_for_normal_user() {
        var user = RolesHelper.userOf("normal");
        var table = PgUserTable.create(rolesOf(List.of(user)));
        var expr = table.expressions().get(ColumnIdent.of("usesuper")).create();
        expr.setNextRow(user);
        assertThat(expr.value()).isEqualTo(false);
    }

    @Test
    public void test_passwd_masked_when_password_exists() {
        var user = RolesHelper.userOf("joe", RolesHelper.getSecureHash("secret"));
        var table = PgUserTable.create(rolesOf(List.of(user)));
        var expr = table.expressions().get(ColumnIdent.of("passwd")).create();
        expr.setNextRow(user);
        assertThat(expr.value()).isEqualTo(PASSWORD_PLACEHOLDER);
    }

    @Test
    public void test_passwd_null_when_no_password() {
        var user = RolesHelper.userOf("nopwd");
        var table = PgUserTable.create(rolesOf(List.of(user)));
        var expr = table.expressions().get(ColumnIdent.of("passwd")).create();
        expr.setNextRow(user);
        assertThat(expr.value()).isNull();
    }

    @Test
    public void test_unsupported_columns_return_null() {
        var user = RolesHelper.userOf("testuser");
        var table = PgUserTable.create(rolesOf(List.of(user)));

        for (String col : new String[]{"usecreatedb", "usebypassrls", "valuntil", "useconfig"}) {
            var expr = table.expressions().get(ColumnIdent.of(col)).create();
            expr.setNextRow(user);
            assertThat(expr.value())
                .as("Column '%s' should be null for unsupported features", col)
                .isNull();
        }
    }

    @Test
    public void test_table_has_exactly_9_columns() {
        var table = PgUserTable.create(rolesOf(List.of()));
        assertThat(table.rootColumns()).hasSize(9);
    }
}
```

#### File 5: Integration Test — `PgCatalogITest.java` (MINIMAL)

**Path:** `server/src/test/java/io/crate/integrationtests/PgCatalogITest.java`

**Purpose:** Only verify what unit tests cannot — that the table is queryable via SQL end-to-end and the issue's exact query works. Follows the existing pattern in `PgCatalogITest` (uses `execute()` + `assertThat(response)`).

> Note: `PgCatalogITest` already has `@After dropAllUsers()` cleanup, so user/role creation in tests is safe.

```java
@Test
public void test_pg_user() {
    execute("CREATE USER \"Arthur\" WITH (password='foo')");
    execute("CREATE USER \"John\"");
    execute("CREATE ROLE \"NonLoginRole\"");

    execute("SELECT usename, usesuper, passwd " +
        "FROM pg_catalog.pg_user ORDER BY usename");
    // NonLoginRole must NOT appear — pg_user only shows login-capable users
    assertThat(response).hasRows(
        "Arthur| false| ********",
        "John| false| NULL",
        "crate| true| NULL");

    // Verify usesysid (OID) is populated for all rows
    execute("SELECT usesysid FROM pg_catalog.pg_user");
    assertThat(response).hasRowCount(3);
    for (int i = 0; i < response.rowCount(); i++) {
        assertThat(response.rows()[i][0]).isNotNull();
    }
}

@Test
public void test_pg_user_join_with_pg_namespace_issue_19165() {
    // Exact query from issue #19165 (DbVisualizer)
    execute("""
        SELECT n.nspname AS "Name",
               r.usename AS "Owner",
               pg_catalog.obj_description(n.oid, 'pg_namespace') AS "Comment"
        FROM pg_catalog.pg_namespace n
        LEFT JOIN pg_catalog.pg_user r ON n.nspowner = r.usesysid
        """);
    // Verify query runs without error and returns rows for each schema
    assertThat(response).hasColumns("Name", "Owner", "Comment");
}
```

> Note: No new imports needed in `PgCatalogITest.java` — existing `io.crate.testing.Asserts.assertThat` and `org.assertj.core.api.Assertions.assertThat` are sufficient.

#### File 6: Privilege Test Update — `PrivilegesIntegrationTest.java`

**Path:** `server/src/test/java/io/crate/integrationtests/PrivilegesIntegrationTest.java`

**Purpose:** `testAccessesToPgClassEntriesWithRespectToPrivileges()` (line 500) hardcodes the list of all pg_catalog + information_schema tables visible to unprivileged users via `pg_class`. Adding `pg_user` to the schema means this test **will fail** unless `"pg_user"` is added to the expected list.

**Change:** Add `"pg_user",` between `"pg_type",` and `"pg_views",` (alphabetical order, line ~548):

```java
            "pg_type",
            "pg_user",     // ← ADD THIS LINE
            "pg_views",
```

### 4.4 Privilege & Auth Model

**No row-level privilege filtering needed.** This follows the exact same pattern as `pg_roles`:

| Table | Constructor | Row filter | Rationale |
|-------|-------------|------------|-----------|
| `pg_roles` | 3-arg (no BiPredicate) | None — all users see all roles | PostgreSQL: `pg_roles` is public |
| `pg_user` (ours) | 3-arg (no BiPredicate) | None — all users see all users | PostgreSQL: `pg_user` is public |
| `pg_namespace` | 2-arg (with BiPredicate) | Filters by schema privilege | Schemas are access-controlled |
| `pg_tables` | 2-arg (with BiPredicate) | Filters by table privilege | Tables are access-controlled |

In PostgreSQL, `pg_user` is a **public view** — any connected user can see all users (with passwords masked). This is the same visibility as `pg_roles`. The `StaticTableDefinition` uses the 3-arg constructor:

```java
new StaticTableDefinition<>(
    () -> completedFuture(roles.roles().stream().filter(Role::isUser).toList()),
    PgUserTable.create(roles).expressions(),
    false    // no I/O involved
)
```

This is identical to how `sys.users` supplies its data (see `SysTableDefinitions.java:88`):
```java
() -> completedFuture(roles.roles().stream().filter(Role::isUser).toList())
```

**Password security:** The `passwd` column always returns `"********"` (via `PASSWORD_PLACEHOLDER`) or `null` — never the actual hash. This matches PostgreSQL's `pg_user` behavior (real hashes are only in `pg_shadow`, which we don't implement).

---

## 5. Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     SQL Query                                    │
│  SELECT ... FROM pg_catalog.pg_user r                           │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                   PgCatalogSchemaInfo                            │
│  tableInfoMap.get("pg_user") → PgUserTable (SystemTable<Role>)  │
│  Provides: column metadata, types, expressions                  │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│               PgCatalogTableDefinitions                          │
│  StaticTableDefinition for PgUserTable.IDENT                    │
│  Data source: roles.roles().stream()                            │
│                   .filter(Role::isUser)  ← only login roles     │
│                   .toList()                                      │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Roles Service                                 │
│  Returns Collection<Role> from cluster state                    │
│  Each Role has: name, isSuperUser, isUser, password, etc.       │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Column Expressions                              │
│  usename     → Role::name                                       │
│  usesysid    → OidHash.userOid(name)                            │
│  usecreatedb → null                                             │
│  usesuper    → Role::isSuperUser                                │
│  userepl     → roles::hasALPrivileges                           │
│  usebypassrls→ null                                             │
│  passwd      → "********" or null                               │
│  valuntil    → null                                             │
│  useconfig   → null                                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. File Change Summary

| Action | File | Lines Changed |
|--------|------|---------------|
| **CREATE** | `server/src/main/java/io/crate/metadata/pgcatalog/PgUserTable.java` | ~40 lines |
| **MODIFY** | `server/src/main/java/io/crate/metadata/pgcatalog/PgCatalogSchemaInfo.java` | +1 line |
| **MODIFY** | `server/src/main/java/io/crate/metadata/pgcatalog/PgCatalogTableDefinitions.java` | +7 lines |
| **CREATE** | `server/src/test/java/io/crate/metadata/pgcatalog/PgUserTableTest.java` | ~90 lines (unit tests — primary coverage) |
| **MODIFY** | `server/src/test/java/io/crate/integrationtests/PgCatalogITest.java` | +25 lines (minimal — 2 tests: basic + issue query) |
| **MODIFY** | `server/src/test/java/io/crate/integrationtests/PrivilegesIntegrationTest.java` | +1 line (add `"pg_user"` to expected pg_class table list) |

**Total: ~164 lines of code across 6 files**

---

## 7. Dependencies

### Existing classes used (no new dependencies):

**Production:**
- `io.crate.metadata.SystemTable` — table builder
- `io.crate.metadata.RelationName` — table identifier
- `io.crate.metadata.pgcatalog.OidHash` — OID generation
- `io.crate.role.Role` — user/role data model
- `io.crate.role.Roles` — roles service (interface)
- `io.crate.role.metadata.SysUsersTableInfo.PASSWORD_PLACEHOLDER` — masked password constant
- `io.crate.expression.reference.StaticTableDefinition` — data source binding
- `io.crate.types.DataTypes` — column type constants

**Test:**
- `io.crate.role.metadata.RolesHelper` — factory methods: `userOf()`, `roleOf()`, `getSecureHash()`
- `io.crate.test.integration.CrateDummyClusterServiceUnitTest` — lightweight unit test base class
- `io.crate.metadata.ColumnIdent` — column reference for expression lookup
- `org.elasticsearch.test.IntegTestCase` — integration test base class (already used by `PgCatalogITest`)

### No new Maven dependencies required.

---

## 8. Verification Plan

### 8.1 Unit Tests (fast, run first)
```bash
# Run pg_user unit tests — covers column logic, filtering, nulls
./mvnw test -pl server -Dtest=PgUserTableTest
```

### 8.2 Integration Tests (expensive, run last, kept minimal)
```bash
# Run pg_catalog integration tests — only 2 tests added for pg_user
./mvnw test -pl server -Dtest=PgCatalogITest
```

### 8.3 Code Quality
```bash
./mvnw compile forbiddenapis:check
./mvnw compile checkstyle:checkstyle
```

### 8.4 Manual Verification
1. Start CrateDB locally
2. Connect with `psql` and run:
   ```sql
   SELECT * FROM pg_catalog.pg_user;
   ```
3. Run the exact DbVisualizer query from the issue:
   ```sql
   SELECT n.nspname AS "Name",
          r.usename AS "Owner",
          pg_catalog.obj_description(n.oid, 'pg_namespace') AS "Comment"
   FROM pg_catalog.pg_namespace n
   LEFT JOIN pg_catalog.pg_user r ON n.nspowner = r.usesysid
   ```
4. Verify no error and results are returned.

---

## 9. Risks & Considerations

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| `Map.ofEntries()` exceeds 30 entries (Java limit) | Low — currently 30 entries, adding 1 makes 31 | Check if CrateDB uses a custom builder or switch to `HashMap` if needed |
| `nspowner` in `pg_namespace` always returns `0` | Known limitation | JOIN with `pg_user` will produce NULL owner for all schemas via LEFT JOIN — acceptable, matches current behavior |
| Password visibility concern | None | Password is always masked as `********`, same as `PgRolesTable` |

### 9.1 Note on `Map.ofEntries()` Limit

Java's `Map.ofEntries()` supports up to **any number** of entries (it's varargs). The limitation of 10 applies only to `Map.of()`. So adding one more entry is safe.

---

## 10. Out of Scope

The following are explicitly **not** part of this implementation:

- **`pg_shadow`** — superuser-only view with real password hashes. Not needed for the issue query. Can be added later if needed.
- **`pg_authid`** — base catalog table. Same as above.
- **Object comments** — `obj_description()` already exists but returns `null`. Adding actual comment storage is a separate feature.
- **`nspowner` population** — `pg_namespace.nspowner` currently returns `0` for all schemas. Making it return real owner OIDs is a separate enhancement.
