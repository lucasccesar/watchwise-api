package com.watchwise.watchwise_api.like.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LikeControllerIntegrationTest {

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
    private UserListRepository userListRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        likeRepository.deleteAll();
        commentRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
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
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder postRequest(RegisteredUser actor, String path) {
        return post(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser actor, String path) {
        return delete(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private Content persistContent(String tmdbId) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UserList persistList(User user, String name, UserListVisibility visibility) {
        LocalDateTime now = LocalDateTime.now();
        return userListRepository.save(UserList.builder()
                .user(user)
                .name(name)
                .visibility(visibility)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private DiaryEntry persistDiaryEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return diaryEntryRepository.save(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Comment persistContentComment(User user, Content content, String text) {
        LocalDateTime now = LocalDateTime.now();
        return commentRepository.save(Comment.builder()
                .user(user)
                .content(content)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Comment persistListComment(User user, UserList list, String text) {
        LocalDateTime now = LocalDateTime.now();
        return commentRepository.save(Comment.builder()
                .user(user)
                .list(list)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ---------- POST /comments/{commentId}/like ----------

    @Test
    @DisplayName("[likeComment] Should Return NoContent And Persist The Like - When Comment Targets Content")
    void shouldReturnNoContentAndPersistTheLikeWhenCommentTargetsContent() throws Exception {
        RegisteredUser user = registerUser("likecommentok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Comment comment = persistContentComment(entity, content, "Great movie!");

        mockMvc.perform(postRequest(user, "/comments/" + comment.getId() + "/like"))
                .andExpect(status().isNoContent());

        assertThat(likeRepository.findByUserIdAndCommentId(user.id(), comment.getId())).isPresent();
    }

    @Test
    @DisplayName("[likeComment] Should Return NoContent And Persist Only One Row - When Already Liked")
    void shouldReturnNoContentAndPersistOnlyOneRowWhenAlreadyLiked() throws Exception {
        RegisteredUser user = registerUser("likecommenttwice");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Comment comment = persistContentComment(entity, content, "Great movie!");

        mockMvc.perform(postRequest(user, "/comments/" + comment.getId() + "/like"))
                .andExpect(status().isNoContent());
        mockMvc.perform(postRequest(user, "/comments/" + comment.getId() + "/like"))
                .andExpect(status().isNoContent());

        assertThat(likeRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("[likeComment] Should Return NotFound - When The Comment Does Not Exist")
    void shouldReturnNotFoundWhenTheCommentDoesNotExistOnLike() throws Exception {
        RegisteredUser user = registerUser("likecommentnf");

        mockMvc.perform(postRequest(user, "/comments/" + UUID.randomUUID() + "/like"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Comment not found"));
    }

    @Test
    @DisplayName("[likeComment] Should Return Forbidden And Not Persist - When Comment Targets A Private List And Liker Is Not The Owner")
    void shouldReturnForbiddenAndNotPersistWhenCommentTargetsAPrivateListAndLikerIsNotTheOwner() throws Exception {
        RegisteredUser owner = registerUser("likecommentprivowner");
        RegisteredUser stranger = registerUser("likecommentstranger");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Private list", UserListVisibility.PRIVATE);
        Comment comment = persistListComment(ownerEntity, list, "Nice picks");

        mockMvc.perform(postRequest(stranger, "/comments/" + comment.getId() + "/like"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This list is private"));

        assertThat(likeRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[likeComment] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnLikeComment() throws Exception {
        RegisteredUser user = registerUser("likecommentnoauth");

        mockMvc.perform(post("/comments/" + UUID.randomUUID() + "/like")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[likeComment] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnLikeComment() throws Exception {
        RegisteredUser user = registerUser("likecommentnocsrf");

        mockMvc.perform(post("/comments/" + UUID.randomUUID() + "/like").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /comments/{commentId}/like ----------

    @Test
    @DisplayName("[unlikeComment] Should Return NoContent And Remove The Like - When It Exists")
    void shouldReturnNoContentAndRemoveTheLikeWhenItExists() throws Exception {
        RegisteredUser user = registerUser("unlikecommentok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Comment comment = persistContentComment(entity, content, "Great movie!");
        mockMvc.perform(postRequest(user, "/comments/" + comment.getId() + "/like")).andExpect(status().isNoContent());

        mockMvc.perform(deleteRequest(user, "/comments/" + comment.getId() + "/like"))
                .andExpect(status().isNoContent());

        assertThat(likeRepository.findByUserIdAndCommentId(user.id(), comment.getId())).isEmpty();
    }

    @Test
    @DisplayName("[unlikeComment] Should Return NoContent - When The Like Does Not Exist")
    void shouldReturnNoContentWhenTheLikeDoesNotExistOnUnlikeComment() throws Exception {
        RegisteredUser user = registerUser("unlikecommentnoop");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Comment comment = persistContentComment(entity, content, "Great movie!");

        mockMvc.perform(deleteRequest(user, "/comments/" + comment.getId() + "/like"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[unlikeComment] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnUnlikeComment() throws Exception {
        RegisteredUser user = registerUser("unlikecommentnoauth");

        mockMvc.perform(delete("/comments/" + UUID.randomUUID() + "/like")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[unlikeComment] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnUnlikeComment() throws Exception {
        RegisteredUser user = registerUser("unlikecommentnocsrf");

        mockMvc.perform(delete("/comments/" + UUID.randomUUID() + "/like").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ---------- POST /diary/{diaryEntryId}/like ----------

    @Test
    @DisplayName("[likeDiaryEntry] Should Return NoContent And Persist The Like - When Owner Profile Is Public")
    void shouldReturnNoContentAndPersistTheLikeWhenOwnerProfileIsPublic() throws Exception {
        RegisteredUser user = registerUser("likediaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(entity, content);

        mockMvc.perform(postRequest(user, "/diary/" + entry.getId() + "/like"))
                .andExpect(status().isNoContent());

        assertThat(likeRepository.findByUserIdAndDiaryEntryId(user.id(), entry.getId())).isPresent();
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Return NotFound - When The Diary Entry Does Not Exist")
    void shouldReturnNotFoundWhenTheDiaryEntryDoesNotExistOnLike() throws Exception {
        RegisteredUser user = registerUser("likediarynf");

        mockMvc.perform(postRequest(user, "/diary/" + UUID.randomUUID() + "/like"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Return Forbidden And Not Persist - When Owner Profile Is Private And Liker Does Not Follow The Owner")
    void shouldReturnForbiddenAndNotPersistWhenOwnerProfileIsPrivateAndLikerDoesNotFollowTheOwner() throws Exception {
        RegisteredUser owner = registerUser("likediaryprivowner");
        RegisteredUser stranger = registerUser("likediarystranger");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        ownerEntity.setIsProfilePublic(false);
        userRepository.save(ownerEntity);
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(ownerEntity, content);

        mockMvc.perform(postRequest(stranger, "/diary/" + entry.getId() + "/like"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This diary entry is private"));

        assertThat(likeRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnLikeDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("likediarynoauth");

        mockMvc.perform(post("/diary/" + UUID.randomUUID() + "/like")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnLikeDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("likediarynocsrf");

        mockMvc.perform(post("/diary/" + UUID.randomUUID() + "/like").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /diary/{diaryEntryId}/like ----------

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Return NoContent And Remove The Like - When It Exists")
    void shouldReturnNoContentAndRemoveTheLikeWhenItExistsForDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("unlikediaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(entity, content);
        mockMvc.perform(postRequest(user, "/diary/" + entry.getId() + "/like")).andExpect(status().isNoContent());

        mockMvc.perform(deleteRequest(user, "/diary/" + entry.getId() + "/like"))
                .andExpect(status().isNoContent());

        assertThat(likeRepository.findByUserIdAndDiaryEntryId(user.id(), entry.getId())).isEmpty();
    }

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Return NoContent - When The Like Does Not Exist")
    void shouldReturnNoContentWhenTheLikeDoesNotExistOnUnlikeDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("unlikediarynoop");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(entity, content);

        mockMvc.perform(deleteRequest(user, "/diary/" + entry.getId() + "/like"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnUnlikeDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("unlikediarynoauth");

        mockMvc.perform(delete("/diary/" + UUID.randomUUID() + "/like")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnUnlikeDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("unlikediarynocsrf");

        mockMvc.perform(delete("/diary/" + UUID.randomUUID() + "/like").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }
}
