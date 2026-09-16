package com.watchwise.watchwise_api.pick.support;

import com.jayway.jsonpath.JsonPath;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.common.tmdb.*;
import com.watchwise.watchwise_api.pick.dto.*;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PicksDomainIntegrationSupport {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> false);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected UserRepository users;
    @Autowired protected PicksTemplateRepository templates;
    @Autowired protected PicksTemplateCategoryRepository categories;
    @Autowired protected PicksTemplateOptionRepository options;
    @Autowired protected PickRepository picks;
    @Autowired protected PickSelectionRepository selections;
    @Autowired protected PickService pickService;
    @Autowired protected PickSelectionService selectionService;
    @Autowired protected PicksTemplateService templateService;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected JwtService jwt;
    @Autowired protected RequestThrottler throttler;
    @MockitoBean protected TmdbClient tmdb;
    @MockitoBean(name = "tmdbRestClient") protected RestClient tmdbRestClient;

    protected User owner;
    protected User other;
    protected User admin;

    @BeforeEach
    void initializeDomain() {
        jdbc.execute("TRUNCATE TABLE users, contents, picks_templates CASCADE");
        RequestThrottlerTestSupport.reset(throttler);
        reset(tmdb);
        owner = user("owner", UserRole.USER);
        other = user("other", UserRole.USER);
        admin = user("admin", UserRole.ADMIN);
        when(tmdb.getPersonDetails(anyString())).thenAnswer(call ->
                new TmdbLookupResult.Found<>(new TmdbPersonDetails(call.getArgument(0))));
        when(tmdb.getMovieFullDetails(anyString(), anyString())).thenAnswer(call ->
                new TmdbLookupResult.Found<>(movie(call.getArgument(0), "2025-06-01")));
    }

    protected User user(String name, UserRole role) {
        return users.saveAndFlush(User.builder().username(name).email(name + "@example.com")
                .password("hashed-password").role(role).createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build());
    }

    protected Cookie access(User user) {
        return new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, jwt.generateToken(user.getId(), TokenType.ACCESS));
    }

    protected MockHttpServletRequestBuilder authenticated(User user, HttpMethod method, String path, String body) {
        String csrf = UUID.randomUUID().toString();
        return request(method, path).cookie(access(user), new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, csrf))
                .header("X-XSRF-TOKEN", csrf).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected TemplateFixture template() throws Exception {
        MvcResult result = mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates", """
                {"name":"Awards","categories":[
                  {"name":"Person","group":"PRIMARY","displayOrder":1,"allowedType":"PERSON","optionMode":"OPEN"},
                  {"name":"Movie","group":"PRIMARY","displayOrder":2,"allowedType":"MOVIE","optionMode":"OPEN"}]}
                """)).andExpect(status().isCreated()).andReturn();
        return new TemplateFixture(id(result, "$.id"), id(result, "$.categories[0].id"), id(result, "$.categories[1].id"));
    }

    protected UUID id(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }

    protected PickResponseDTO personPick(TemplateFixture template, PickVisibility visibility) {
        return pickService.createPick(owner.getId(), template.id(), new PickCreationDTO(visibility,
                List.of(new PickSelectionCreationDTO(template.person(), new PickTargetDTO(null, "42", null)))));
    }

    protected PickTargetDTO movieTarget(String id) {
        return new PickTargetDTO(new PickContentTargetDTO(PickAllowedType.MOVIE, id, null, null, null), null, null);
    }

    protected TmdbMovieFullDetails movie(String id, String date) {
        return new TmdbMovieFullDetails(id, "Movie", null, null, null, null, date, 100,
                List.of(), List.of(), null, null, null, null, null, null, null);
    }

    protected String categoryBody(String type, String optionJson) {
        return """
                {"name":"Fixed","group":"PRIMARY","displayOrder":3,"allowedType":"%s",
                 "optionMode":"FIXED","options":%s}
                """.formatted(type, optionJson);
    }

    protected record TemplateFixture(UUID id, UUID person, UUID movie) {
    }
}
