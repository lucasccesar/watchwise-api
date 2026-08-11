package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserControllerIntegrationTest {

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
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Access Token Cookie Is Valid")
    void shouldReturnUserResponseDtoWhenAccessTokenCookieIsValid() throws Exception {
        Cookie accessTokenCookie = registerAndGetAccessToken("meuser", "meuser@email.com");

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("meuser"))
                .andExpect(jsonPath("$.email").value("meuser@email.com"));
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGetCurrentUser() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Authenticated User - When Request Is Valid")
    void shouldUpdateAuthenticatedUserWhenRequestIsValid() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("johndoe", "johndoe@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("johndoe"))
                .andExpect(jsonPath("$.description").value("Updated bio"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Accept The Same Csrf Token Twice - When Two Authenticated Requests Are Made In A Row")
    void shouldAcceptTheSameCsrfTokenTwiceWhenTwoAuthenticatedRequestsAreMadeInARow() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("repeatuser", "repeatuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"First update\" }"))
                .andExpect(status().isOk());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Second update\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Second update"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Only The Authenticated User - When Another User Exists")
    void shouldUpdateOnlyTheAuthenticatedUserWhenAnotherUserExists() throws Exception {
        mockMvc.perform(registerRequest("userone", "userone@email.com")).andExpect(status().isCreated());

        MvcResult registerResultTwo = mockMvc.perform(registerRequest("usertwo", "usertwo@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookieTwo = registerResultTwo.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookieTwo = registerResultTwo.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookieTwo, csrfCookieTwo, "{ \"description\": \"User two bio\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("usertwo"))
                .andExpect(jsonPath("$.description").value("User two bio"));

        User userOne = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("userone", "userone")
                .orElseThrow();
        assertThat(userOne.getDescription()).isNull();
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocookieuser", "nocookieuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patch("/users/me")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return NotFound - When Account Was Deleted After Token Was Issued")
    void shouldReturnNotFoundWhenAccountWasDeletedAfterTokenWasIssued() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("stalepatchuser", "stalepatchuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Conflict - When Updating Username To One Already Taken By Another Real User")
    void shouldReturnConflictWhenUpdatingUsernameToOneAlreadyTakenByAnotherRealUser() throws Exception {
        mockMvc.perform(registerRequest("takenusername", "takenusername@email.com"))
                .andExpect(status().isCreated());

        MvcResult registerResult = mockMvc.perform(registerRequest("conflictuser", "conflictuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"username\": \"takenusername\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Conflict - When Updating Username To One Already Taken With Different Case")
    void shouldReturnConflictWhenUpdatingUsernameToOneAlreadyTakenWithDifferentCase() throws Exception {
        mockMvc.perform(registerRequest("CaseTakenUser", "casetakenuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult registerResult = mockMvc.perform(registerRequest("caseconflictuser", "caseconflictuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"username\": \"casetakenuser\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return BadRequest - When Email Format Is Invalid")
    void shouldReturnBadRequestWhenEmailFormatIsInvalid() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("invalidemailuser", "invalidemailuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"email\": \"not-an-email\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissing() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocsrfuser", "nocsrfuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(patch("/users/me")
                        .cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Delete Account And Clear Cookies - When Password Matches")
    void shouldDeleteAccountAndClearCookiesWhenPasswordMatches() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("deleteuser", "deleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.CSRF_TOKEN_COOKIE, 0));

        Optional<User> deletedUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("deleteuser", "deleteuser");
        assertThat(deletedUser).isEmpty();
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized And Keep Account - When Password Does Not Match")
    void shouldReturnUnauthorizedAndKeepAccountWhenPasswordDoesNotMatch() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("keepuser", "keepuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"WrongPassword123\" }"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password"));

        Optional<User> keptUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("keepuser", "keepuser");
        assertThat(keptUser).isPresent();
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return BadRequest - When Password Is Blank")
    void shouldReturnBadRequestWhenPasswordIsBlank() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("blankdeleteuser", "blankdeleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForDeleteCurrentUser() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocookiedeleteuser", "nocookiedeleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(delete("/users/me")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"password\": \"Password123\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return NotFound - When Account Was Already Deleted")
    void shouldReturnNotFoundWhenAccountWasAlreadyDeleted() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("doubledeleteuser", "doubledeleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForDeleteCurrentUser() throws Exception {
        Cookie accessTokenCookie = registerAndGetAccessToken("nocsrfdeleteuser", "nocsrfdeleteuser@email.com");

        mockMvc.perform(delete("/users/me")
                        .cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"password\": \"Password123\" }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserDTO - When User Exists And Profile Is Public")
    void shouldReturnPublicUserDtoWhenUserExistsAndProfileIsPublic() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerpublic", "viewerpublic@email.com");

        mockMvc.perform(registerRequest("targetpublic", "targetpublic@email.com"))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetpublic", "targetpublic")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetUser.getId().toString()))
                .andExpect(jsonPath("$.username").value("targetpublic"))
                .andExpect(jsonPath("$.isProfilePublic").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("[getUserById] Should Return Forbidden - When Target Profile Is Private")
    void shouldReturnForbiddenWhenTargetProfileIsPrivate() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerprivate", "viewerprivate@email.com");

        mockMvc.perform(registerRequest("targetprivate", "targetprivate@email.com", false))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetprivate", "targetprivate")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getUserById] Should Return NotFound - When User Does Not Exist")
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewernotfound", "viewernotfound@email.com");

        mockMvc.perform(get("/users/" + UUID.randomUUID()).cookie(viewerAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getUserById] Should Return BadRequest With Expected Type - When UserId Path Variable Is Not A Valid Uuid")
    void shouldReturnBadRequestWithExpectedTypeWhenUserIdPathVariableIsNotAValidUuid() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerbaduuid", "viewerbaduuid@email.com");

        mockMvc.perform(get("/users/not-a-uuid").cookie(viewerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'not-a-uuid' for parameter 'userId'. Expected type: UUID"));
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserDTO - When No Access Token Cookie Is Present")
    void shouldReturnPublicUserDtoWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(registerRequest("targetnoauth", "targetnoauth@email.com"))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetnoauth", "targetnoauth")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("targetnoauth"));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Matching Users - When Username Prefix Matches")
    void shouldReturnMatchingUsersWhenUsernamePrefixMatches() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("searchviewer", "searchviewer@email.com");
        mockMvc.perform(registerRequest("searchuser1", "searchuser1@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("searchuser2", "searchuser2@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("unrelated", "unrelated@email.com")).andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "searchuser").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("searchuser1"))
                .andExpect(jsonPath("$[1].username").value("searchuser2"))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Include Private Profiles In Results - When Username Matches")
    void shouldIncludePrivateProfilesInResultsWhenUsernameMatches() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("privatesearchviewer", "privatesearchviewer@email.com");
        mockMvc.perform(registerRequest("privatesearchtarget", "privatesearchtarget@email.com", false))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "privatesearchtarget").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("privatesearchtarget"))
                .andExpect(jsonPath("$[0].isProfilePublic").value(false));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Respect Size Parameter - When Provided")
    void shouldRespectSizeParameterWhenProvided() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("sizeviewer", "sizeviewer@email.com");
        mockMvc.perform(registerRequest("sizeuser1", "sizeuser1@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("sizeuser2", "sizeuser2@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("sizeuser3", "sizeuser3@email.com")).andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "sizeuser").param("size", "2").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Different Page Of Results - When Page Parameter Changes")
    void shouldReturnDifferentPageOfResultsWhenPageParameterChanges() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("pageviewer", "pageviewer@email.com");
        mockMvc.perform(registerRequest("pageuser1", "pageuser1@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("pageuser2", "pageuser2@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("pageuser3", "pageuser3@email.com")).andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "pageuser").param("page", "1").param("size", "1").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("pageuser1"));

        mockMvc.perform(get("/users").param("username", "pageuser").param("page", "2").param("size", "1").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("pageuser2"));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return BadRequest - When Username Is Missing")
    void shouldReturnBadRequestWhenUsernameIsMissing() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("nousernameviewer", "nousernameviewer@email.com");

        mockMvc.perform(get("/users").cookie(viewerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'username' is missing"));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return BadRequest - When Username Is Blank")
    void shouldReturnBadRequestWhenUsernameIsBlank() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("blankusernameviewer", "blankusernameviewer@email.com");

        mockMvc.perform(get("/users").param("username", "").cookie(viewerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username must be provided"));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Matching Users - When No Access Token Cookie Is Present")
    void shouldReturnMatchingUsersWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(registerRequest("noauthsearchtarget", "noauthsearchtarget@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "noauthsearchtarget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("noauthsearchtarget"));
    }

    private Cookie registerAndGetAccessToken(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username, email))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        return accessTokenCookie;
    }

    private MockHttpServletRequestBuilder patchMeRequest(Cookie accessTokenCookie, Cookie csrfCookie, String body) {
        return patch("/users/me")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder deleteMeRequest(Cookie accessTokenCookie, Cookie csrfCookie, String body) {
        return delete("/users/me")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, String email) {
        return registerRequest(username, email, true);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, String email, boolean isProfilePublic) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, email, isProfilePublic);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
