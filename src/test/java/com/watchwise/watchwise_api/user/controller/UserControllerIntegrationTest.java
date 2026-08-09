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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @DisplayName("[getUserById] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGetUserById() throws Exception {
        mockMvc.perform(registerRequest("targetnoauth", "targetnoauth@email.com"))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetnoauth", "targetnoauth")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()))
                .andExpect(status().isUnauthorized());
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
    @DisplayName("[getUsersByUsername] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGetUsersByUsername() throws Exception {
        mockMvc.perform(get("/users").param("username", "anything"))
                .andExpect(status().isUnauthorized());
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
