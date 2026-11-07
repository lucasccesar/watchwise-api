package com.watchwise.watchwise_api.demoseed;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.diarycompletion.service.impl.DiaryProjectionInvalidator;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final int USER_COUNT = 100;
    private static final int MOVIE_COUNT = 200;
    private static final int SERIES_COUNT = 200;
    private static final int JDBC_BATCH_SIZE = 500;
    private static final int DIARY_BATCH_SIZE = 500;
    private static final int LIKE_BATCH_SIZE = 10_000;
    private static final int BASE_MOVIE_DIARY_COUNT = 10;
    private static final int BASE_SERIES_COUNT = 6;
    private static final int MAX_EXTRA_MOVIES = 10;
    private static final int MAX_EXTRA_SERIES = 6;
    private static final int DEMO_GAME_DAYS = 14;
    private static final int GAME_CHALLENGE_LOOKBACK_DAYS = 60;
    private static final String DEMO_USERNAME_PREFIX = "watchwise_demo_20261106_";
    private static final String DEMO_EMAIL_DOMAIN = "@watchwise.invalid";
    private static final String[] REVIEW_TEXTS = {
            "Gostei bastante da atmosfera e do desenvolvimento dos personagens.",
            "Uma experiência envolvente, com cenas que ficaram comigo depois de assistir.",
            "O ritmo funciona bem e a história entrega momentos muito bons.",
            "Curti a direção e a forma como os temas aparecem ao longo da obra.",
            "Foi uma ótima surpresa; quero rever alguns detalhes com mais calma.",
            "A atuação e a trilha sonora deram bastante força para a narrativa.",
            "Tem alguns altos e baixos, mas no conjunto valeu muito a sessão.",
            "Gostei da construção do mundo e da conclusão desta parte da história.",
            "Uma obra divertida e bem conduzida, com personagens interessantes.",
            "O visual é marcante e combina muito bem com o tom da narrativa.",
            "Não era exatamente o que eu esperava, mas acabou me conquistando.",
            "Uma boa sessão para quem gosta de histórias com esse tipo de clima."
    };
    private static final String[] SOCIAL_COMMENT_TEXTS = {
            "Boa escolha! Também gostei bastante dessa obra.",
            "Essa ficou na minha lista para assistir.",
            "Concordo com essa seleção, tem momentos excelentes.",
            "Gostei da recomendação; vou conferir quando puder.",
            "A fotografia e a trilha sonora funcionam muito bem aqui.",
            "Essa história continua rendendo boas conversas.",
            "Também é uma das minhas favoritas desse gênero.",
            "Ótima lembrança, fazia tempo que eu não pensava nessa obra.",
            "Curti a seleção e a descrição da lista.",
            "Essa escolha combina muito com o tema.",
            "Adorei ver esse título por aqui.",
            "Boa recomendação para uma maratona."
    };
    private static final String[] LIST_NAMES = {
            "Para rever", "Favoritos da semana", "Descobertas", "Noite de cinema",
            "Maratona pessoal", "Histórias inesquecíveis", "Quero assistir de novo"
    };
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final FollowerRepository followerRepository;
    private final Top5EntryRepository top5EntryRepository;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final DailyChallengeRepository dailyChallengeRepository;
    private final UserDailyGameResultRepository userDailyGameResultRepository;
    private final UserListRepository userListRepository;
    private final UserListItemRepository userListItemRepository;
    private final PicksTemplateRepository picksTemplateRepository;
    private final PicksTemplateCategoryRepository picksTemplateCategoryRepository;
    private final PickRepository pickRepository;
    private final PickSelectionRepository pickSelectionRepository;
    private final DiaryProjectionInvalidator diaryProjectionInvalidator;
    private final TmdbClient tmdbClient;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Value("${app.demo-seed.series-file}")
    private String seriesFile;

    @Value("${app.demo-seed.avatars-file}")
    private String avatarsFile;

    @Value("${app.demo-seed.movies-file:src/main/resources/import/curated-content-aliases.csv}")
    private String moviesFile;

    private final Random random = new Random(20261106L);

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<SeriesInput> suppliedSeries = readSeries(Path.of(seriesFile));
        List<AvatarInput> avatars = readAvatars(Path.of(avatarsFile));
        List<MovieData> movies = readMovies(Path.of(moviesFile));
        if (suppliedSeries.size() != SERIES_COUNT || avatars.size() != USER_COUNT) {
            throw new IllegalStateException("Demo seed requires exactly 200 series and 100 avatar rows");
        }
        if (movies.size() != MOVIE_COUNT) {
            throw new IllegalStateException("Demo seed requires exactly 200 Letterboxd movie rows");
        }
        refuseExistingDemoUsers();

        log.info("Loaded {} movies from the first matching Letterboxd CSV rows", movies.size());
        log.info("Validating {} series against TMDB", suppliedSeries.size());
        List<SeriesData> allSeries = loadSeriesData(suppliedSeries, today);
        List<SeriesData> diarySeries = allSeries.stream().filter(series -> !series.episodes().isEmpty()).toList();
        if (diarySeries.size() < 12) {
            throw new IllegalStateException("TMDB returned fewer than 12 series with released regular episodes");
        }
        List<ChallengeData> challenges = loadExistingCompleteChallenges(today);
        if (challenges.size() != DEMO_GAME_DAYS * DailyGameType.values().length) {
            throw new IllegalStateException("Expected 14 complete existing game dates across all eight modes");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Map<ContentKey, ContentRefData> content = createContentReferences(movies, allSeries, now);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        SeedSummary summary = transaction.execute(status -> seedDatabase(
                avatars, movies, allSeries, diarySeries, challenges, content, today, now));
        if (summary == null) {
            throw new IllegalStateException("Demo seed transaction returned no summary");
        }
        log.info("Demo seed complete: {} users, {} movies, {} series, {} diary entries, {} lists, {} follows, "
                        + "{} picks templates, {} picks, {} comments, {} likes, {} game results",
                summary.users(), summary.movies(), summary.series(), summary.diaryEntries(), summary.lists(),
                summary.follows(), summary.templates(), summary.picks(), summary.comments(), summary.likes(),
                summary.gameResults());
    }

    private SeedSummary seedDatabase(List<AvatarInput> avatars, List<MovieData> movies,
                                     List<SeriesData> allSeries, List<SeriesData> diarySeries,
                                     List<ChallengeData> challenges, Map<ContentKey, ContentRefData> content,
                                     LocalDate today, LocalDateTime now) {
        List<User> users = createUsers(avatars, now);
        Map<UUID, List<MovieData>> moviesByUser = assignMovies(users, movies);
        Map<UUID, List<SeriesData>> seriesByUser = assignSeries(users, diarySeries);

        List<DiaryTarget> diaryTargets = createDiaryEntries(users, moviesByUser, seriesByUser, content, today, now);
        List<UserListTarget> listTargets = createLists(users, movies, allSeries, content, now);
        long top5Count = createTop5(users, movies, allSeries, content, now);
        long followCount = createFollows(users, now);
        List<TemplateTarget> templateTargets = createTemplates(users, now);
        List<PickTargetRecord> pickTargets = createPicks(users, templateTargets, movies, allSeries, content, now);
        InteractionCounts interactions = createCommentsAndLikes(
                users, content.values().stream().toList(), diaryTargets, listTargets,
                pickTargets, templateTargets, now);
        long gameResultCount = createGameResults(users, challenges);

        entityManager.flush();
        return new SeedSummary(users.size(), movies.size(), allSeries.size(), diaryTargets.size(),
                listTargets.size(), followCount, templateTargets.size(), pickTargets.size(),
                interactions.comments(), interactions.likes(), gameResultCount, top5Count);
    }

    private List<User> createUsers(List<AvatarInput> avatars, LocalDateTime now) {
        List<User> users = new ArrayList<>(USER_COUNT);
        for (int index = 0; index < USER_COUNT; index++) {
            AvatarInput avatar = avatars.get(index);
            String suffix = String.format("%03d", index + 1);
            String username = DEMO_USERNAME_PREFIX + suffix;
            String email = DEMO_USERNAME_PREFIX + suffix + DEMO_EMAIL_DOMAIN;
            String name = avatar.name().isBlank() ? "Pessoa de demonstração " + suffix : avatar.name();
            if (name.length() > 120) name = name.substring(0, 120);
            byte[] passwordBytes = new byte[32];
            SECURE_RANDOM.nextBytes(passwordBytes);
            String privatePassword = java.util.HexFormat.of().formatHex(passwordBytes);
            users.add(User.builder()
                    .role(UserRole.USER)
                    .username(username)
                    .name(name)
                    .email(email)
                    .password(passwordEncoder.encode(privatePassword))
                    .description("Perfil fictício para demonstração.")
                    .profilePicture(avatar.image())
                    .isProfilePublic(true)
                    .isEmailVerified(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .authVersion(0L)
                    .preferredLanguage("pt-BR")
                    .preferredRegion("BR")
                    .build());
        }
        List<User> saved = userRepository.saveAllAndFlush(users);
        entityManager.clear();
        return saved;
    }

    private Map<ContentKey, ContentRefData> createContentReferences(
            List<MovieData> movies, List<SeriesData> series, LocalDateTime now) {
        Map<ContentKey, Content> existing = new LinkedHashMap<>();
        loadExistingContent(existing, movies, series);

        Map<ContentKey, ContentDraft> drafts = contentDrafts(movies, series);
        List<Content> missing = new ArrayList<>();
        for (Map.Entry<ContentKey, ContentDraft> entry : drafts.entrySet()) {
            if (existing.containsKey(entry.getKey())) continue;
            ContentDraft draft = entry.getValue();
            ContentKey key = draft.key();
            missing.add(Content.builder()
                    .tmdbId(key.tmdbId())
                    .type(key.type())
                    .seriesTmdbId(key.seriesTmdbId())
                    .seasonNumber(key.seasonNumber())
                    .episodeNumber(key.episodeNumber())
                    .isSeasonFinale(draft.isSeasonFinale())
                    .isSeriesFinale(draft.isSeriesFinale())
                    .runtimeMinutes(draft.runtimeMinutes())
                    .createdAt(now)
                    .updatedAt(now)
                    .refreshGeneration(0L)
                    .build());
        }
        for (int offset = 0; offset < missing.size(); offset += JDBC_BATCH_SIZE) {
            List<Content> batch = missing.subList(offset, Math.min(offset + JDBC_BATCH_SIZE, missing.size()));
            try {
                contentRepository.saveAllAndFlush(batch).forEach(row -> existing.put(ContentKey.of(row), row));
            } catch (DataIntegrityViolationException conflict) {
                loadExistingContent(existing, movies, series);
                List<Content> retry = batch.stream()
                        .filter(row -> !existing.containsKey(ContentKey.of(row)))
                        .map(row -> contentEntity(drafts.get(ContentKey.of(row)), now))
                        .toList();
                if (retry.size() == batch.size()) throw conflict;
                if (!retry.isEmpty()) {
                    try {
                        contentRepository.saveAllAndFlush(retry)
                                .forEach(row -> existing.put(ContentKey.of(row), row));
                    } catch (DataIntegrityViolationException retryFailure) {
                        loadExistingContent(existing, movies, series);
                        if (retry.stream().anyMatch(row -> !existing.containsKey(ContentKey.of(row)))) {
                            throw retryFailure;
                        }
                    }
                }
            }
            if ((offset + batch.size()) % 5_000 == 0 || offset + batch.size() == missing.size()) {
                log.info("Persisted {}/{} missing Content references", offset + batch.size(), missing.size());
            }
        }

        Map<ContentKey, ContentRefData> references = new LinkedHashMap<>();
        for (Map.Entry<ContentKey, ContentDraft> entry : drafts.entrySet()) {
            Content row = existing.get(entry.getKey());
            if (row == null) throw new IllegalStateException("Content reference was not persisted: " + entry.getKey());
            references.put(entry.getKey(), new ContentRefData(row.getId(), entry.getKey()));
        }
        return references;
    }

    private void loadExistingContent(Map<ContentKey, Content> existing,
                                     List<MovieData> movies, List<SeriesData> series) {
        Set<String> seriesIds = series.stream().map(SeriesData::tmdbId).collect(java.util.stream.Collectors.toSet());
        Set<String> movieIds = movies.stream().map(MovieData::tmdbId).collect(java.util.stream.Collectors.toSet());
        contentRepository.findByTypeAndTmdbIdIn(ContentType.MOVIE, movieIds)
                .forEach(value -> existing.put(ContentKey.of(value), value));
        contentRepository.findByTypeAndTmdbIdIn(ContentType.SERIES, seriesIds)
                .forEach(value -> existing.put(ContentKey.of(value), value));
        contentRepository.findByTypeAndSeriesTmdbIdIn(ContentType.SEASON, seriesIds)
                .forEach(value -> existing.put(ContentKey.of(value), value));
        contentRepository.findByTypeAndSeriesTmdbIdIn(ContentType.EPISODE, seriesIds)
                .forEach(value -> existing.put(ContentKey.of(value), value));
    }

    private Content contentEntity(ContentDraft draft, LocalDateTime now) {
        ContentKey key = draft.key();
        return Content.builder()
                .tmdbId(key.tmdbId())
                .type(key.type())
                .seriesTmdbId(key.seriesTmdbId())
                .seasonNumber(key.seasonNumber())
                .episodeNumber(key.episodeNumber())
                .isSeasonFinale(draft.isSeasonFinale())
                .isSeriesFinale(draft.isSeriesFinale())
                .runtimeMinutes(draft.runtimeMinutes())
                .createdAt(now)
                .updatedAt(now)
                .refreshGeneration(0L)
                .build();
    }

    private Map<ContentKey, ContentDraft> contentDrafts(List<MovieData> movies, List<SeriesData> series) {
        Map<ContentKey, ContentDraft> result = new LinkedHashMap<>();
        for (MovieData movie : movies) {
            addDraft(result, new ContentDraft(new ContentKey(ContentType.MOVIE, movie.tmdbId(), null, null, null),
                    null, null, null));
        }
        for (SeriesData item : series) {
            addDraft(result, new ContentDraft(new ContentKey(ContentType.SERIES, item.tmdbId(), null, null, null),
                    null, null, null));
            for (SeasonData season : item.seasons()) {
                addDraft(result, new ContentDraft(new ContentKey(ContentType.SEASON, null, item.tmdbId(),
                        season.seasonNumber(), null), null, null, season.isSeriesFinale()));
                for (EpisodeData episode : season.episodes()) {
                    addDraft(result, new ContentDraft(new ContentKey(ContentType.EPISODE, null, item.tmdbId(),
                            season.seasonNumber(), episode.episodeNumber()), episode.runtimeMinutes(),
                            episode.isSeasonFinale(), episode.isSeriesFinale()));
                }
            }
        }
        return result;
    }

    private void addDraft(Map<ContentKey, ContentDraft> drafts, ContentDraft value) {
        drafts.merge(value.key(), value, (prior, next) -> new ContentDraft(prior.key(),
                prior.runtimeMinutes() == null ? next.runtimeMinutes() : prior.runtimeMinutes(),
                Boolean.TRUE.equals(prior.isSeasonFinale()) || Boolean.TRUE.equals(next.isSeasonFinale())
                        ? Boolean.TRUE : null,
                Boolean.TRUE.equals(prior.isSeriesFinale()) || Boolean.TRUE.equals(next.isSeriesFinale())
                        ? Boolean.TRUE : null));
    }

    private List<DiaryTarget> createDiaryEntries(List<User> users,
                                                  Map<UUID, List<MovieData>> moviesByUser,
                                                  Map<UUID, List<SeriesData>> seriesByUser,
                                                  Map<ContentKey, ContentRefData> content,
                                                  LocalDate watchedDate, LocalDateTime now) {
        List<DiaryTarget> targets = new ArrayList<>();
        List<DiaryEntry> batch = new ArrayList<>(DIARY_BATCH_SIZE);
        for (User user : users) {
            UUID userId = user.getId();
            for (MovieData movie : moviesByUser.getOrDefault(userId, List.of())) {
                ContentRefData ref = requireContent(content,
                        new ContentKey(ContentType.MOVIE, movie.tmdbId(), null, null, null));
                batch.add(diaryEntry(userId, ref.id(), false, watchedDate, now));
                if (batch.size() >= DIARY_BATCH_SIZE) flushDiaryBatch(batch, targets);
            }
            for (SeriesData series : seriesByUser.getOrDefault(userId, List.of())) {
                for (EpisodeData episode : series.episodes()) {
                    ContentRefData ref = requireContent(content, new ContentKey(ContentType.EPISODE, null,
                            series.tmdbId(), episode.seasonNumber(), episode.episodeNumber()));
                    batch.add(diaryEntry(userId, ref.id(), true, watchedDate, now));
                    if (batch.size() >= DIARY_BATCH_SIZE) flushDiaryBatch(batch, targets);
                }
                diaryProjectionInvalidator.markSeriesChanged(userId, series.tmdbId());
            }
        }
        if (!batch.isEmpty()) flushDiaryBatch(batch, targets);
        return targets;
    }

    private DiaryEntry diaryEntry(UUID userId, UUID contentId, boolean ignore,
                                  LocalDate watchedDate, LocalDateTime now) {
        Integer score = random.nextInt(10) + 1;
        String review = random.nextInt(100) < 60 ? REVIEW_TEXTS[random.nextInt(REVIEW_TEXTS.length)] : null;
        return DiaryEntry.builder()
                .user(entityManager.getReference(User.class, userId))
                .content(entityManager.getReference(Content.class, contentId))
                .comment(review)
                .score(score)
                .watchedDate(watchedDate)
                .watchedAt(null)
                .watchNumber(1)
                .watchedInTheater(null)
                .ignore(ignore)
                .createdAt(now)
                .updatedAt(now)
                .likesCount(0)
                .build();
    }

    private void flushDiaryBatch(List<DiaryEntry> batch, List<DiaryTarget> targets) {
        List<DiaryEntry> saved = diaryEntryRepository.saveAllAndFlush(batch);
        for (DiaryEntry entry : saved) {
            targets.add(new DiaryTarget(entry.getId(), entry.getUser().getId(), entry.getContent().getId()));
        }
        if (targets.size() > 0 && targets.size() % 5_000 == 0) {
            log.info("Prepared {} demo diary entries", targets.size());
        }
        batch.clear();
        entityManager.clear();
    }

    private Map<UUID, List<MovieData>> assignMovies(List<User> users, List<MovieData> movies) {
        List<MovieData> shuffled = new ArrayList<>(movies);
        Collections.shuffle(shuffled, random);
        Map<UUID, List<MovieData>> result = new LinkedHashMap<>();
        for (int userIndex = 0; userIndex < users.size(); userIndex++) {
            LinkedHashSet<MovieData> selection = new LinkedHashSet<>();
            for (int index = 0; index < BASE_MOVIE_DIARY_COUNT; index++) {
                selection.add(shuffled.get((userIndex * BASE_MOVIE_DIARY_COUNT + index) % shuffled.size()));
            }
            int desiredCount = BASE_MOVIE_DIARY_COUNT + random.nextInt(MAX_EXTRA_MOVIES + 1);
            addRandomRemainder(selection, shuffled, desiredCount);
            result.put(users.get(userIndex).getId(), List.copyOf(selection));
        }
        return result;
    }

    private Map<UUID, List<SeriesData>> assignSeries(List<User> users, List<SeriesData> series) {
        List<SeriesData> shuffled = new ArrayList<>(series);
        Collections.shuffle(shuffled, random);
        Map<UUID, List<SeriesData>> result = new LinkedHashMap<>();
        for (int userIndex = 0; userIndex < users.size(); userIndex++) {
            LinkedHashSet<SeriesData> selection = new LinkedHashSet<>();
            for (int index = 0; index < BASE_SERIES_COUNT; index++) {
                selection.add(shuffled.get((userIndex * BASE_SERIES_COUNT + index) % shuffled.size()));
            }
            int desiredCount = BASE_SERIES_COUNT + random.nextInt(MAX_EXTRA_SERIES + 1);
            addRandomRemainder(selection, shuffled, desiredCount);
            result.put(users.get(userIndex).getId(), List.copyOf(selection));
        }
        return result;
    }

    private <T> void addRandomRemainder(LinkedHashSet<T> selection, List<T> pool, int desiredCount) {
        List<T> remaining = new ArrayList<>(pool);
        remaining.removeAll(selection);
        Collections.shuffle(remaining, random);
        for (T value : remaining) {
            if (selection.size() >= desiredCount) break;
            selection.add(value);
        }
    }

    private List<UserListTarget> createLists(List<User> users, List<MovieData> movies,
                                             List<SeriesData> series,
                                             Map<ContentKey, ContentRefData> content,
                                             LocalDateTime now) {
        List<ContentRefData> movieAndSeries = new ArrayList<>();
        movies.forEach(movie -> movieAndSeries.add(requireContent(content,
                new ContentKey(ContentType.MOVIE, movie.tmdbId(), null, null, null))));
        series.forEach(item -> movieAndSeries.add(requireContent(content,
                new ContentKey(ContentType.SERIES, item.tmdbId(), null, null, null))));
        List<ContentRefData> seasons = content.values().stream()
                .filter(ref -> ref.key().type() == ContentType.SEASON).toList();
        List<ContentRefData> episodes = content.values().stream()
                .filter(ref -> ref.key().type() == ContentType.EPISODE).toList();
        List<List<ContentRefData>> availableGroups = new ArrayList<>();
        availableGroups.add(movieAndSeries);
        if (seasons.size() >= 20) availableGroups.add(seasons);
        if (episodes.size() >= 20) availableGroups.add(episodes);

        List<UserListPlan> plans = new ArrayList<>();
        for (User user : users) {
            int listCount = random.nextInt(4);
            for (int index = 0; index < listCount; index++) {
                List<ContentRefData> pool = availableGroups.get(random.nextInt(availableGroups.size()));
                int maximumSize = Math.min(60, pool.size());
                int size = 20 + random.nextInt(maximumSize - 19);
                List<ContentRefData> items = sample(pool, size);
                String name = LIST_NAMES[random.nextInt(LIST_NAMES.length)] + " " + (index + 1);
                UserList list = UserList.builder()
                        .user(entityManager.getReference(User.class, user.getId()))
                        .name(name)
                        .description("Lista fictícia criada para demonstrar o Watchwise.")
                        .visibility(UserListVisibility.PUBLIC)
                        .createdAt(now)
                        .updatedAt(now)
                        .likesCount(0)
                        .build();
                plans.add(new UserListPlan(list, user.getId(), items));
            }
        }
        List<UserList> savedLists = userListRepository.saveAllAndFlush(plans.stream().map(UserListPlan::list).toList());
        List<UserListTarget> result = new ArrayList<>();
        List<UserListItem> items = new ArrayList<>(JDBC_BATCH_SIZE);
        for (int index = 0; index < savedLists.size(); index++) {
            UserList savedList = savedLists.get(index);
            UserListPlan plan = plans.get(index);
            result.add(new UserListTarget(savedList.getId(), plan.ownerId()));
            for (int position = 0; position < plan.items().size(); position++) {
                ContentRefData item = plan.items().get(position);
                items.add(UserListItem.builder()
                        .userList(entityManager.getReference(UserList.class, savedList.getId()))
                        .content(entityManager.getReference(Content.class, item.id()))
                        .position(position + 1)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                if (items.size() >= JDBC_BATCH_SIZE) flushListItems(items);
            }
        }
        if (!items.isEmpty()) flushListItems(items);
        return result;
    }

    private void flushListItems(List<UserListItem> batch) {
        userListItemRepository.saveAllAndFlush(batch);
        batch.clear();
        entityManager.clear();
    }

    private long createTop5(List<User> users, List<MovieData> movies, List<SeriesData> series,
                            Map<ContentKey, ContentRefData> content, LocalDateTime now) {
        List<Top5Entry> entries = new ArrayList<>(USER_COUNT * 10);
        List<ContentRefData> moviePool = movies.stream().map(movie -> requireContent(content,
                new ContentKey(ContentType.MOVIE, movie.tmdbId(), null, null, null))).toList();
        List<ContentRefData> seriesPool = series.stream().map(item -> requireContent(content,
                new ContentKey(ContentType.SERIES, item.tmdbId(), null, null, null))).toList();
        for (User user : users) {
            addTop5Entries(entries, user.getId(), moviePool, ContentType.MOVIE, now);
            addTop5Entries(entries, user.getId(), seriesPool, ContentType.SERIES, now);
        }
        top5EntryRepository.saveAllAndFlush(entries);
        entityManager.clear();
        return entries.size();
    }

    private void addTop5Entries(List<Top5Entry> entries, UUID userId, List<ContentRefData> pool,
                                ContentType type, LocalDateTime now) {
        List<ContentRefData> top = sample(pool, 5);
        for (int index = 0; index < top.size(); index++) {
            entries.add(Top5Entry.builder()
                    .user(entityManager.getReference(User.class, userId))
                    .content(entityManager.getReference(Content.class, top.get(index).id()))
                    .type(type)
                    .position(index + 1)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    private long createFollows(List<User> users, LocalDateTime now) {
        int size = users.size();
        int[] outgoingLimit = new int[size];
        int[] incomingLimit = new int[size];
        int[] outgoingCount = new int[size];
        int[] incomingCount = new int[size];
        for (int index = 0; index < size; index++) {
            outgoingLimit[index] = random.nextInt(21);
            incomingLimit[index] = random.nextInt(21);
        }
        Set<Integer> edges = new HashSet<>();
        List<Follower> follows = new ArrayList<>();
        int desiredEdges = 900 + random.nextInt(201);
        int attempts = 0;
        while (follows.size() < desiredEdges && attempts++ < 100_000) {
            int from = random.nextInt(size);
            int to = random.nextInt(size);
            int edgeKey = from * size + to;
            if (from == to || edges.contains(edgeKey)
                    || outgoingCount[from] >= outgoingLimit[from]
                    || incomingCount[to] >= incomingLimit[to]) continue;
            edges.add(edgeKey);
            outgoingCount[from]++;
            incomingCount[to]++;
            follows.add(Follower.builder()
                    .follower(entityManager.getReference(User.class, users.get(from).getId()))
                    .followed(entityManager.getReference(User.class, users.get(to).getId()))
                    .status(FollowStatus.ACCEPTED)
                    .createdAt(now)
                    .build());
        }
        followerRepository.saveAllAndFlush(follows);
        entityManager.clear();
        return follows.size();
    }

    private List<TemplateTarget> createTemplates(List<User> users, LocalDateTime now) {
        List<TemplatePlan> plans = new ArrayList<>();
        for (int userIndex = 0; userIndex < users.size(); userIndex++) {
            User user = users.get(userIndex);
            int count = random.nextInt(3);
            if (userIndex == 0) {
                // Keep the demo template pool non-empty while preserving the 0–2 range.
                count = Math.max(count, 1);
            }
            for (int index = 0; index < count; index++) {
                PickAllowedType allowedType = random.nextBoolean() ? PickAllowedType.MOVIE : PickAllowedType.SERIES;
                PickOrigin origin = user.getRole() == UserRole.ADMIN ? PickOrigin.OFFICIAL : PickOrigin.COMMUNITY;
                plans.add(new TemplatePlan(PicksTemplate.builder()
                        .creator(entityManager.getReference(User.class, user.getId()))
                        .origin(origin)
                        .name("Escolhas de " + user.getUsername() + " " + (index + 1))
                        .description("Template fictício de demonstração.")
                        .instructions("Escolha uma obra que combine com a categoria.")
                        .createdAt(now)
                        .updatedAt(now)
                        .likesCount(0)
                        .build(), user.getId(), allowedType));
            }
        }
        List<PicksTemplate> savedTemplates = picksTemplateRepository.saveAllAndFlush(
                plans.stream().map(TemplatePlan::template).toList());
        entityManager.clear();

        List<PicksTemplateCategory> categories = new ArrayList<>(plans.size());
        for (int index = 0; index < plans.size(); index++) {
            TemplatePlan plan = plans.get(index);
            categories.add(PicksTemplateCategory.builder()
                    .picksTemplate(entityManager.getReference(PicksTemplate.class, savedTemplates.get(index).getId()))
                    .name(plan.allowedType() == PickAllowedType.MOVIE ? "Filme" : "Série")
                    .group(PickCategoryGroup.PRIMARY)
                    .displayOrder(1)
                    .allowedType(plan.allowedType())
                    .optionMode(PickCategoryOptionMode.OPEN)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        List<PicksTemplateCategory> savedCategories = categories.isEmpty()
                ? List.of() : picksTemplateCategoryRepository.saveAllAndFlush(categories);
        entityManager.clear();

        List<TemplateTarget> result = new ArrayList<>(plans.size());
        for (int index = 0; index < plans.size(); index++) {
            TemplatePlan plan = plans.get(index);
            result.add(new TemplateTarget(savedTemplates.get(index).getId(), plan.ownerId(),
                    savedCategories.get(index).getId(), plan.allowedType()));
        }
        return result;
    }

    private List<PickTargetRecord> createPicks(List<User> users, List<TemplateTarget> templates,
                                                List<MovieData> movies, List<SeriesData> series,
                                                Map<ContentKey, ContentRefData> content, LocalDateTime now) {
        List<ContentRefData> moviePool = movies.stream().map(movie -> requireContent(content,
                new ContentKey(ContentType.MOVIE, movie.tmdbId(), null, null, null))).toList();
        List<ContentRefData> seriesPool = series.stream().map(item -> requireContent(content,
                new ContentKey(ContentType.SERIES, item.tmdbId(), null, null, null))).toList();
        List<PickPlan> plans = new ArrayList<>();
        if (templates.isEmpty()) return List.of();
        for (User user : users) {
            int count = random.nextInt(5);
            for (int index = 0; index < count; index++) {
                List<TemplateTarget> candidates = templates.stream()
                        .filter(template -> !template.ownerId().equals(user.getId())).toList();
                if (candidates.isEmpty()) candidates = templates;
                TemplateTarget template = candidates.get(random.nextInt(candidates.size()));
                ContentRefData target = template.allowedType() == PickAllowedType.MOVIE
                        ? moviePool.get(random.nextInt(moviePool.size()))
                        : seriesPool.get(random.nextInt(seriesPool.size()));
                plans.add(new PickPlan(Pick.builder()
                        .picksTemplate(entityManager.getReference(PicksTemplate.class, template.id()))
                        .user(entityManager.getReference(User.class, user.getId()))
                        .visibility(PickVisibility.PUBLIC)
                        .createdAt(now)
                        .updatedAt(now)
                        .likesCount(0)
                        .build(), user.getId(), template, target));
            }
        }
        if (plans.isEmpty()) return List.of();
        List<Pick> savedPicks = pickRepository.saveAllAndFlush(plans.stream().map(PickPlan::pick).toList());
        entityManager.clear();
        List<PickSelection> selections = new ArrayList<>(plans.size());
        List<PickTargetRecord> result = new ArrayList<>(plans.size());
        for (int index = 0; index < plans.size(); index++) {
            PickPlan plan = plans.get(index);
            Pick savedPick = savedPicks.get(index);
            selections.add(PickSelection.builder()
                    .pick(entityManager.getReference(Pick.class, savedPick.getId()))
                    .category(entityManager.getReference(PicksTemplateCategory.class, plan.template().categoryId()))
                    .content(entityManager.getReference(Content.class, plan.target().id()))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            result.add(new PickTargetRecord(savedPick.getId(), plan.ownerId()));
        }
        pickSelectionRepository.saveAllAndFlush(selections);
        entityManager.clear();
        return result;
    }

    private InteractionCounts createCommentsAndLikes(List<User> users,
                                                       List<ContentRefData> content,
                                                       List<DiaryTarget> diary,
                                                       List<UserListTarget> lists,
                                                       List<PickTargetRecord> picks,
                                                       List<TemplateTarget> templates,
                                                       LocalDateTime now) {
        List<CommentTarget> targets = new ArrayList<>();
        content.forEach(item -> targets.add(new CommentTarget(CommentTargetKind.CONTENT, item.id(), null)));
        diary.forEach(item -> targets.add(new CommentTarget(CommentTargetKind.DIARY_ENTRY, item.id(), item.ownerId())));
        lists.forEach(item -> targets.add(new CommentTarget(CommentTargetKind.USER_LIST, item.id(), item.ownerId())));
        picks.forEach(item -> targets.add(new CommentTarget(CommentTargetKind.PICK, item.id(), item.ownerId())));
        templates.forEach(item -> targets.add(new CommentTarget(CommentTargetKind.PICKS_TEMPLATE, item.id(), item.ownerId())));

        Map<CounterKind, Map<UUID, Integer>> likesCounts = new HashMap<>();
        for (CounterKind kind : CounterKind.values()) likesCounts.put(kind, new LinkedHashMap<>());
        AtomicLong commentCount = new AtomicLong();
        AtomicLong likeCount = new AtomicLong();
        List<Like> likeBatch = new ArrayList<>(LIKE_BATCH_SIZE);
        List<Comment> commentBatch = new ArrayList<>(JDBC_BATCH_SIZE);

        for (CommentTarget target : targets) {
            int count = random.nextInt(6);
            for (int index = 0; index < count; index++) {
                User author = randomUserExcept(users, target.ownerId());
                int likes = random.nextInt(11);
                commentBatch.add(commentEntity(target, author.getId(),
                        SOCIAL_COMMENT_TEXTS[random.nextInt(SOCIAL_COMMENT_TEXTS.length)], likes, now));
                if (commentBatch.size() >= JDBC_BATCH_SIZE) {
                    List<Comment> saved = commentRepository.saveAllAndFlush(commentBatch);
                    commentCount.addAndGet(saved.size());
                    for (Comment comment : saved) {
                        appendLikes(likeBatch, users, comment.getUser().getId(),
                                LikeTargetKind.COMMENT, comment.getId(), comment.getLikesCount(), now);
                        likeCount.addAndGet(comment.getLikesCount());
                    }
                    commentBatch.clear();
                    flushLikesIfFull(likeBatch);
                    entityManager.clear();
                }
            }
        }
        if (!commentBatch.isEmpty()) {
            List<Comment> saved = commentRepository.saveAllAndFlush(commentBatch);
            commentCount.addAndGet(saved.size());
            for (Comment comment : saved) {
                appendLikes(likeBatch, users, comment.getUser().getId(),
                        LikeTargetKind.COMMENT, comment.getId(), comment.getLikesCount(), now);
                likeCount.addAndGet(comment.getLikesCount());
            }
            commentBatch.clear();
        }
        flushLikesIfFull(likeBatch);

        for (DiaryTarget target : diary) {
            int count = random.nextInt(11);
            likesCounts.get(CounterKind.DIARY_ENTRY).put(target.id(), count);
            appendLikes(likeBatch, users, target.ownerId(), LikeTargetKind.DIARY_ENTRY, target.id(), count, now);
            likeCount.addAndGet(count);
            flushLikesIfFull(likeBatch);
        }
        for (UserListTarget target : lists) {
            int count = random.nextInt(11);
            likesCounts.get(CounterKind.USER_LIST).put(target.id(), count);
            appendLikes(likeBatch, users, target.ownerId(), LikeTargetKind.USER_LIST, target.id(), count, now);
            likeCount.addAndGet(count);
            flushLikesIfFull(likeBatch);
        }
        for (PickTargetRecord target : picks) {
            int count = random.nextInt(11);
            likesCounts.get(CounterKind.PICK).put(target.id(), count);
            appendLikes(likeBatch, users, target.ownerId(), LikeTargetKind.PICK, target.id(), count, now);
            likeCount.addAndGet(count);
            flushLikesIfFull(likeBatch);
        }
        for (TemplateTarget target : templates) {
            int count = random.nextInt(11);
            likesCounts.get(CounterKind.PICKS_TEMPLATE).put(target.id(), count);
            appendLikes(likeBatch, users, target.ownerId(), LikeTargetKind.PICKS_TEMPLATE, target.id(), count, now);
            likeCount.addAndGet(count);
            flushLikesIfFull(likeBatch);
        }
        if (!likeBatch.isEmpty()) {
            likeRepository.saveAllAndFlush(likeBatch);
            likeBatch.clear();
        }
        updateLikesCount(likesCounts);
        entityManager.clear();
        return new InteractionCounts(commentCount.get(), likeCount.get());
    }

    private User randomUserExcept(List<User> users, UUID ownerId) {
        List<User> eligible = users.stream().filter(user -> !user.getId().equals(ownerId)).toList();
        if (eligible.isEmpty()) throw new IllegalStateException("A comment target has no eligible author");
        return eligible.get(random.nextInt(eligible.size()));
    }

    private Comment commentEntity(CommentTarget target, UUID authorId, String text, int likes, LocalDateTime now) {
        Comment.CommentBuilder builder = Comment.builder()
                .user(entityManager.getReference(User.class, authorId))
                .text(text)
                .containsSpoiler(false)
                .parentComment(null)
                .createdAt(now)
                .updatedAt(now)
                .likesCount(likes);
        switch (target.kind()) {
            case CONTENT -> builder.content(entityManager.getReference(Content.class, target.id()));
            case DIARY_ENTRY -> builder.diaryEntry(entityManager.getReference(DiaryEntry.class, target.id()));
            case USER_LIST -> builder.list(entityManager.getReference(UserList.class, target.id()));
            case PICK -> builder.pick(entityManager.getReference(Pick.class, target.id()));
            case PICKS_TEMPLATE -> builder.picksTemplate(entityManager.getReference(
                    com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate.class, target.id()));
        }
        return builder.build();
    }

    private void appendLikes(List<Like> batch, List<User> users, UUID ownerId,
                             LikeTargetKind kind, UUID targetId, int count, LocalDateTime now) {
        if (count == 0) return;
        List<User> eligible = users.stream().filter(user -> !user.getId().equals(ownerId)).toList();
        List<User> likers = sample(eligible, Math.min(count, eligible.size()));
        for (User liker : likers) {
            Like.LikeBuilder builder = Like.builder()
                    .user(entityManager.getReference(User.class, liker.getId()))
                    .createdAt(now);
            switch (kind) {
                case COMMENT -> builder.comment(entityManager.getReference(Comment.class, targetId));
                case DIARY_ENTRY -> builder.diaryEntry(entityManager.getReference(DiaryEntry.class, targetId));
                case USER_LIST -> builder.list(entityManager.getReference(UserList.class, targetId));
                case PICK -> builder.pick(entityManager.getReference(Pick.class, targetId));
                case PICKS_TEMPLATE -> builder.picksTemplate(entityManager.getReference(
                        com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate.class, targetId));
            }
            batch.add(builder.build());
            if (batch.size() >= LIKE_BATCH_SIZE) flushLikesIfFull(batch);
        }
    }

    private void flushLikesIfFull(List<Like> batch) {
        if (batch.size() < LIKE_BATCH_SIZE) return;
        likeRepository.saveAllAndFlush(batch);
        batch.clear();
        entityManager.clear();
    }

    private void updateLikesCount(Map<CounterKind, Map<UUID, Integer>> counters) {
        batchUpdateCounts("UPDATE diary_entries SET likes_count = ? WHERE id = ?", counters.get(CounterKind.DIARY_ENTRY));
        batchUpdateCounts("UPDATE user_lists SET likes_count = ? WHERE id = ?", counters.get(CounterKind.USER_LIST));
        batchUpdateCounts("UPDATE picks SET likes_count = ? WHERE id = ?", counters.get(CounterKind.PICK));
        batchUpdateCounts("UPDATE picks_templates SET likes_count = ? WHERE id = ?", counters.get(CounterKind.PICKS_TEMPLATE));
    }

    private void batchUpdateCounts(String sql, Map<UUID, Integer> values) {
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(values.entrySet());
        for (int offset = 0; offset < entries.size(); offset += JDBC_BATCH_SIZE) {
            List<Map.Entry<UUID, Integer>> batch = entries.subList(offset,
                    Math.min(offset + JDBC_BATCH_SIZE, entries.size()));
            jdbcTemplate.batchUpdate(sql, batch, batch.size(), (statement, entry) -> {
                statement.setInt(1, entry.getValue());
                statement.setObject(2, entry.getKey());
            });
        }
    }

    private long createGameResults(List<User> users, List<ChallengeData> challenges) {
        List<UserDailyGameResult> batch = new ArrayList<>(JDBC_BATCH_SIZE);
        long count = 0;
        for (User user : users) {
            for (ChallengeData challenge : challenges) {
                DailyGameType type = challenge.gameType();
                boolean completed = random.nextInt(100) < 75;
                int attempts = completed ? random.nextInt(type.maxAttempts()) + 1 : type.maxAttempts();
                int score = completed ? type.maxAttempts() - attempts + 1 : 0;
                int hour = 8 + random.nextInt(14);
                int minute = random.nextInt(60);
                LocalDateTime completedAt = challenge.challengeDate().atTime(hour, minute);
                LocalDateTime createdAt = completedAt.minusMinutes(random.nextInt(60));
                batch.add(UserDailyGameResult.builder()
                        .user(entityManager.getReference(User.class, user.getId()))
                        .dailyChallenge(entityManager.getReference(DailyChallenge.class, challenge.id()))
                        .attemptsUsed(attempts)
                        .score(score)
                        .attemptDetails(null)
                        .status(completed ? DailyGameResultStatus.COMPLETED : DailyGameResultStatus.FAILED)
                        .completedAt(completedAt)
                        .shareOnCompletion(false)
                        .sharedAt(null)
                        .createdAt(createdAt)
                        .updatedAt(completedAt)
                        .build());
                if (batch.size() >= JDBC_BATCH_SIZE) {
                    userDailyGameResultRepository.saveAllAndFlush(batch);
                    count += batch.size();
                    batch.clear();
                    entityManager.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            userDailyGameResultRepository.saveAllAndFlush(batch);
            count += batch.size();
        }
        return count;
    }

    private List<ChallengeData> loadExistingCompleteChallenges(LocalDate today) {
        LocalDate startDate = today.minusDays(GAME_CHALLENGE_LOOKBACK_DAYS);
        LocalDate endDate = today.minusDays(1);
        Map<LocalDate, List<DailyChallenge>> byDate = new HashMap<>();
        dailyChallengeRepository.findByChallengeDateBetweenOrderByChallengeDateDescGameTypeAsc(
                        startDate, endDate)
                .forEach(challenge -> byDate.computeIfAbsent(challenge.getChallengeDate(), ignored -> new ArrayList<>())
                        .add(challenge));
        List<LocalDate> completeDates = new ArrayList<>();
        for (LocalDate date : byDate.keySet().stream().sorted(Comparator.reverseOrder()).toList()) {
            List<DailyChallenge> day = byDate.getOrDefault(date, List.of());
            Set<DailyGameType> modes = day.stream().map(DailyChallenge::getGameType)
                    .collect(java.util.stream.Collectors.toSet());
            if (day.size() != DailyGameType.values().length || modes.size() != DailyGameType.values().length) {
                if (!day.isEmpty()) log.info("Skipping incomplete game date {} ({} of 8 modes)", date, modes.size());
                continue;
            }
            completeDates.add(date);
            if (completeDates.size() == DEMO_GAME_DAYS) break;
        }
        Collections.reverse(completeDates);
        List<ChallengeData> result = new ArrayList<>(completeDates.size() * DailyGameType.values().length);
        for (LocalDate date : completeDates) {
            byDate.get(date).stream().sorted(Comparator.comparing(DailyChallenge::getGameType))
                    .map(challenge -> new ChallengeData(
                            challenge.getId(), challenge.getChallengeDate(), challenge.getGameType()))
                    .forEach(result::add);
        }
        log.info("Using {} complete existing game dates and {} shared challenges", completeDates.size(), result.size());
        return result;
    }

    private List<MovieData> readMovies(Path path) throws IOException {
        List<List<String>> rows = readCsv(path, ',');
        if (rows.isEmpty() || !rows.get(0).containsAll(List.of("title", "tmdb_media_type", "tmdb_id"))) {
            throw new IllegalArgumentException("Letterboxd CSV must have title, tmdb_media_type and tmdb_id columns");
        }
        int titleColumn = rows.get(0).indexOf("title");
        int mediaTypeColumn = rows.get(0).indexOf("tmdb_media_type");
        int idColumn = rows.get(0).indexOf("tmdb_id");
        List<MovieData> result = new ArrayList<>(MOVIE_COUNT);
        Set<String> ids = new HashSet<>();
        for (int index = 1; index < rows.size() && result.size() < MOVIE_COUNT; index++) {
            List<String> row = rows.get(index);
            if (row.size() <= Math.max(titleColumn, Math.max(mediaTypeColumn, idColumn))) continue;
            if (!"movie".equalsIgnoreCase(row.get(mediaTypeColumn).trim())) continue;
            String title = row.get(titleColumn).trim();
            String tmdbId = row.get(idColumn).trim();
            if (title.isBlank() || !tmdbId.matches("[0-9]+") || !ids.add(tmdbId)) {
                throw new IllegalArgumentException("Letterboxd movie CSV has an invalid or duplicate movie row at line "
                        + (index + 1));
            }
            result.add(new MovieData(tmdbId, title));
        }
        return List.copyOf(result);
    }

    private List<SeriesData> loadSeriesData(List<SeriesInput> supplied, LocalDate today) {
        List<SeriesData> results = new ArrayList<>(supplied.size());
        int processed = 0;
        for (SeriesInput input : supplied) {
            TmdbTvFullDetails details = tmdbClient.getTvFullDetails(input.tmdbId(), "pt-BR")
                    .toOptional().orElseThrow(() -> new IllegalStateException(
                            "TMDB could not resolve supplied series ID " + input.tmdbId()));
            List<RawSeason> rawSeasons = new ArrayList<>();
            List<TmdbSeasonSummary> summaries = details.seasons() == null ? List.of() : details.seasons();
            for (TmdbSeasonSummary summary : summaries.stream()
                    .filter(value -> value.seasonNumber() != null && value.seasonNumber() > 0)
                    .sorted(Comparator.comparing(TmdbSeasonSummary::seasonNumber)).toList()) {
                LocalDate seasonAirDate = parseDate(summary.airDate());
                if (seasonAirDate != null && seasonAirDate.isAfter(today)) continue;
                var lookup = tmdbClient.getSeasonFullDetails(input.tmdbId(), summary.seasonNumber(), "pt-BR");
                if (lookup.isUnavailable()) {
                    throw new IllegalStateException("TMDB season details are unavailable for series "
                            + input.tmdbId() + " season " + summary.seasonNumber());
                }
                TmdbSeasonFullDetails season = lookup.toOptional().orElse(null);
                if (season == null || season.episodes() == null) continue;
                List<TmdbEpisodeSummary> episodes = season.episodes().stream()
                        .filter(episode -> episode.episodeNumber() != null && episode.episodeNumber() > 0)
                        .filter(episode -> {
                            LocalDate airDate = parseDate(episode.airDate());
                            return airDate != null && !airDate.isAfter(today);
                        })
                        .sorted(Comparator.comparing(TmdbEpisodeSummary::episodeNumber))
                        .toList();
                if (!episodes.isEmpty()) rawSeasons.add(new RawSeason(summary.seasonNumber(), episodes));
            }

            int finaleSeason = rawSeasons.stream().mapToInt(RawSeason::seasonNumber).max().orElse(0);
            List<SeasonData> seasons = new ArrayList<>(rawSeasons.size());
            for (RawSeason raw : rawSeasons) {
                int finaleEpisode = raw.episodes().stream().mapToInt(TmdbEpisodeSummary::episodeNumber).max().orElse(0);
                boolean isSeriesFinaleSeason = raw.seasonNumber() == finaleSeason;
                List<EpisodeData> episodes = raw.episodes().stream().map(episode -> {
                    boolean seasonFinale = episode.episodeNumber() == finaleEpisode;
                    boolean seriesFinale = isSeriesFinaleSeason && seasonFinale;
                    return new EpisodeData(raw.seasonNumber(), episode.episodeNumber(), episode.runtime(),
                            seasonFinale, seriesFinale);
                }).toList();
                seasons.add(new SeasonData(raw.seasonNumber(), isSeriesFinaleSeason, episodes));
            }
            results.add(new SeriesData(input.tmdbId(), input.name(), details, List.copyOf(seasons)));
            if (++processed % 25 == 0) log.info("Validated {} of {} supplied series", processed, supplied.size());
        }
        return List.copyOf(results);
    }

    private List<SeriesInput> readSeries(Path path) throws IOException {
        List<List<String>> rows = readCsv(path, ',');
        if (rows.isEmpty() || !rows.get(0).containsAll(List.of("nome", "tmdb_id"))) {
            throw new IllegalArgumentException("Series CSV must have nome and tmdb_id columns");
        }
        int nameColumn = rows.get(0).indexOf("nome");
        int idColumn = rows.get(0).indexOf("tmdb_id");
        List<SeriesInput> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() <= Math.max(nameColumn, idColumn)) continue;
            String id = row.get(idColumn).trim();
            String name = row.get(nameColumn).trim();
            if (!id.matches("[0-9]+") || !ids.add(id) || name.isBlank()) {
                throw new IllegalArgumentException("Series CSV has an invalid or duplicate row at line " + (index + 1));
            }
            result.add(new SeriesInput(id, name));
        }
        return List.copyOf(result);
    }

    private List<AvatarInput> readAvatars(Path path) throws IOException {
        List<List<String>> rows = readCsv(path, ';');
        if (rows.isEmpty() || !rows.get(0).containsAll(List.of("id", "nome", "imagem"))) {
            throw new IllegalArgumentException("Avatar CSV must have id, nome and imagem columns");
        }
        int idColumn = rows.get(0).indexOf("id");
        int nameColumn = rows.get(0).indexOf("nome");
        int imageColumn = rows.get(0).indexOf("imagem");
        List<AvatarInput> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> images = new HashSet<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() <= Math.max(idColumn, Math.max(nameColumn, imageColumn))) continue;
            String id = row.get(idColumn).trim();
            String name = row.get(nameColumn).trim();
            String image = row.get(imageColumn).trim();
            URI uri;
            try {
                uri = URI.create(image);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Avatar CSV has an invalid image URL at line " + (index + 1));
            }
            if (!ids.add(id) || name.isBlank() || image.length() > 2048
                    || !"https".equalsIgnoreCase(uri.getScheme()) || !images.add(image)) {
                throw new IllegalArgumentException("Avatar CSV has an invalid or duplicate row at line " + (index + 1));
            }
            result.add(new AvatarInput(id, name, image));
        }
        return List.copyOf(result);
    }

    private List<List<String>> readCsv(Path path, char delimiter) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            rows.add(parseCsvLine(line, delimiter));
        }
        return rows;
    }

    private List<String> parseCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == delimiter && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV contains an unterminated quoted field");
        fields.add(field.toString());
        return fields;
    }

    private void refuseExistingDemoUsers() {
        for (int index = 1; index <= USER_COUNT; index++) {
            String username = DEMO_USERNAME_PREFIX + String.format("%03d", index);
            if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
                throw new IllegalStateException("Demo seed users already exist; refusing duplicate insert: " + username);
            }
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ContentRefData requireContent(Map<ContentKey, ContentRefData> content, ContentKey key) {
        ContentRefData ref = content.get(key);
        if (ref == null) throw new IllegalStateException("Missing Content reference for " + key);
        return ref;
    }

    private <T> List<T> sample(List<T> pool, int count) {
        if (count < 0 || count > pool.size()) throw new IllegalArgumentException("Invalid sample size " + count);
        List<T> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, random);
        return List.copyOf(copy.subList(0, count));
    }

    private record SeriesInput(String tmdbId, String name) { }
    private record AvatarInput(String id, String name, String image) { }
    private record MovieData(String tmdbId, String title) { }
    private record EpisodeData(int seasonNumber, int episodeNumber, Integer runtimeMinutes,
                               boolean isSeasonFinale, boolean isSeriesFinale) { }
    private record SeasonData(int seasonNumber, boolean isSeriesFinale, List<EpisodeData> episodes) { }
    private record RawSeason(int seasonNumber, List<TmdbEpisodeSummary> episodes) { }
    private record SeriesData(String tmdbId, String inputName, TmdbTvFullDetails details,
                              List<SeasonData> seasons) {
        private List<EpisodeData> episodes() {
            return seasons.stream().flatMap(season -> season.episodes().stream()).toList();
        }
    }
    private record ContentKey(ContentType type, String tmdbId, String seriesTmdbId,
                              Integer seasonNumber, Integer episodeNumber) {
        private static ContentKey of(Content content) {
            return new ContentKey(content.getType(), content.getTmdbId(), content.getSeriesTmdbId(),
                    content.getSeasonNumber(), content.getEpisodeNumber());
        }
    }
    private record ContentDraft(ContentKey key, Integer runtimeMinutes,
                                Boolean isSeasonFinale, Boolean isSeriesFinale) { }
    private record ContentRefData(UUID id, ContentKey key) { }
    private record UserListPlan(UserList list, UUID ownerId, List<ContentRefData> items) { }
    private record DiaryTarget(UUID id, UUID ownerId, UUID contentId) { }
    private record UserListTarget(UUID id, UUID ownerId) { }
    private record TemplateTarget(UUID id, UUID ownerId, UUID categoryId, PickAllowedType allowedType) { }
    private record TemplatePlan(PicksTemplate template, UUID ownerId, PickAllowedType allowedType) { }
    private record PickPlan(Pick pick, UUID ownerId, TemplateTarget template, ContentRefData target) { }
    private record PickTargetRecord(UUID id, UUID ownerId) { }
    private record ChallengeData(UUID id, LocalDate challengeDate, DailyGameType gameType) { }
    private record InteractionCounts(long comments, long likes) { }
    private record SeedSummary(long users, long movies, long series, long diaryEntries, long lists,
                               long follows, long templates, long picks, long comments, long likes,
                               long gameResults, long top5Entries) { }
    private record CommentTarget(CommentTargetKind kind, UUID id, UUID ownerId) { }
    private enum CommentTargetKind { CONTENT, DIARY_ENTRY, USER_LIST, PICK, PICKS_TEMPLATE }
    private enum LikeTargetKind { COMMENT, DIARY_ENTRY, USER_LIST, PICK, PICKS_TEMPLATE }
    private enum CounterKind { DIARY_ENTRY, USER_LIST, PICK, PICKS_TEMPLATE }
}
