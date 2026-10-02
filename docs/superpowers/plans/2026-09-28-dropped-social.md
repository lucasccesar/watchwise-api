# Dropped review social interactions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow dropped reviews to receive likes and comments, expose those interactions in the feed, and include dropped reviews in content review listings.

**Architecture:** Extend the existing polymorphic `Comment` and `Like` targets with a `DroppedEntry` target and keep authorization in the corresponding services. Add a paginated union query for diary and dropped review keys, then assemble the existing diary-shaped review data plus an explicit source discriminator.

**Tech Stack:** Spring Boot, Java 21, Spring Data JPA, Flyway, MapStruct, PostgreSQL, JUnit 5, Mockito, MockMvc, Testcontainers.

## Global Constraints

- Do not add a score to `DroppedEntry`.
- Keep `DroppedEntry.comment` as the review text.
- Do not add dropped reviews to any content-review endpoint other than `GET /contents/{contentId}/reviews`.
- Preserve owner/public/accepted-follower visibility and idempotent like semantics.
- Update `openapi.yaml`, `database-schema.md`, `business-rules.md`, and `progress.md` with the code change; documentation remains worktree-only.

### Task 1: Database and target entities

**Files:**
- Create: `src/main/resources/db/migration/V60__add-dropped-social-targets.sql`
- Modify: `dropped/entity/DroppedEntry.java`, `comment/entity/Comment.java`, `like/entity/Like.java`, their repositories and DTOs.
- Test: repository/entity integration tests.

- [ ] Add the dropped likes counter and comment/like foreign-key target columns.
- [ ] Extend exactly-one-target checks, unique constraints, and indexes.
- [ ] Add JPA associations and repository queries for dropped targets.
- [ ] Run focused repository tests.

### Task 2: Likes and comments

**Files:**
- Modify: `like/service/LikeService.java`, `LikeServiceImpl`, `LikeController`; `comment/service/CommentService.java`, `CommentServiceImpl`, `CommentController`, `CommentRepository`, `CommentMapper`, `CommentResponseDTO`.
- Test: corresponding service/controller tests.

- [ ] Add idempotent dropped-like insert/delete and counter maintenance.
- [ ] Add dropped comment list/create routes and same-target reply validation.
- [ ] Apply dropped visibility checks to reads, writes, likes, and comment likes.
- [ ] Run focused unit tests.

### Task 3: Feed and dropped responses

**Files:**
- Modify: dropped DTO/mapper/service, `FeedServiceImpl` and feed tests.

- [ ] Resolve viewer likes in batches for dropped list/feed reads.
- [ ] Populate `likesCount` and `likedByMe` without changing the existing drop/comment semantics.
- [ ] Run feed and dropped tests.

### Task 4: Mixed content reviews

**Files:**
- Create: `ContentReviewSource` and `ContentReviewResponseDTO`.
- Modify: diary review service/controller/repository and tests.

- [ ] Query visible diary and dropped rows with non-null comments as one ordered page.
- [ ] Assemble diary-specific fields and dropped-specific null fields with `source`.
- [ ] Verify mixed ordering, visibility, page metadata, likes, and target identifiers.

### Task 5: Contract and project documentation

**Files:** `docs/context/openapi.yaml`, `docs/context/database-schema.md`, `docs/context/business-rules.md`, `docs/context/progress.md`.

- [ ] Document routes, response fields, target constraints, visibility, and cascade behavior.
- [ ] Run the focused suite and then the full Maven suite where infrastructure permits.
