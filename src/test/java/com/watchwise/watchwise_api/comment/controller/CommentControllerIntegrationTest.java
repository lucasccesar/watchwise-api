package com.watchwise.watchwise_api.comment.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CommentControllerIntegrationTest {

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
    private UserListItemRepository userListItemRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);

        lenient().when(tmdbClient.getMovieFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        lenient().when(tmdbClient.getTvFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
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

    private MockHttpServletRequestBuilder getRequest(RegisteredUser actor, String path) {
        return get(path).cookie(actor.accessToken());
    }

    private MockHttpServletRequestBuilder postRequest(RegisteredUser actor, String path, String body) {
        return post(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser actor, String path) {
        return delete(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private String commentBody(String text) {
        return commentBody(text, null, null);
    }

    private String commentBody(String text, UUID parentCommentId, Boolean containsSpoiler) {
        String textField = text == null ? "null" : "\"" + text + "\"";
        String parentField = parentCommentId == null ? "null" : "\"" + parentCommentId + "\"";
        String spoilerField = containsSpoiler == null ? "null" : String.valueOf(containsSpoiler);
        return """
                {
                    "text": %s,
                    "parentCommentId": %s,
                    "containsSpoiler": %s
                }
                """.formatted(textField, parentField, spoilerField);
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

    private void persistChildListItem(UserList userList, UserList childList) {
        LocalDateTime now = LocalDateTime.now();
        userListItemRepository.save(UserListItem.builder()
                .userList(userList)
                .childList(childList)
                .position(1)
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

    private Comment persistDiaryEntryComment(User user, DiaryEntry entry, String text) {
        LocalDateTime now = LocalDateTime.now();
        return commentRepository.save(Comment.builder()
                .user(user)
                .diaryEntry(entry)
                .text(text)
                .containsSpoiler(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ---------- GET /contents/{contentId}/comments ----------

    @Test
    @DisplayName("[getCommentsForContent] Should Return Comments Ordered By CreatedAt Ascending")
    void shouldReturnCommentsOrderedByCreatedAtAscendingForContent() throws Exception {
        RegisteredUser user = registerUser("getcontentcommentsok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Comment older = persistContentComment(entity, content, "First");
        older.setCreatedAt(LocalDateTime.now().minusDays(1));
        commentRepository.save(older);
        persistContentComment(entity, content, "Second");

        mockMvc.perform(getRequest(user, "/contents/" + content.getId() + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("First"))
                .andExpect(jsonPath("$.content[0].user.username").value("getcontentcommentsok"))
                .andExpect(jsonPath("$.content[1].text").value("Second"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Return NotFound - When Content Does Not Exist")
    void shouldReturnNotFoundWhenContentDoesNotExistForListing() throws Exception {
        RegisteredUser user = registerUser("getcontentcommentsnf");

        mockMvc.perform(getRequest(user, "/contents/" + UUID.randomUUID() + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Content not found"));
    }

    @Test
    @DisplayName("[getCommentsForContent] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnGetContentComments() throws Exception {
        mockMvc.perform(get("/contents/" + UUID.randomUUID() + "/comments"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- POST /contents/{contentId}/comments ----------

    @Test
    @DisplayName("[createCommentOnContent] Should Return Created And Persist The Comment")
    void shouldReturnCreatedAndPersistTheCommentOnContent() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentok");
        Content content = persistContent("550");

        mockMvc.perform(postRequest(user, "/contents/" + content.getId() + "/comments", commentBody("Great movie!")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Great movie!"))
                .andExpect(jsonPath("$.contentId").value(content.getId().toString()))
                .andExpect(jsonPath("$.listId").doesNotExist())
                .andExpect(jsonPath("$.diaryEntryId").doesNotExist())
                .andExpect(jsonPath("$.containsSpoiler").value(false));

        assertThat(commentRepository.findByContentIdOrderByCreatedAtAsc(content.getId(), PageRequest.of(0, 10))
                .getContent()).hasSize(1);
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return NotFound And Not Persist - When Content Does Not Exist")
    void shouldReturnNotFoundAndNotPersistWhenContentDoesNotExistOnCreate() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentnf");

        mockMvc.perform(postRequest(user, "/contents/" + UUID.randomUUID() + "/comments", commentBody("Great movie!")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Content not found"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return BadRequest And Not Persist - When Text Is Blank")
    void shouldReturnBadRequestAndNotPersistWhenTextIsBlankOnContent() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentblank");
        Content content = persistContent("550");

        mockMvc.perform(postRequest(user, "/contents/" + content.getId() + "/comments", commentBody("   ")))
                .andExpect(status().isBadRequest());

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return BadRequest And Not Persist - When Text Exceeds 280 Characters")
    void shouldReturnBadRequestAndNotPersistWhenTextExceeds280CharactersOnContent() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentlong");
        Content content = persistContent("550");
        String tooLong = "a".repeat(281);

        mockMvc.perform(postRequest(user, "/contents/" + content.getId() + "/comments", commentBody(tooLong)))
                .andExpect(status().isBadRequest());

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return NotFound And Not Persist - When Parent Comment Does Not Exist")
    void shouldReturnNotFoundAndNotPersistWhenParentCommentDoesNotExistOnContent() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentnoparent");
        Content content = persistContent("550");

        mockMvc.perform(postRequest(user, "/contents/" + content.getId() + "/comments",
                        commentBody("Reply", UUID.randomUUID(), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Parent comment not found"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return BadRequest And Not Persist - When Parent Comment Targets A Different Content")
    void shouldReturnBadRequestAndNotPersistWhenParentCommentTargetsADifferentContent() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentwrongparent");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Content otherContent = persistContent("680");
        Comment parentOnOtherContent = persistContentComment(entity, otherContent, "Existing comment");

        mockMvc.perform(postRequest(user, "/contents/" + content.getId() + "/comments",
                        commentBody("Reply", parentOnOtherContent.getId(), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parent comment must target the same content"));

        assertThat(commentRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnCreateContentComment() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentnoauth");

        mockMvc.perform(post("/contents/" + UUID.randomUUID() + "/comments")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Great movie!")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnCreateContentComment() throws Exception {
        RegisteredUser user = registerUser("createcontentcommentnocsrf");

        mockMvc.perform(post("/contents/" + UUID.randomUUID() + "/comments")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Great movie!")))
                .andExpect(status().isForbidden());
    }

    // ---------- GET /lists/{listId}/comments ----------

    @Test
    @DisplayName("[getCommentsForList] Should Return Comments - When List Is Public")
    void shouldReturnCommentsWhenListIsPublic() throws Exception {
        RegisteredUser owner = registerUser("getlistcommentsowner");
        RegisteredUser viewer = registerUser("getlistcommentsviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Best sci-fi", UserListVisibility.PUBLIC);
        persistListComment(ownerEntity, list, "Nice picks");

        mockMvc.perform(getRequest(viewer, "/lists/" + list.getId() + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("Nice picks"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExistForListing() throws Exception {
        RegisteredUser user = registerUser("getlistcommentsnf");

        mockMvc.perform(getRequest(user, "/lists/" + UUID.randomUUID() + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Forbidden - When List Is Private And Viewer Is Not The Owner")
    void shouldReturnForbiddenWhenListIsPrivateAndViewerIsNotTheOwnerForListing() throws Exception {
        RegisteredUser owner = registerUser("getlistcommentsprivowner");
        RegisteredUser stranger = registerUser("getlistcommentsstranger");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Private list", UserListVisibility.PRIVATE);

        mockMvc.perform(getRequest(stranger, "/lists/" + list.getId() + "/comments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This list is private"));
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnGetListComments() throws Exception {
        mockMvc.perform(get("/lists/" + UUID.randomUUID() + "/comments"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- POST /lists/{listId}/comments ----------

    @Test
    @DisplayName("[createCommentOnList] Should Return Created And Persist The Comment")
    void shouldReturnCreatedAndPersistTheCommentOnList() throws Exception {
        RegisteredUser user = registerUser("createlistcommentok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", UserListVisibility.PUBLIC);

        mockMvc.perform(postRequest(user, "/lists/" + list.getId() + "/comments", commentBody("Nice picks")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Nice picks"))
                .andExpect(jsonPath("$.listId").value(list.getId().toString()))
                .andExpect(jsonPath("$.contentId").doesNotExist());

        assertThat(commentRepository.findByListIdOrderByCreatedAtAsc(list.getId(), PageRequest.of(0, 10))
                .getContent()).hasSize(1);
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return NotFound And Not Persist - When List Does Not Exist")
    void shouldReturnNotFoundAndNotPersistWhenListDoesNotExistOnCreate() throws Exception {
        RegisteredUser user = registerUser("createlistcommentnf");

        mockMvc.perform(postRequest(user, "/lists/" + UUID.randomUUID() + "/comments", commentBody("Nice picks")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return Forbidden And Not Persist - When List Is Private And Commenter Is Not The Owner")
    void shouldReturnForbiddenAndNotPersistWhenListIsPrivateAndCommenterIsNotTheOwner() throws Exception {
        RegisteredUser owner = registerUser("createlistcommentprivowner");
        RegisteredUser stranger = registerUser("createlistcommentstranger");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Private list", UserListVisibility.PRIVATE);

        mockMvc.perform(postRequest(stranger, "/lists/" + list.getId() + "/comments", commentBody("Nice picks")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This list is private"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return BadRequest And Not Persist - When List Is Locked As A List Of Lists")
    void shouldReturnBadRequestAndNotPersistWhenListIsLockedAsAListOfLists() throws Exception {
        RegisteredUser user = registerUser("createlistcommentlocked");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "List of lists", UserListVisibility.PUBLIC);
        UserList childList = persistList(entity, "Child list", UserListVisibility.PUBLIC);
        persistChildListItem(list, childList);

        mockMvc.perform(postRequest(user, "/lists/" + list.getId() + "/comments", commentBody("Nice picks")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This list is a list of lists and cannot receive comments"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return BadRequest And Not Persist - When Text Is Blank")
    void shouldReturnBadRequestAndNotPersistWhenTextIsBlankOnList() throws Exception {
        RegisteredUser user = registerUser("createlistcommentblank");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", UserListVisibility.PUBLIC);

        mockMvc.perform(postRequest(user, "/lists/" + list.getId() + "/comments", commentBody("   ")))
                .andExpect(status().isBadRequest());

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnCreateListComment() throws Exception {
        RegisteredUser user = registerUser("createlistcommentnoauth");

        mockMvc.perform(post("/lists/" + UUID.randomUUID() + "/comments")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Nice picks")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnCreateListComment() throws Exception {
        RegisteredUser user = registerUser("createlistcommentnocsrf");

        mockMvc.perform(post("/lists/" + UUID.randomUUID() + "/comments")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Nice picks")))
                .andExpect(status().isForbidden());
    }

    // ---------- GET /diary/{diaryEntryId}/comments ----------

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Comments - When Owner Profile Is Public")
    void shouldReturnCommentsWhenOwnerProfileIsPublic() throws Exception {
        RegisteredUser owner = registerUser("getdiarycommentsowner");
        RegisteredUser viewer = registerUser("getdiarycommentsviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(ownerEntity, content);
        persistDiaryEntryComment(ownerEntity, entry, "Agreed");

        mockMvc.perform(getRequest(viewer, "/diary/" + entry.getId() + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("Agreed"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return NotFound - When Diary Entry Does Not Exist")
    void shouldReturnNotFoundWhenDiaryEntryDoesNotExistForListing() throws Exception {
        RegisteredUser user = registerUser("getdiarycommentsnf");

        mockMvc.perform(getRequest(user, "/diary/" + UUID.randomUUID() + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Forbidden - When Owner Profile Is Private And Viewer Does Not Follow The Owner")
    void shouldReturnForbiddenWhenOwnerProfileIsPrivateAndViewerDoesNotFollowTheOwnerForListing() throws Exception {
        RegisteredUser owner = registerUser("getdiarycommentsprivowner");
        RegisteredUser stranger = registerUser("getdiarycommentsstranger");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        ownerEntity.setIsProfilePublic(false);
        userRepository.save(ownerEntity);
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(ownerEntity, content);

        mockMvc.perform(getRequest(stranger, "/diary/" + entry.getId() + "/comments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This diary entry is private"));
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnGetDiaryComments() throws Exception {
        mockMvc.perform(get("/diary/" + UUID.randomUUID() + "/comments"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- POST /diary/{diaryEntryId}/comments ----------

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return Created And Persist The Comment")
    void shouldReturnCreatedAndPersistTheCommentOnDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("creatediarycommentok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(entity, content);

        mockMvc.perform(postRequest(user, "/diary/" + entry.getId() + "/comments", commentBody("Agreed")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Agreed"))
                .andExpect(jsonPath("$.diaryEntryId").value(entry.getId().toString()))
                .andExpect(jsonPath("$.contentId").doesNotExist());

        assertThat(commentRepository
                .findByDiaryEntryIdOrderByCreatedAtAsc(entry.getId(), PageRequest.of(0, 10))
                .getContent()).hasSize(1);
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return NotFound And Not Persist - When Diary Entry Does Not Exist")
    void shouldReturnNotFoundAndNotPersistWhenDiaryEntryDoesNotExistOnCreate() throws Exception {
        RegisteredUser user = registerUser("creatediarycommentnf");

        mockMvc.perform(postRequest(user, "/diary/" + UUID.randomUUID() + "/comments", commentBody("Agreed")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return Forbidden And Not Persist - When Owner Profile Is Private And Commenter Does Not Follow The Owner")
    void shouldReturnForbiddenAndNotPersistWhenOwnerProfileIsPrivateAndCommenterDoesNotFollowTheOwner() throws Exception {
        RegisteredUser owner = registerUser("creatediarycommentprivowner");
        RegisteredUser stranger = registerUser("creatediarycommentstranger");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        ownerEntity.setIsProfilePublic(false);
        userRepository.save(ownerEntity);
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(ownerEntity, content);

        mockMvc.perform(postRequest(stranger, "/diary/" + entry.getId() + "/comments", commentBody("Agreed")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This diary entry is private"));

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return BadRequest And Not Persist - When Text Is Blank")
    void shouldReturnBadRequestAndNotPersistWhenTextIsBlankOnDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("creatediarycommentblank");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        DiaryEntry entry = persistDiaryEntry(entity, content);

        mockMvc.perform(postRequest(user, "/diary/" + entry.getId() + "/comments", commentBody("   ")))
                .andExpect(status().isBadRequest());

        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnCreateDiaryComment() throws Exception {
        RegisteredUser user = registerUser("creatediarycommentnoauth");

        mockMvc.perform(post("/diary/" + UUID.randomUUID() + "/comments")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Agreed")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnCreateDiaryComment() throws Exception {
        RegisteredUser user = registerUser("creatediarycommentnocsrf");

        mockMvc.perform(post("/diary/" + UUID.randomUUID() + "/comments")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Agreed")))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /comments/{commentId} ----------

    @Test
    @DisplayName("[deleteComment] Should Return NoContent And Remove The Comment - When Owned By The Requester")
    void shouldReturnNoContentAndRemoveTheCommentWhenOwnedByTheRequester() throws Exception {
        RegisteredUser user = registerUser("deletecommentok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content = persistContent("550");
        Comment comment = persistContentComment(entity, content, "Great movie!");

        mockMvc.perform(deleteRequest(user, "/comments/" + comment.getId()))
                .andExpect(status().isNoContent());

        assertThat(commentRepository.findById(comment.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteComment] Should Return NotFound - When The Comment Does Not Exist")
    void shouldReturnNotFoundWhenTheCommentDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("deletecommentnf");

        mockMvc.perform(deleteRequest(user, "/comments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Comment not found"));
    }

    @Test
    @DisplayName("[deleteComment] Should Return NotFound And Not Remove - When The Comment Belongs To Another User")
    void shouldReturnNotFoundAndNotRemoveWhenTheCommentBelongsToAnotherUser() throws Exception {
        RegisteredUser owner = registerUser("deletecommentowner");
        RegisteredUser intruder = registerUser("deletecommentintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        Content content = persistContent("550");
        Comment comment = persistContentComment(ownerEntity, content, "Great movie!");

        mockMvc.perform(deleteRequest(intruder, "/comments/" + comment.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Comment not found"));

        assertThat(commentRepository.findById(comment.getId())).isPresent();
    }

    @Test
    @DisplayName("[deleteComment] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnDelete() throws Exception {
        RegisteredUser user = registerUser("deletecommentnoauth");

        mockMvc.perform(delete("/comments/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[deleteComment] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnDelete() throws Exception {
        RegisteredUser user = registerUser("deletecommentnocsrf");

        mockMvc.perform(delete("/comments/" + UUID.randomUUID())
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }
}
