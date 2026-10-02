# User List Page Size Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restrict `GET /users/{userId}/lists` to a page size of 10 without changing other paginated endpoints.

**Architecture:** Keep `PageRequestFactory`'s shared defaults unchanged. Add a reusable factory overload for a caller-specific maximum with sorting, then let `UserListServiceImpl` validate the endpoint-specific minimum and request the maximum of 10 in both sorted and unsorted paths.

**Tech Stack:** Java 21, Spring Data `PageRequest`, JUnit 5, AssertJ, Maven wrapper, OpenAPI YAML.

## Global Constraints

- Values below 10 for this endpoint return the project's typed `BadRequestException` and therefore `400`.
- Values above 10 for this endpoint are clamped to 10.
- Other endpoints using `PageRequestFactory` retain the existing default of 20 and maximum of 1000.
- Use the Maven wrapper and update synchronized API documentation and `docs/context/progress.md`.

---

### Task 1: Lock the endpoint behavior in tests

**Files:**
- Modify: `src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java`

**Interfaces:**
- Consumes: existing `UserListServiceImpl#getUserLists` test fixture and `PageRequest` captor.
- Produces: regression coverage for default 10, minimum rejection, maximum clamping, and exact size 10.

- [x] **Step 1: Write the failing tests**

  Update the existing `getUserLists` pagination assertions so the omitted size
  expects 10, an input of 11 is clamped to 10, and an input of 10 is accepted.
  Add a test that calls `getUserLists(..., 9, ...)` and expects
  `BadRequestException` with no repository query.

- [x] **Step 2: Run the focused test to verify it fails**

  Run:

  ```powershell
  .\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\\.m2\\repository" test "-Dtest=UserListServiceImplTest"
  ```

  Expected: the new assertions fail because the current endpoint defaults to 20,
  allows 9, and clamps only at the shared maximum of 1000.

### Task 2: Implement the endpoint-specific bound

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/common/pagination/PageRequestFactory.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java`

**Interfaces:**
- Consumes: existing `PageRequestFactory` overloads and user-list repository calls.
- Produces: sorted and unsorted user-list queries using maximum 10, with a minimum-10 validation.

- [x] **Step 1: Add the minimal factory overload**

  Expose an overload accepting `(pageNumber, pageSize, maxPageSize, sortBy,
  sortDirection)` and delegate to the existing private construction path, keeping
  the shared constants unchanged.

- [x] **Step 2: Apply the user-list bound**

  Add a user-list page-size constant of 10, reject non-null values below 10 with
  `BadRequestException`, and use the new factory overload with maximum 10 in the
  aggregate-sort and normal-sort branches.

- [x] **Step 3: Run the focused test to verify it passes**

  Run the same `UserListServiceImplTest` command from Task 1 and expect all tests
  in that class to pass.

### Task 3: Synchronize the API contract and project log

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: the endpoint behavior implemented in Tasks 1-2.
- Produces: contract documentation that describes `size` as exactly 10 for this endpoint and a chronological shipped-change entry.

- [x] **Step 1: Document the endpoint parameter**

  Replace the shared `size` parameter reference only under
  `/users/{userId}/lists` with an inline query parameter having
  `minimum: 10`, `maximum: 10`, and `default: 10`; describe values below 10 as
  invalid and values above 10 as capped by the service.

- [x] **Step 2: Update the current-day progress entry**

  Append the shipped pagination change to the existing `2026-10-01` section in
  `docs/context/progress.md`.

- [x] **Step 3: Run verification**

  Run:

  ```powershell
  .\mvnw.cmd -Duser.home="D:\\Users\\Lucas C\\Documentos\\codigos\\watchwise-api" -Dmaven.repo.local="C:\\Users\\Lucas C\\\.m2\\repository" test "-Dtest=UserListServiceImplTest,PageRequestFactoryTest"
  ```

  Then inspect `git diff --check` and the final diff to confirm only the intended
  endpoint, tests, and synchronized documentation changed.
