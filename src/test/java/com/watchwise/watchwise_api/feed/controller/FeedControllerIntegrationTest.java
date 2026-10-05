package com.watchwise.watchwise_api.feed.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FeedControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private DailyChallengeRepository dailyChallengeRepository;

    @Autowired
    private UserDailyGameResultRepository userDailyGameResultRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private WatchCompanionRepository watchCompanionRepository;

    @Autowired
    private DroppedEntryRepository droppedEntryRepository;

    @Autowired
    private Top5EntryRepository top5EntryRepository;

    @Autowired
    private PickRepository pickRepository;

    @Autowired
    private PicksTemplateRepository picksTemplateRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        likeRepository.deleteAll();
        commentRepository.deleteAll();
        userDailyGameResultRepository.deleteAll();
        dailyChallengeRepository.deleteAll();
        pickRepository.deleteAll();
        picksTemplateRepository.deleteAll();
        top5EntryRepository.deleteAll();
        droppedEntryRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessTokenCookie, csrfCookie);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        String body = """
                {
                    "username": "%s",
                    "name": "%s",
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void persistFollow(UUID followerId, UUID followedId, FollowStatus status) {
        followerRepository.save(Follower.builder()
                .follower(userRepository.getReferenceById(followerId))
                .followed(userRepository.getReferenceById(followedId))
                .status(status)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Content persistContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private DiaryEntry persistDiaryEntry(User user, Content content, LocalDateTime createdAt) {
        return persistDiaryEntry(user, content, createdAt, false);
    }

    private DiaryEntry persistDiaryEntry(User user, Content content, LocalDateTime createdAt, boolean ignore) {
        return diaryEntryRepository.save(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .ignore(ignore)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private void persistWatchCompanion(DiaryEntry diaryEntry, User companion) {
        watchCompanionRepository.save(WatchCompanion.builder()
                .diaryEntry(diaryEntry)
                .user(companion)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void persistDroppedEntry(User user, Content content, LocalDateTime createdAt) {
        droppedEntryRepository.save(DroppedEntry.builder()
                .user(user)
                .content(content)
                .type(content.getType())
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private void persistTop5Entry(User user, Content content, LocalDateTime createdAt) {
        top5EntryRepository.save(Top5Entry.builder()
                .user(user)
                .content(content)
                .type(content.getType())
                .position(1)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private Comment persistComment(User author, Comment.CommentBuilder target, String text, LocalDateTime createdAt) {
        return commentRepository.save(target
                .user(author)
                .text(text)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private DailyChallenge persistDailyGameChallenge() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 28, 12, 0);
        return dailyChallengeRepository.saveAndFlush(DailyChallenge.builder()
                .challengeDate(LocalDate.of(2026, 9, 29))
                .gameType(DailyGameType.MOVIE_BY_INFO)
                .targetKind(DailyGameTargetKind.MOVIE)
                .targetTmdbId("550")
                .answerKey("movie:550")
                .imagePath("/fight-club.jpg")
                .answerSnapshot(Map.of("title", "Fight Club"))
                .displaySnapshot(Map.of())
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    @Test
    @DisplayName("[getFeed] Should Return Count And Three Newest Comments Per Target - When Entries Have Comments")
    void shouldReturnCountAndThreeNewestCommentsPerTargetWhenEntriesHaveComments() throws Exception {
        RegisteredUser viewer = registerUser("feedcommentviewer");
        RegisteredUser followed = registerUser("feedcommentfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);
        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        User viewerEntity = userRepository.findById(viewer.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        Content movie = persistContent("550", ContentType.MOVIE);
        Content series = persistContent("1399", ContentType.SERIES);
        DiaryEntry diary = persistDiaryEntry(followedEntity, movie, now);
        DiaryEntry otherDiary = persistDiaryEntry(followedEntity, persistContent("680", ContentType.MOVIE), now.minusMinutes(5));
        persistDroppedEntry(followedEntity, series, now.minusMinutes(1));
        for (int i = 1; i <= 4; i++) {
            persistComment(viewerEntity, Comment.builder().diaryEntry(diary), "diary " + i, now.plusSeconds(i));
        }
        persistComment(viewerEntity, Comment.builder().diaryEntry(otherDiary), "other diary", now.plusSeconds(9));

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("DIARY_ENTRY"))
                .andExpect(jsonPath("$.content[0].commentsCount").value(4))
                .andExpect(jsonPath("$.content[0].recentComments.length()").value(3))
                .andExpect(jsonPath("$.content[0].recentComments[0].text").value("diary 4"))
                .andExpect(jsonPath("$.content[0].recentComments[1].text").value("diary 3"))
                .andExpect(jsonPath("$.content[0].recentComments[2].text").value("diary 2"))
                .andExpect(jsonPath("$.content[1].eventType").value("DROPPED"))
                .andExpect(jsonPath("$.content[1].commentsCount").value(0))
                .andExpect(jsonPath("$.content[1].recentComments.length()").value(0))
                .andExpect(jsonPath("$.content[2].eventType").value("DIARY_ENTRY"))
                .andExpect(jsonPath("$.content[2].commentsCount").value(1))
                .andExpect(jsonPath("$.content[2].recentComments[0].text").value("other diary"));
    }

    @Test
    @DisplayName("[getFeed] Should Mark LikedByMe Only On Comments The Viewer Liked - When Preview Has Several Comments")
    void shouldMarkLikedByMeOnlyOnCommentsTheViewerLikedWhenPreviewHasSeveralComments() throws Exception {
        RegisteredUser viewer = registerUser("feedlikedviewer");
        RegisteredUser followed = registerUser("feedlikedfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);
        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        User viewerEntity = userRepository.findById(viewer.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        DiaryEntry diary = persistDiaryEntry(followedEntity, persistContent("550", ContentType.MOVIE), now);
        persistComment(followedEntity, Comment.builder().diaryEntry(diary), "not liked", now.plusSeconds(1));
        Comment liked = persistComment(followedEntity, Comment.builder().diaryEntry(diary), "liked", now.plusSeconds(2));
        likeRepository.save(Like.builder().user(viewerEntity).comment(liked).createdAt(now).build());

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recentComments[0].text").value("liked"))
                .andExpect(jsonPath("$.content[0].recentComments[0].likedByMe").value(true))
                .andExpect(jsonPath("$.content[0].recentComments[1].text").value("not liked"))
                .andExpect(jsonPath("$.content[0].recentComments[1].likedByMe").value(false));
    }

    @Test
    @DisplayName("[getFeed] Should Keep Social Fields Null - When Event Is Top5Update")
    void shouldKeepSocialFieldsNullWhenEventIsTop5Update() throws Exception {
        RegisteredUser viewer = registerUser("feedtop5socialviewer");
        RegisteredUser followed = registerUser("feedtop5socialfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);
        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        persistTop5Entry(followedEntity, persistContent("550", ContentType.MOVIE), LocalDateTime.now());

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("TOP5_UPDATE"))
                .andExpect(jsonPath("$.content[0].commentsCount").value(nullValue()))
                .andExpect(jsonPath("$.content[0].recentComments").value(nullValue()));
    }

    @Test
    @DisplayName("[getFeed] Should Isolate Pick And Template Recent Comments - When Both Have Comments")
    void shouldIsolatePickAndTemplateRecentCommentsWhenBothHaveComments() throws Exception {
        RegisteredUser viewer = registerUser("feedpicksocialviewer");
        RegisteredUser followed = registerUser("feedpicksocialfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);
        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        User viewerEntity = userRepository.findById(viewer.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = picksTemplateRepository.saveAndFlush(PicksTemplate.builder()
                .creator(followedEntity).origin(PickOrigin.COMMUNITY).name("Weekend Picks")
                .createdAt(now.minusMinutes(1)).updatedAt(now.minusMinutes(1)).build());
        Pick pick = pickRepository.saveAndFlush(Pick.builder()
                .picksTemplate(template).user(followedEntity).visibility(PickVisibility.PUBLIC)
                .createdAt(now).updatedAt(now).build());
        persistComment(viewerEntity, Comment.builder().pick(pick), "pick comment", now.plusSeconds(1));
        persistComment(viewerEntity, Comment.builder().picksTemplate(template), "template comment one", now.plusSeconds(2));
        persistComment(viewerEntity, Comment.builder().picksTemplate(template), "template comment two", now.plusSeconds(3));

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("PICK_CREATED"))
                .andExpect(jsonPath("$.content[0].pick.commentsCount").value(1))
                .andExpect(jsonPath("$.content[0].pick.recentComments.length()").value(1))
                .andExpect(jsonPath("$.content[0].pick.recentComments[0].text").value("pick comment"))
                .andExpect(jsonPath("$.content[0].picksTemplate.commentsCount").value(2))
                .andExpect(jsonPath("$.content[0].picksTemplate.recentComments.length()").value(2))
                .andExpect(jsonPath("$.content[0].picksTemplate.recentComments[0].text").value("template comment two"))
                .andExpect(jsonPath("$.content[1].eventType").value("PICKS_TEMPLATE_CREATED"))
                .andExpect(jsonPath("$.content[1].picksTemplate.recentComments.length()").value(2));
    }

    private void persistSharedDailyGameResult(User user, DailyChallenge challenge, LocalDateTime sharedAt) {
        UserDailyGameResult result = UserDailyGameResult.builder()
                .user(user)
                .dailyChallenge(challenge)
                .attemptsUsed(1)
                .score(10)
                .status(DailyGameResultStatus.COMPLETED)
                .completedAt(sharedAt)
                .createdAt(sharedAt.minusMinutes(5))
                .updatedAt(sharedAt)
                .build();
        result.setShareOnCompletion(true);
        result.markSharedAt(sharedAt);
        userDailyGameResultRepository.saveAndFlush(result);
    }

    @Test
    @DisplayName("[getFeed] Should Include WatchedWith - When The DiaryEntry Has A Companion")
    void shouldIncludeWatchedWithWhenTheDiaryEntryHasACompanion() throws Exception {
        RegisteredUser viewer = registerUser("feedwatchedwithviewer");
        RegisteredUser followed = registerUser("feedwatchedwithfollowed");
        RegisteredUser companion = registerUser("feedwatchedwithcompanion");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);

        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        User companionEntity = userRepository.findById(companion.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE);
        DiaryEntry entry = persistDiaryEntry(followedEntity, movie, LocalDateTime.now());
        persistWatchCompanion(entry, companionEntity);

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].watchedWith.length()").value(1))
                .andExpect(jsonPath("$.content[0].watchedWith[0].username").value("feedwatchedwithcompanion"));
    }

    @Test
    @DisplayName("[getFeed] Should Include Pick And Template Creation Events - When Followed User Creates Them")
    void shouldIncludePickAndTemplateCreationEventsWhenFollowedUserCreatesThem() throws Exception {
        RegisteredUser viewer = registerUser("feedpickviewer");
        RegisteredUser followed = registerUser("feedpickfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);

        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = picksTemplateRepository.saveAndFlush(PicksTemplate.builder()
                .creator(followedEntity)
                .origin(PickOrigin.COMMUNITY)
                .name("Weekend Picks")
                .createdAt(now.minusMinutes(1))
                .updatedAt(now.minusMinutes(1))
                .build());
        Pick pick = pickRepository.saveAndFlush(Pick.builder()
                .picksTemplate(template)
                .user(followedEntity)
                .visibility(PickVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build());

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("PICK_CREATED"))
                .andExpect(jsonPath("$.content[0].id").value(pick.getId().toString()))
                .andExpect(jsonPath("$.content[0].pick.id").value(pick.getId().toString()))
                .andExpect(jsonPath("$.content[0].picksTemplate.id").value(template.getId().toString()))
                .andExpect(jsonPath("$.content[0].picksTemplate.name").value("Weekend Picks"))
                .andExpect(jsonPath("$.content[1].eventType").value("PICKS_TEMPLATE_CREATED"))
                .andExpect(jsonPath("$.content[1].picksTemplate.id").value(template.getId().toString()))
                .andExpect(jsonPath("$.content[1].pick").value(nullValue()));
    }

    @Test
    @DisplayName("[getFeed] Should Return Merged Events From Followed Users Ordered By CreatedAt Desc - When Called")
    void shouldReturnMergedEventsFromFollowedUsersOrderedByCreatedAtDescWhenCalled() throws Exception {
        RegisteredUser viewer = registerUser("feedviewer");
        RegisteredUser followed = registerUser("feedfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);

        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();

        Content movie = persistContent("550", ContentType.MOVIE);
        Content series = persistContent("1399", ContentType.SERIES);
        persistDiaryEntry(followedEntity, movie, now.minusMinutes(2));
        persistDroppedEntry(followedEntity, series, now.minusMinutes(1));
        persistTop5Entry(followedEntity, movie, now);

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].eventType").value("TOP5_UPDATE"))
                .andExpect(jsonPath("$.content[1].eventType").value("DROPPED"))
                .andExpect(jsonPath("$.content[1].likesCount").value(0))
                .andExpect(jsonPath("$.content[1].likedByMe").value(false))
                .andExpect(jsonPath("$.content[2].eventType").value("DIARY_ENTRY"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getFeed] Should Include Shared DailyGameResult Only From Accepted Followers - When Results Are Published")
    void shouldIncludeSharedDailyGameResultOnlyFromAcceptedFollowersWhenResultsArePublished() throws Exception {
        RegisteredUser viewer = registerUser("feeddailyviewer");
        RegisteredUser acceptedFollower = registerUser("feeddailyaccepted");
        RegisteredUser pendingFollower = registerUser("feeddailypending");
        persistFollow(viewer.id(), acceptedFollower.id(), FollowStatus.ACCEPTED);
        persistFollow(viewer.id(), pendingFollower.id(), FollowStatus.PENDING);

        User acceptedEntity = userRepository.findById(acceptedFollower.id()).orElseThrow();
        User pendingEntity = userRepository.findById(pendingFollower.id()).orElseThrow();
        DailyChallenge challenge = persistDailyGameChallenge();
        LocalDateTime sharedAt = LocalDateTime.of(2026, 9, 29, 12, 0);
        persistSharedDailyGameResult(acceptedEntity, challenge, sharedAt);
        persistSharedDailyGameResult(pendingEntity, challenge, sharedAt.minusMinutes(1));

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("DAILY_GAME_RESULT"))
                .andExpect(jsonPath("$.content[0].user.username").value("feeddailyaccepted"))
                .andExpect(jsonPath("$.content[0].dailyGameResult.gameType").value("MOVIE_BY_INFO"))
                .andExpect(jsonPath("$.content[0].dailyGameResult.status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].dailyGameResult.answer.title").value("Fight Club"))
                .andExpect(jsonPath("$.content[0].content").value(nullValue()))
                .andExpect(jsonPath("$.content[0].likesCount").value(nullValue()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getFeed] Should Not Include Ignored DiaryEntry Rows - When A Bulk-Logged Episode Is Marked Ignore")
    void shouldNotIncludeIgnoredDiaryEntryRowsWhenABulkLoggedEpisodeIsMarkedIgnore() throws Exception {
        RegisteredUser viewer = registerUser("feedignoreviewer");
        RegisteredUser followed = registerUser("feedignorefollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);

        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();

        Content episode = contentRepository.save(Content.builder()
                .type(ContentType.EPISODE).seriesTmdbId("900").seasonNumber(1).episodeNumber(1)
                .createdAt(now).updatedAt(now).build());
        Content season = contentRepository.save(Content.builder()
                .type(ContentType.SEASON).seriesTmdbId("900").seasonNumber(1)
                .createdAt(now).updatedAt(now).build());
        persistDiaryEntry(followedEntity, episode, now.minusMinutes(1), true);
        persistDiaryEntry(followedEntity, season, now, false);

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.type").value("SEASON"));
    }

    @Test
    @DisplayName("[getFeed] Should Not Include Events From Users The Viewer Does Not Follow - When Called")
    void shouldNotIncludeEventsFromUsersTheViewerDoesNotFollowWhenCalled() throws Exception {
        RegisteredUser viewer = registerUser("feednofollow");
        RegisteredUser stranger = registerUser("feedstranger");
        User strangerEntity = userRepository.findById(stranger.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE);
        persistDiaryEntry(strangerEntity, movie, LocalDateTime.now());

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("[getFeed] Should Return BadRequest - When Size Is Zero")
    void shouldReturnBadRequestWhenSizeIsZero() throws Exception {
        RegisteredUser viewer = registerUser("feedbadsize");

        mockMvc.perform(get("/feed").param("size", "0").cookie(viewer.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be greater than 0"));
    }

    @Test
    @DisplayName("[getFeed] Should Return BadRequest - When Cursor Is Malformed")
    void shouldReturnBadRequestWhenCursorIsMalformed() throws Exception {
        RegisteredUser viewer = registerUser("feedbadcursor");

        mockMvc.perform(get("/feed").param("cursor", "not-a-valid-cursor!!").cookie(viewer.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor"));
    }

    @Test
    @DisplayName("[getFeed] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(get("/feed"))
                .andExpect(status().isUnauthorized());
    }
}
