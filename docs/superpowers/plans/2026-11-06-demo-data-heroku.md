# Watchwise Demo Data Seed Plan

**Goal:** Add 100 public synthetic users and the requested Watchwise activity to the Heroku app `watchwise-app` using the supplied TMDB series and avatar CSV files.

**Architecture:** Run a one-shot non-web Spring process locally against the Heroku Postgres database, with scheduling and unrelated startup runners disabled. Commit shared Content references in short batches to avoid blocking live unique-key inserts, then batch-write the generated user activity while enforcing target, visibility, counter, and projection rules. Keep external TMDB work outside the main activity transaction; do not add a public endpoint or run the loader on normal app startup.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Maven wrapper, existing TMDB client.

## Global Constraints

- Use `200_series_mais_populares_tmdb.csv` as the exact series pool and assign the 100 distinct URLs in `100_personagens_avatares.csv` one per user.
- Select the first 200 rows with `tmdb_media_type=movie` from `src/main/resources/import/curated-content-aliases.csv`, preserving file order.
- Create `UserRole.USER` accounts with public profiles and accepted follows; templates created by them are `COMMUNITY`.
- Persist only Watchwise `Content` references; TMDB remains the source of media metadata.
- Diary rows are MOVIE/EPISODE only; every row gets a score from 1–10 and approximately 60% get review text.
- Respect the list type groups `{MOVIE, SERIES}`, `SEASON`, and `EPISODE`.
- Use the 14 complete shared challenge dates already present in the database for per-user game results; keep results consistent with all eight game types and do not add game-result likes/comments.
- Apply 0–10 likes and 0–5 comments to each generated target type that supports them; comments themselves receive 0–10 likes. Do not invent direct likes on `Content` or `Top5Entry`.
- No automatic database migration or normal-deploy seed execution.

---

### Task 1: Build a one-shot loader and validate the source inputs

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/demoseed/DemoSeedApplication.java`
- Create: `src/main/java/com/watchwise/watchwise_api/demoseed/DemoDataSeeder.java`
- Read: the supplied series/avatar CSVs and the in-project Letterboxd CSV; do not copy credentials or Heroku config values into files.

- [ ] Parse the series CSV as comma-delimited and the avatar CSV as semicolon-delimited.
- [ ] Require exactly 200 unique positive series TMDB IDs and 100 unique, non-empty HTTPS avatar URLs no longer than the profile picture column limit.
- [ ] Fetch 200 unique non-adult movie IDs from TMDB popular results using the existing client.
- [ ] Make the process opt-in by command-line property and disable Flyway, scheduled jobs, and the Letterboxd alias startup runner.

### Task 2: Seed accounts, content references, diary, lists, follows, Top 5, and Picks

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/demoseed/DemoDataSeeder.java`

- [ ] Create 100 deterministic public USER accounts, assign each supplied avatar once, and hash an inaccessible random password per account.
- [ ] Batch-create 10–20 MOVIE and all released regular-season EPISODE diary rows for 6–12 supplied series per user; set `watchNumber=1`, mark bulk-expanded episode rows `ignore=true`, avoid watchlist/dropped conflicts on these new users, and invalidate each affected user/series history projection.
- [ ] Assign a score to every DiaryEntry and text to approximately 60% of them.
- [ ] Batch-create 0–3 public lists per user with 20–60 items, each list confined to a supported content-type group and positions in order.
- [ ] Create accepted follows with in-degree and out-degree each between 0 and 20.
- [ ] Fill each user's movie and series Top 5 to five entries each.
- [ ] Batch-create 0–2 COMMUNITY templates per user with a valid OPEN category, and 0–4 public Picks per user with one matching selection.

### Task 3: Seed comments, likes, and daily game history

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/demoseed/DemoDataSeeder.java`

- [ ] Generate 0–5 comments on each requested supported target, without recursive replies.
- [ ] Generate 0–10 unique likes on every supported target, including generated comments, while excluding self-likes.
- [ ] Load the 14 existing UTC dates that have all eight global challenge modes; skip incomplete dates.
- [ ] Create one coherent result per user/existing challenge; keep attempt counts, scores, status, completion time, and expired attempt details consistent with the game rules.
- [ ] Make a second invocation skip the completed seed batch instead of duplicating accounts or activity.

### Task 4: Run and verify against Heroku Postgres

**Files:**
- No application deployment or remote push.

- [ ] Connect using the existing Heroku config in process memory; cap the one-shot Hikari pool at two connections and never print secrets.
- [ ] Record pre-seed and post-seed counts for the generated users and related tables.
- [ ] Confirm the loader reports completion and database size remains below the `essential-0` 1 GB storage limit.

