# UserList Item Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax (- [ ]) for tracking.

**Goal:** Paginate the items returned by GET /lists/{listId}, capping episode lists at 24 items per page and every other list scope at 30.

**Architecture:** Keep the existing visibility, enrichment, filtering, and in-memory sorting pipeline. After resolving the list-wide UserListItemScope, build a scope-specific PageRequest, slice the filtered/sorted items, and expose the slice plus item-page metadata in UserListDetailedResponseDTO. The existing /users/{userId}/lists entity-page limit of 10 remains unchanged.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MapStruct, JUnit 5, Mockito, MockMvc, Maven Wrapper.

## Global Constraints

- Follow the approved design in docs/superpowers/specs/2026-10-02-userlist-item-pagination-design.md.
- EPISODE item scopes use a maximum page size of 24.
- MOVIE_OR_SERIES, SEASON, LIST, and MIXED item scopes use a maximum page size of 30.
- Omitted size uses the existing default of 20; positive smaller sizes remain valid; larger sizes are clamped.
- Apply type and genre filters and the existing sort before slicing the requested page.
- Keep itemsCount, watchedPercentage, totalRuntimeMinutes, comments, likes, and other aggregates based on the full list.
- Do not infer the page-size ceiling from a filtered page; resolve itemScope from the complete list.
- Preserve the existing response array at UserListDetailedResponseDTO.items; add item-page metadata beside it.
- Preserve the 10-item cap and minimum validation of GET /users/{userId}/lists from commit a8490c3.
- Use the Maven wrapper on Windows: .\mvnw.cmd.
- Follow TDD: write the behavior test first, run the focused test, observe the expected failure, then implement the smallest passing change.
- Use Conventional Commits without a body or Co-Authored-By trailer; documentation-only changes remain uncommitted per AGENTS.md.
- Never push without explicit user approval.

## File Map

- src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java: accepts page and size for the list-detail GET route.
- src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java: exposes the paginated detail-service signature.
- src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java: resolves the scope-specific ceiling, slices the filtered/sorted result, and preserves full-list aggregates.
- src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailedResponseDTO.java: adds item-page metadata fields beside items.
- src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListMapper.java: maps the new metadata fields.
- src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java: proves page slicing, 24/30 caps, filters, and full-list aggregates.
- src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java: proves controller forwarding of page and size.
- src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java: proves the HTTP response and validation/error contract.
- docs/context/openapi.yaml: documents query parameters, item metadata, and the two ceilings.
- docs/context/business-rules.md: records the implemented pagination rule and its scope source.
- docs/context/progress.md: records the shipped behavior under the existing 2026-10-02 section.

## Task 1: Add failing service coverage for scope-specific pagination

Files:
- Modify: src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java

Interfaces:
- Consumes: the desired UserListServiceImpl.getUserListById(UUID, UUID, ContentType, String, String, String, Integer, Integer) signature.
- Produces: regression tests that capture the list passed to UserListMapper and assert the effective page size, page metadata, and unchanged full-list aggregate count.

- [ ] Step 1: Add the episode-cap test before production implementation

Add a test named shouldClampEpisodeListPageSizeTo24AndReturnSecondPage that prepares 30 episode item DTOs, stubs getItemScope(listId) as UserListItemScope.EPISODE, and invokes:

~~~java
userListService.getUserListById(
        lucasId, list.getId(), null, null, null, null, 2, 99);
~~~

Capture the item list and the five metadata arguments passed to userListMapper.userListToDetailedResponseDto. Assert that the captured item list contains the final six items, itemsPage is 2, itemsSize is 24, itemsTotalElements is 30, itemsTotalPages is 2, itemsHasNext is false, and the mapper still receives itemsCount = 30.

- [ ] Step 2: Add the non-episode ceiling and filtering tests

Add shouldClampNonEpisodeListPageSizeTo30 with 31 movie items and size=99; assert 30 returned items, page size 30, total elements 31, total pages 2, and hasNext=true.

Add shouldApplyTypeAndGenreBeforePagination with enough mixed fixture items for the requested filter to leave more than one page, invoke type=EPISODE, genre=Drama, page=2, and size=24, and assert that the captured page contains only the second filtered page while itemsTotalElements equals the filtered count and itemsCount equals the complete list count.

