# ADR-007: Integration tests boot the real Flyway schema

**Date:** 2026-07-10
**Status:** Accepted
**Supersedes:** the previous `ddl-auto: create-drop` integration-test setup
**Related work:** PR #319 (harness), #320 (flake closure), PR #323 / #326 (stabilization)

## Context

Integration tests used to build their schema with Hibernate's `ddl-auto: create-drop`, while every
runtime profile uses Flyway with `ddl-auto: validate`. That divergence meant the test suite could be
green against a schema that **production would never boot on**:

- A missing or wrong migration is invisible — Hibernate just generated whatever the entities implied.
- Migration-only constructs (composite unique indexes, generated columns, seeded reference rows) were
  absent in tests, so any behavior depending on them was untested.
- Conversely, a schema drift introduced by an entity change surfaced for the first time at deploy
  time, as a boot failure.

At the same time the suite needed state isolation between tests, and `create-drop` per class was
paying for a full schema rebuild on every context.

## Decision

Integration tests boot **the same Flyway migrations as production**, then reset data between tests.

1. `spring.flyway.enabled: true` and `ddl-auto: validate` in `application-test.yml`. The test schema
   is migrated `V1 → Vn` exactly like a real environment, and Hibernate only validates against it.
2. A shared Testcontainers MySQL + Redis pair (`TestContainerConfig`) is reused across the suite
   rather than created per class.
3. `DatabaseCleaner` restores a clean slate before each test by truncating **dirty tables only**
   (rows present, or `AUTO_INCREMENT > 1`), preserving Flyway-seeded reference tables via a
   `PRESERVE` set.
4. Tests are tagged `@Tag("integration")`. `./gradlew test` excludes the tag; `:app:integrationTest`
   includes only it.

### Two invariants that are easy to break

- **`DatabaseCleaner` truncates on its own autocommit connection.** MySQL `TRUNCATE` is DDL and
  triggers an implicit commit; running it on the test's connection would force-commit the test's
  rollback-scoped transaction and destroy isolation.
- **`integrationTest` runs with `maxParallelForks = 1`.** All forks share one schema, so parallel
  forks would truncate each other's data. Parallelism requires schema-per-fork isolation first.

## Consequences

**Positive**

- A missing migration now fails the test suite, not the deploy. Schema drift has one detection point.
- Migration-dependent behavior (unique constraints, seeds) is exercised by tests.
- Dirty-table-only truncation avoids InnoDB DDL fsync cost, which is severe on Docker Desktop for
  Windows (1–2s per table).

**Negative / accepted cost**

- Integration tests cannot be parallelized as-is.
- Adding a migration means the test suite pays for it too; migration ordering mistakes surface as
  suite-wide failures rather than a single test.
- Testcontainers requires a working Docker daemon in CI; infrastructure flakiness is retried at the
  CI level (PR #326) rather than by weakening the harness.

## Notes

Do not "fix" a failing integration test by switching it back to `create-drop`. That hides exactly the
class of bug this harness exists to catch.
