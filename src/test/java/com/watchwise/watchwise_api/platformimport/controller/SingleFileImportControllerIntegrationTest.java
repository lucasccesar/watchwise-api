package com.watchwise.watchwise_api.platformimport.controller;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.platformimport.dto.ImportCommitDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportResolutionDTO;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
class SingleFileImportControllerIntegrationTest extends PicksDomainIntegrationSupport {
    @Autowired private ImportWorker worker;
    @Autowired private ImportLimits limits;
    @Autowired private PrivateImportFileStore importFiles;
    @Autowired private ObjectMapper mapper;
    private final List<String> storageKeys = new ArrayList<>();

    @DynamicPropertySource
    static void importProperties(DynamicPropertyRegistry registry) {
        registry.add("app.import.storage-directory", () -> Path.of(System.getProperty("java.io.tmpdir"),
                "watchwise-single-file-http-tests").toAbsolutePath().toString());
    }

    @AfterEach
    void cleanImportFiles() throws IOException {
        for (String key : storageKeys) importFiles.delete(key);
        storageKeys.clear();
    }

    @Test
    @DisplayName("[createImport] Should Reject Upload Without Authentication")
    void shouldRejectUploadWithoutAuthentication() throws Exception {
        String csrf = UUID.randomUUID().toString();
        MockMultipartFile file = new MockMultipartFile("file", "template.json", "application/json",
                "{\"schemaVersion\":1,\"title\":\"Secured\",\"categories\":[{\"name\":\"Best\",\"type\":\"movie\"}]}"
                        .getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/imports").file(file)
                        .param("inputKind", "PICKS_TEMPLATE")
                        .param("sourceFormat", "WATCHWISE_TEMPLATE_JSON")
                        .cookie(new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, csrf))
                        .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createImport] Should Reject Upload Without CSRF")
    void shouldRejectUploadWithoutCsrf() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "template.json", "application/json",
                "{\"schemaVersion\":1,\"title\":\"Secured\",\"categories\":[{\"name\":\"Best\",\"type\":\"movie\"}]}"
                        .getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/imports").file(file)
                        .param("inputKind", "PICKS_TEMPLATE")
                        .param("sourceFormat", "WATCHWISE_TEMPLATE_JSON")
                        .cookie(access(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[createImport] Should Resolve, Commit And Reuse A Picks Template - When Upload Uses Authenticated HTTP")
    void shouldImportAndIdempotentlyReuseTemplateOverHttp() throws Exception {
        String source = """
                {
                  "schemaVersion": 1,
                  "title": "People Awards",
                  "description": "Annual awards",
                  "eligibility": {"start":"2000-01-01","end":"2030-12-31"},
                  "categories": [{"name":"Best lead","type":"person","nominees":["Sam Example"]}]
                }
                """;
        when(tmdb.searchPeople(eq("Sam Example"), anyString(), eq(1))).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbPersonSearchResult("42", "Sam Example", null),
                        new TmdbPersonSearchResult("7", "Sam Example", null)), 1, 2)));

        UUID importId = uploadTemplate(source);
        advanceToPreview(importId);
        JsonNode firstPreview = getPreview(importId);
        JsonNode templateRoot = findItem(firstPreview, "TEMPLATE");
        JsonNode category = findItem(firstPreview, "TEMPLATE_CATEGORY");
        JsonNode nominee = findItem(firstPreview, "TEMPLATE_OPTION");
        assertThat(category.path("parentItemId").asString()).isEqualTo(templateRoot.path("itemId").asString());
        assertThat(nominee.path("parentItemId").asString()).isEqualTo(category.path("itemId").asString());
        assertThat(templateRoot.path("itemTitle").asString()).isEqualTo("People Awards");
        assertThat(category.path("itemTitle").asString()).isEqualTo("Best lead");
        assertThat(nominee.path("itemTitle").asString()).isEqualTo("Sam Example");
        assertThat(nominee.path("targetType").asString()).isEqualTo("PERSON");
        assertThat(nominee.path("parentItemId").asString()).isNotBlank();
        assertThat(nominee.path("resolvedTarget").path("candidates").size()).isEqualTo(2);
        assertThat(nominee.path("resolvedTarget").has("nominee")).isFalse();
        String candidateId = candidateIdFor(nominee.path("resolvedTarget"), "42");

        patchResolution(importId, firstPreview.path("previewVersion").asLong(),
                new ImportResolutionOperation.ResolveTarget(UUID.fromString(nominee.path("itemId").asString()),
                        candidateId));
        worker.runAvailable(1);
        JsonNode ready = getJob(importId);
        assertThat(ready.path("state").asString()).isEqualTo("READY");
        commit(importId, ready);
        worker.runAvailable(1);
        assertThat(getJob(importId).path("state").asString()).isEqualTo("COMPLETED");

        UUID templateId = jdbc.queryForObject("""
                SELECT target_id FROM external_import_links
                WHERE user_id=? AND source_namespace='WATCHWISE_TEMPLATE_V1'
                  AND object_kind='PICKS_TEMPLATE' AND state='APPLIED'
                """, UUID.class, owner.getId());
        mvc.perform(get("/picks-templates/{templateId}", templateId).cookie(access(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("People Awards"));

        UUID repeatedImportId = uploadTemplate(source);
        advanceToPreview(repeatedImportId);
        JsonNode repeatedPreview = getPreview(repeatedImportId);
        assertThat(findItem(repeatedPreview, "TEMPLATE_OPTION").path("outcome").asString())
                .isEqualTo("MATCHED");
        JsonNode repeatedReady = getJob(repeatedImportId);
        assertThat(repeatedReady.path("state").asString()).isEqualTo("READY");
        commit(repeatedImportId, repeatedReady);
        worker.runAvailable(1);

        assertThat(getJob(repeatedImportId).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(templates.count()).isEqualTo(1);
        assertThat(options.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[createImport] Should Reject Invalid Or Oversized Template JSON - Before Creating A Job")
    void shouldRejectInvalidAndOversizedTemplateJsonBeforeCreatingJob() throws Exception {
        String csrf = UUID.randomUUID().toString();
        MockMultipartFile invalid = new MockMultipartFile("file", "template.json", "application/json",
                "{\"schemaVersion\":2,\"title\":\"Bad\",\"categories\":[{\"name\":\"x\",\"type\":\"movie\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/imports").file(invalid)
                        .param("inputKind", "PICKS_TEMPLATE")
                        .param("sourceFormat", "WATCHWISE_TEMPLATE_JSON")
                        .cookie(access(owner), new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, csrf))
                        .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isBadRequest());

        long originalTemplateLimit = limits.maxTemplateUploadBytes();
        try {
            limits.setMaxTemplateUploadBytes(10L * 1024 * 1024);
            byte[] oversizedBytes = new byte[5 * 1024 * 1024 + 1];
            MockMultipartFile oversized = new MockMultipartFile("file", "template.json", "application/json", oversizedBytes);
            mvc.perform(multipart("/imports").file(oversized)
                            .param("inputKind", "PICKS_TEMPLATE")
                            .param("sourceFormat", "WATCHWISE_TEMPLATE_JSON")
                            .cookie(access(owner), new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, csrf))
                            .header("X-XSRF-TOKEN", csrf))
                    .andExpect(status().isPayloadTooLarge());
        } finally {
            limits.setMaxTemplateUploadBytes(originalTemplateLimit);
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_jobs WHERE user_id=?", Integer.class,
                owner.getId())).isZero();
    }

    @Test
    @DisplayName("[resolveImport] Should Resolve An Unaliased Episode String - With Composite Coordinates")
    void shouldResolveEpisodeStringUsingCompositeTarget() throws Exception {
        when(tmdb.getTvFullDetails(eq("1396"), anyString())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails("1396", "Example Series", "Example Series", null, null, null,
                        "2020-01-01", List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, null, null, List.of(), null, "Ended")));
        when(tmdb.getEpisodeFullDetails(eq("1396"), eq(1), eq(1), anyString())).thenReturn(
                new TmdbLookupResult.Found<>(new TmdbEpisodeFullDetails(
                        1001, "Pilot", null, "2020-01-01", 1, 1, 45, null, List.of())));
        String source = """
                {"schemaVersion":1,"title":"Episode Awards","categories":[
                  {"name":"Best episode","type":"episode","nominees":["Pilot"]}
                ]}
                """;
        UUID importId = uploadTemplate(source);
        advanceToPreview(importId);
        JsonNode preview = getPreview(importId);
        JsonNode nominee = findItem(preview, "TEMPLATE_OPTION");
        assertThat(nominee.path("outcome").asString()).isEqualTo("NOT_FOUND");
        assertThat(nominee.path("targetType").asString()).isEqualTo("EPISODE");
        assertThat(nominee.path("itemTitle").asString()).isEqualTo("Pilot");

        ImportTarget.ContentTarget target = new ImportTarget.ContentTarget(MediaReference.episode("1396", 1, 1));
        patchResolution(importId, preview.path("previewVersion").asLong(),
                new ImportResolutionOperation.ResolveTarget(UUID.fromString(nominee.path("itemId").asString()), target));
        worker.runAvailable(1);
        JsonNode ready = getJob(importId);
        assertThat(ready.path("state").asString()).isEqualTo("READY");
        commit(importId, ready);
        worker.runAvailable(1);

        assertThat(getJob(importId).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM picks_template_options option
                JOIN contents content ON content.id=option.content_id
                WHERE content.type='EPISODE' AND content.series_tmdb_id='1396'
                  AND content.season_number=1 AND content.episode_number=1
                """, Integer.class)).isEqualTo(1);
    }

    private UUID uploadTemplate(String source) throws Exception {
        String csrf = UUID.randomUUID().toString();
        var file = new MockMultipartFile("file", "template.json", "application/json",
                source.getBytes(StandardCharsets.UTF_8));
        var response = mvc.perform(multipart("/imports").file(file)
                        .param("inputKind", "PICKS_TEMPLATE")
                        .param("sourceFormat", "WATCHWISE_TEMPLATE_JSON")
                        .cookie(access(owner), new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, csrf))
                        .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode body = mapper.readTree(response.getResponse().getContentAsString());
        UUID id = UUID.fromString(body.path("id").asString());
        var importJob = jdbc.queryForObject("SELECT storage_key FROM import_jobs WHERE id=?", String.class, id);
        storageKeys.add(importJob);
        return id;
    }

    private void advanceToPreview(UUID importId) {
        worker.runAvailable(1);
        jdbc.update("UPDATE import_jobs SET next_run_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), importId);
        worker.runAvailable(1);
    }

    private JsonNode getJob(UUID importId) throws Exception {
        var response = mvc.perform(get("/imports/{importId}", importId).cookie(access(owner)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private JsonNode getPreview(UUID importId) throws Exception {
        var response = mvc.perform(get("/imports/{importId}/preview", importId)
                        .param("page", "0").param("size", "50").cookie(access(owner)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private JsonNode findItem(JsonNode preview, String kind) {
        for (JsonNode item : preview.path("items")) {
            if (kind.equals(item.path("kind").asString())) return item;
        }
        throw new AssertionError("Preview item not found: " + kind);
    }

    private String candidateIdFor(JsonNode envelope, String personTmdbId) {
        for (JsonNode candidate : envelope.path("candidates")) {
            if (personTmdbId.equals(candidate.path("target").path("personTmdbId").asString())) {
                return candidate.path("candidateId").asString();
            }
        }
        throw new AssertionError("Person candidate not found: " + personTmdbId);
    }

    private void patchResolution(UUID importId, long version, ImportResolutionOperation operation) throws Exception {
        String body = mapper.writeValueAsString(new ImportResolutionDTO(version, List.of(operation)));
        mvc.perform(authenticated(owner, HttpMethod.PATCH, "/imports/" + importId + "/resolutions", body))
                .andExpect(status().isOk());
    }

    private void commit(UUID importId, JsonNode job) throws Exception {
        String body = mapper.writeValueAsString(new ImportCommitDTO(
                job.path("previewVersion").asLong(), job.path("previewDigest").asString()));
        mvc.perform(authenticated(owner, HttpMethod.POST, "/imports/" + importId + "/commit", body))
                .andExpect(status().isAccepted());
    }
}