- [ ] Step 3: Run the focused test and verify the expected red state

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest#shouldClampEpisodeListPageSizeTo24AndReturnSecondPage+shouldClampNonEpisodeListPageSizeTo30+shouldApplyTypeAndGenreBeforePagination"
~~~

Expected result: the test does not pass because the paginated service signature and item metadata arguments do not exist yet. If the test fails for a fixture or matcher error instead, correct the test before changing production code.

## Task 2: Extend the detail response and service contract

Files:
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailedResponseDTO.java
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListMapper.java
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java

Interfaces:
- Consumes: the failing tests from Task 1 and the existing filterAndSortItems pipeline.
- Produces: a service method with pageNumber and pageSize, plus response fields itemsPage, itemsSize, itemsTotalElements, itemsTotalPages, and itemsHasNext.

- [ ] Step 1: Add item-page metadata fields to the record

Extend UserListDetailedResponseDTO after items with:

~~~java
int itemsPage,
int itemsSize,
long itemsTotalElements,
int itemsTotalPages,
boolean itemsHasNext
~~~

Update all constructor call sites and test builders. For bulk creation, pass page 1, the actual number of returned created items as itemsSize, the same number as itemsTotalElements, itemsTotalPages equal to 0 for an empty result or 1 otherwise, and itemsHasNext=false; creation remains an unpaginated mutation response.

- [ ] Step 2: Extend the mapper method explicitly

Change UserListMapper.userListToDetailedResponseDto to accept the five metadata values after itemScope and add explicit MapStruct mappings for every new record component. Update Mockito stubs and verification matchers to include the new arguments.

- [ ] Step 3: Extend the service signature

Change UserListService.getUserListById to:

~~~java
UserListDetailedResponseDTO getUserListById(
        UUID viewerId,
        UUID listId,
        ContentType type,
        String genre,
        String sortBy,
        String sortDirection,
        Integer pageNumber,
        Integer pageSize);
~~~

Update the implementation and every test caller. Keep getUserListProgress unchanged; its full-list progress payload is not the item-page contract being changed.

- [ ] Step 4: Implement the minimal scope-specific page calculation

In UserListServiceImpl.getUserListById, retain the current authorization, enrichment, filter, sort, and full-list aggregate calculations. Resolve itemScope from userListItemService.getItemScope(listId) before slicing, then build the request with:

~~~java
int maxPageSize = itemScope == UserListItemScope.EPISODE ? 24 : 30;
PageRequest itemPageRequest = pageRequestFactory.build(pageNumber, pageSize, maxPageSize);
~~~

Create a PageImpl<UserListItemResponseDTO> from the filtered/sorted list and the page request. Use its content for the DTO, and pass page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages(), and page.hasNext() to the mapper. Ensure the sublist bounds use the page offset and return an empty content list when the offset is beyond the filtered result.

- [ ] Step 5: Run the focused service tests and verify green

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest#shouldClampEpisodeListPageSizeTo24AndReturnSecondPage+shouldClampNonEpisodeListPageSizeTo30+shouldApplyTypeAndGenreBeforePagination"
~~~

Expected result: all three tests pass. Then run the complete service test class:

~~~powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest"
~~~

Expected result: the whole class passes with no failures or errors.

## Task 3: Wire page and size through the controller

Files:
- Modify: src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java
- Modify: src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java

Interfaces:
- Consumes: the eight-argument UserListService.getUserListById method.
- Produces: GET /lists/{listId}?page={page}&size={size} forwarding page and size unchanged to the service.

- [ ] Step 1: Add the controller unit test

Update the detail-route test to call:

~~~java
userListController.getUserListById(listId, null, null, null, null, 2, 24);
~~~

Stub and verify:

~~~java
verify(userListService).getUserListById(
        currentUserId, listId, null, null, null, null, 2, 24);
~~~

- [ ] Step 2: Add optional request parameters to the controller

Add @RequestParam(required = false) Integer page and @RequestParam(required = false) Integer size after sortDirection, and forward both values to the service. Do not add controller-level @Min/@Max; the scope-specific ceiling is known only after the list is loaded and PageRequestFactory already owns page validation.

- [ ] Step 3: Run controller unit tests

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=UserListControllerTest"
~~~

Expected result: all controller unit tests pass, including the updated detail-route forwarding assertion.

## Task 4: Prove the HTTP contract and edge cases

Files:
- Modify: src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java
- Modify: src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java

Interfaces:
- Consumes: the controller route and service page metadata.
- Produces: HTTP-level coverage for page two, caps, empty pages, and invalid page values.

- [ ] Step 1: Add the episode HTTP cap test

Create a public episode list with at least 25 items, request GET /lists/{listId}?page=1&size=99, and assert status 200, $.items.length() <= 24, $.itemsSize == 24, $.itemsTotalElements equals the full filtered count, and $.itemsHasNext is correct.

- [ ] Step 2: Add the non-episode HTTP cap test

Create a movie list with at least 31 items, request GET /lists/{listId}?page=1&size=99, and assert $.items.length() == 30, $.itemsSize == 30, $.itemsTotalPages == 2, and $.itemsHasNext == true.

- [ ] Step 3: Add page-beyond-end and invalid-input coverage

Assert that a valid page after the final page returns 200 with an empty items array and unchanged total metadata. Assert that size=0 and page=0 resolve to the existing ApiError response instead of Spring's default error shape.

- [ ] Step 4: Run the focused integration suite

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=UserListControllerIntegrationTest,UserListServiceImplTest"
~~~

Expected result: exit code 0. If repository tests require Docker and Docker is unavailable, preserve the actual failure output and report that limitation rather than declaring the suite green.

## Task 5: Synchronize contract and project documentation

Files:
- Modify: docs/context/openapi.yaml
- Modify: docs/context/business-rules.md
- Modify: docs/context/progress.md

Interfaces:
- Consumes: the verified response field names and effective page-size behavior from Tasks 2–4.
- Produces: documentation aligned with the implemented route and business rule.

- [ ] Step 1: Document the HTTP parameters and response metadata

Update GET /lists/{listId} to describe optional page and size, the 24-item EPISODE ceiling, the 30-item ceiling for all other scopes, filtering/sorting before pagination, and the itemsPage/itemsSize/itemsTotalElements/itemsTotalPages/itemsHasNext fields. Keep itemsCount documented as the full-list count.

- [ ] Step 2: Record the business rule

Add a UserList rule pointing to UserListServiceImpl.getUserListById: the scope is resolved from the complete list, EPISODE pages are capped at 24, other scopes at 30, and full-list aggregates are not recomputed from the page.

- [ ] Step 3: Record the shipped change in the current day section

Append the implementation result to the existing 2026-10-02 section of docs/context/progress.md. Do not add a future-work section and do not create a documentation-only commit.

## Task 6: Final verification and code commit

Files:
- Verify: all modified source and test files from Tasks 1–4.

- [ ] Step 1: Run the complete focused userlist suite

Run:

~~~powershell
.\mvnw.cmd test "-Dtest=UserListServiceImplTest,UserListControllerTest,UserListControllerIntegrationTest"
~~~

Expected result: exit code 0 with no failures or errors.

- [ ] Step 2: Check the diff

Run:

~~~powershell
git diff --check
git diff --stat
git status --short
~~~

Confirm that pre-existing worktree changes remain intact and that the implementation diff contains only the intended source/test files; documentation remains worktree-only.

- [ ] Step 3: Commit code and tests locally

Run:

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/userlist/controller/UserListController.java src/main/java/com/watchwise/watchwise_api/userlist/service/UserListService.java src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java src/main/java/com/watchwise/watchwise_api/userlist/dto/UserListDetailedResponseDTO.java src/main/java/com/watchwise/watchwise_api/userlist/mapper/UserListMapper.java src/test/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImplTest.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerTest.java src/test/java/com/watchwise/watchwise_api/userlist/controller/UserListControllerIntegrationTest.java
git commit -m "feat(userlist): paginate list items"
~~~

After committing, inspect the commit message and explicitly confirm that it has no Co-Authored-By trailer or other self-attribution. Do not push.
