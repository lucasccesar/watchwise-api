package com.watchwise.watchwise_api.platformimport.controller;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.platformimport.entity.ImportFileCleanupTask;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.repository.ImportFileCleanupTaskRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportCleanupJob;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
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
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.import.max-upload-bytes=4",
        "app.import.max-pending-jobs-per-user=2",
        "app.import.max-pending-upload-bytes-per-user=5",
        "app.import.max-pending-upload-bytes-global=20",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false",
        "spring.servlet.multipart.max-file-size=1MB",
        "spring.servlet.multipart.max-request-size=2MB"
})
@AutoConfigureMockMvc
@Testcontainers
class ImportControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
        registry.add("app.import.storage-directory", () -> java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "watchwise-import-controller-tests").toAbsolutePath().toString());
        registry.add("app.import.staging-directory", () -> java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "watchwise-import-controller-staging-tests").toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    @Autowired
    private ImportItemRepository importItemRepository;

    @Autowired
    private ImportFileCleanupTaskRepository cleanupTaskRepository;

    @Autowired
    private ImportCleanupJob cleanupJob;

    @Autowired
    private ImportLimits importLimits;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CookieUtil cookieUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private PrivateImportFileStore importFileStore;

    @BeforeEach
    void setUp() throws Exception {
        for (ImportJob oldJob : importJobRepository.findAll()) {
            importFileStore.delete(oldJob.getStorageKey());
        }
        importJobRepository.deleteAll();
        cleanupTaskRepository.deleteAll();
        importLimits.setMaxUploadBytes(4);
        importLimits.setMaxPendingJobsPerUser(2);
        importLimits.setMaxPendingUploadBytesPerUser(5);
        importLimits.setMaxPendingUploadBytesGlobal(20);
    }

    @Test
    @DisplayName("[createImport] Should Persist Private Upload And Return Accepted Job - When Format Is Registered")
    void shouldPersistPrivateUploadAndReturnAcceptedJobWhenFormatIsRegistered() throws Exception {
        RegisteredUser owner = createUser();

        MvcResult result = mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'[', ']'}))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.state").value("ANALYZING"))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.sourceNamespace").doesNotExist())
                .andReturn();

        UUID jobId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString());
        ImportJob job = importJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getUserId()).isEqualTo(owner.id());
        assertThat(job.getInputKind()).isEqualTo(ImportInputKind.SINGLE_LIST);
        assertThat(job.getSourceFormat()).isEqualTo(ImportSourceFormat.TRAKT_JSON);
        assertThat(job.getPlatform()).isEqualTo(ImportPlatform.TRAKT);
        assertThat(job.getState()).isEqualTo(ImportJobState.ANALYZING);
        assertThat(job.getUploadSizeBytes()).isEqualTo(2);
        assertThat(job.getZipDigest()).matches("[a-f0-9]{64}");
        assertThat(job.isWorkPending()).isTrue();
    }

    @Test
    @DisplayName("[createImport] Should Return 401 ApiError - When Access Cookie Is Missing")
    void shouldReturn401ApiErrorWhenAccessCookieIsMissing() throws Exception {
        Cookie csrfToken = new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, UUID.randomUUID().toString());
        mockMvc.perform(multipart("/imports")
                        .file(file("list.json", "application/json", new byte[]{'[', ']'}))
                        .cookie(csrfToken)
                        .header("X-XSRF-TOKEN", csrfToken.getValue())
                        .param("inputKind", "SINGLE_LIST")
                        .param("sourceFormat", "TRAKT_JSON")
                        .param("platform", "TRAKT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("[createImport] Should Return 403 ApiError - When Csrf Header Is Missing")
    void shouldReturn403ApiErrorWhenCsrfHeaderIsMissing() throws Exception {
        RegisteredUser owner = createUser();

        mockMvc.perform(multipart("/imports")
                        .file(file("list.json", "application/json", new byte[]{'[', ']'}))
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .param("inputKind", "SINGLE_LIST")
                        .param("sourceFormat", "TRAKT_JSON")
                        .param("platform", "TRAKT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("[createImport] Should Return 400 ApiError - When Multipart File Part Is Missing")
    void shouldReturn400ApiErrorWhenMultipartFilePartIsMissing() throws Exception {
        RegisteredUser owner = createUser();

        mockMvc.perform(multipart("/imports")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .param("inputKind", "SINGLE_LIST")
                        .param("sourceFormat", "TRAKT_JSON")
                        .param("platform", "TRAKT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("[getImport] Should Return 404 ApiError - When Job Belongs To Another User")
    void shouldReturn404ApiErrorWhenJobBelongsToAnotherUser() throws Exception {
        RegisteredUser owner = createUser();
        RegisteredUser other = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});

        mockMvc.perform(get("/imports/{importId}", jobId)
                        .cookie(other.accessToken(), other.csrfToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("[selectImport] Should Advance Preview Version And Return Digest - When Owner Saves Selection")
    void shouldAdvancePreviewVersionAndReturnDigestWhenOwnerSavesSelection() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});

        mockMvc.perform(put("/imports/{importId}/selection", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previewVersion":0,"history":false,"watchlist":false,"listIds":[],"metadata":{"name":"Imported list"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewVersion").value(1))
                .andExpect(jsonPath("$.previewDigest").isNotEmpty());
    }

    @Test
    @DisplayName("[getImportPreview] Should Return Owner-Scoped Preview Page - When Job Has No Normalized Items")
    void shouldReturnOwnerScopedPreviewPageWhenJobHasNoNormalizedItems() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});

        mockMvc.perform(get("/imports/{importId}/preview", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").value(jobId.toString()))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(get("/imports/{importId}/result", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").value(jobId.toString()));
    }

    @Test
    @DisplayName("[getImportPreview] Should Hide Internal Source Locators - When Returning Owner Items")
    void shouldHideInternalSourceLocatorsWhenReturningOwnerItems() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        ImportJob job = importJobRepository.findById(jobId).orElseThrow();
        importItemRepository.saveAndFlush(ImportItem.builder()
                .jobId(jobId)
                .planGeneration(0)
                .sourcePath("../../private/archive/member.csv")
                .recordLocator("../../private/archive/member.csv#row-7")
                .normalizedKind(com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.LIST_ITEM)
                .sourceNamespace(job.getSourceNamespace())
                .objectKind("LIST_ITEM")
                .sourceId("external-item-7")
                .payloadJson("{\"title\":\"Arrival\",\"year\":2016}")
                .ordinal(7)
                .build());

        mockMvc.perform(get("/imports/{importId}/preview", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.items[0].sourcePath").doesNotExist())
                .andExpect(jsonPath("$.items[0].recordLocator").doesNotExist());
    }

    @Test
    @DisplayName("[resolveImport] Should Return 400 ApiError - When Resolution Operation List Is Empty")
    void shouldReturn400ApiErrorWhenResolutionOperationListIsEmpty() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});

        mockMvc.perform(patch("/imports/{importId}/resolutions", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewVersion\":0,\"operations\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("[commitImport] Should Be Idempotent For The Same Preview Digest - When Commit Is Repeated")
    void shouldBeIdempotentForTheSamePreviewDigestWhenCommitIsRepeated() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        ImportJob readyJob = importJobRepository.findById(jobId).orElseThrow();
        readyJob.setState(ImportJobState.READY);
        readyJob.setWorkPending(false);
        importJobRepository.saveAndFlush(readyJob);
        String digest = mockMvc.perform(get("/imports/{importId}", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andReturn().getResponse().getContentAsString();
        String previewDigest = objectMapper.readTree(digest).get("previewDigest").asString();
        String body = "{\"previewVersion\":0,\"digest\":\"" + previewDigest + "\"}";

        mockMvc.perform(post("/imports/{importId}/commit", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("IMPORTING"));
        mockMvc.perform(post("/imports/{importId}/commit", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("IMPORTING"));
    }

    @Test
    @DisplayName("[commitImport] Should Reject A Second Active Writer - When The Partial Unique Index Conflicts")
    void shouldRejectASecondActiveWriterWhenThePartialUniqueIndexConflicts() throws Exception {
        RegisteredUser owner = createUser();
        UUID firstJobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        UUID secondJobId = uploadAndReturnJobId(owner, new byte[]{'{', '}'});
        for (UUID jobId : List.of(firstJobId, secondJobId)) {
            ImportJob job = importJobRepository.findById(jobId).orElseThrow();
            job.setState(ImportJobState.READY);
            job.setWorkPending(false);
            importJobRepository.saveAndFlush(job);
        }
        String firstPreview = mockMvc.perform(get("/imports/{importId}", firstJobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andReturn().getResponse().getContentAsString();
        String secondPreview = mockMvc.perform(get("/imports/{importId}", secondJobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andReturn().getResponse().getContentAsString();
        String firstDigest = objectMapper.readTree(firstPreview).get("previewDigest").asString();
        String secondDigest = objectMapper.readTree(secondPreview).get("previewDigest").asString();

        mockMvc.perform(post("/imports/{importId}/commit", firstJobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewVersion\":0,\"digest\":\"" + firstDigest + "\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/imports/{importId}/commit", secondJobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewVersion\":0,\"digest\":\"" + secondDigest + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(importJobRepository.findById(firstJobId).orElseThrow().getState())
                .isEqualTo(ImportJobState.IMPORTING);
        assertThat(importJobRepository.findById(secondJobId).orElseThrow().getState())
                .isEqualTo(ImportJobState.READY);
    }

    @Test
    @DisplayName("[commitImport] Should Return 409 ApiError - When Preview Digest Does Not Match")
    void shouldReturn409ApiErrorWhenPreviewDigestDoesNotMatch() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        ImportJob readyJob = importJobRepository.findById(jobId).orElseThrow();
        readyJob.setState(ImportJobState.READY);
        importJobRepository.saveAndFlush(readyJob);

        mockMvc.perform(post("/imports/{importId}/commit", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewVersion\":0,\"digest\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        assertThat(importJobRepository.findById(jobId).orElseThrow().getState()).isEqualTo(ImportJobState.READY);
    }

    @Test
    @DisplayName("[commitImport] Should Return 409 ApiError - When Another Job For The Owner Is Already Applying")
    void shouldReturn409ApiErrorWhenAnotherJobForTheOwnerIsAlreadyApplying() throws Exception {
        RegisteredUser owner = createUser();
        UUID firstJobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        UUID secondJobId = uploadAndReturnJobId(owner, new byte[]{'{', '}'});
        for (UUID jobId : List.of(firstJobId, secondJobId)) {
            ImportJob job = importJobRepository.findById(jobId).orElseThrow();
            job.setState(ImportJobState.READY);
            job.setWorkPending(false);
            importJobRepository.saveAndFlush(job);
        }
        String firstPreview = mockMvc.perform(get("/imports/{importId}", firstJobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andReturn().getResponse().getContentAsString();
        String firstDigest = objectMapper.readTree(firstPreview).get("previewDigest").asString();
        String secondPreview = mockMvc.perform(get("/imports/{importId}", secondJobId)
                        .cookie(owner.accessToken(), owner.csrfToken()))
                .andReturn().getResponse().getContentAsString();
        String secondDigest = objectMapper.readTree(secondPreview).get("previewDigest").asString();

        mockMvc.perform(post("/imports/{importId}/commit", firstJobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewVersion\":0,\"digest\":\"" + firstDigest + "\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/imports/{importId}/commit", secondJobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewVersion\":0,\"digest\":\"" + secondDigest + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(importJobRepository.findById(firstJobId).orElseThrow().getState())
                .isEqualTo(ImportJobState.IMPORTING);
        assertThat(importJobRepository.findById(secondJobId).orElseThrow().getState())
                .isEqualTo(ImportJobState.READY);
    }

    @Test
    @DisplayName("[cancelImport] Should Mark Job Cancelled - When Owner Cancels A Pending Job")
    void shouldMarkJobCancelledWhenOwnerCancelsPendingJob() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});

        mockMvc.perform(post("/imports/{importId}/cancel", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        assertThat(importJobRepository.findById(jobId).orElseThrow().isWorkPending()).isFalse();
    }

    @Test
    @DisplayName("[retryImport] Should Queue Failed Job Again - When Stored Source File Still Exists")
    void shouldQueueFailedJobAgainWhenStoredSourceFileStillExists() throws Exception {
        RegisteredUser owner = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        ImportJob failedJob = importJobRepository.findById(jobId).orElseThrow();
        failedJob.setState(ImportJobState.FAILED);
        failedJob.setWorkPending(false);
        importJobRepository.saveAndFlush(failedJob);
        mockMvc.perform(post("/imports/{importId}/retry", jobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("ANALYZING"));
        assertThat(importJobRepository.findById(jobId).orElseThrow().isWorkPending()).isTrue();
    }

    @Test
    @DisplayName("[retryImport] Should Return 429 ApiError - When Retry Would Exceed Pending Job Limit")
    void shouldReturn429ApiErrorWhenRetryWouldExceedPendingJobLimit() throws Exception {
        RegisteredUser owner = createUser();
        UUID failedJobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        ImportJob failedJob = importJobRepository.findById(failedJobId).orElseThrow();
        failedJob.setState(ImportJobState.FAILED);
        failedJob.setWorkPending(false);
        importJobRepository.saveAndFlush(failedJob);

        importLimits.setMaxPendingJobsPerUser(1);
        uploadAndReturnJobId(owner, new byte[]{'a'});

        mockMvc.perform(post("/imports/{importId}/retry", failedJobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        ImportJob persistedFailedJob = importJobRepository.findById(failedJobId).orElseThrow();
        assertThat(persistedFailedJob.getState()).isEqualTo(ImportJobState.FAILED);
        assertThat(persistedFailedJob.isWorkPending()).isFalse();
    }

    @Test
    @DisplayName("[uploadQuota] Should Count Failed Stored Bytes And Allow Retry Without Double Counting")
    void shouldCountFailedStoredBytesAndAllowRetryWithoutDoubleCounting() throws Exception {
        RegisteredUser owner = createUser();
        UUID failedJobId = uploadAndReturnJobId(owner, new byte[]{'a', 'b', 'c'});
        ImportJob failedJob = importJobRepository.findById(failedJobId).orElseThrow();
        failedJob.setState(ImportJobState.FAILED);
        failedJob.setWorkPending(false);
        importJobRepository.saveAndFlush(failedJob);

        importLimits.setMaxPendingUploadBytesPerUser(5);
        mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'d', 'e', 'f'}))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        mockMvc.perform(post("/imports/{importId}/retry", failedJobId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("ANALYZING"));

        ImportJob persistedFailedJob = importJobRepository.findById(failedJobId).orElseThrow();
        assertThat(persistedFailedJob.getState()).isEqualTo(ImportJobState.ANALYZING);
        assertThat(persistedFailedJob.isWorkPending()).isTrue();
    }

    @Test
    @DisplayName("[uploadQuota] Should Count Failed Bytes Globally And Allow Their Retry Without Double Counting")
    void shouldCountFailedBytesGloballyAndAllowRetryWithoutDoubleCounting() throws Exception {
        RegisteredUser failedOwner = createUser();
        UUID failedJobId = uploadAndReturnJobId(failedOwner, new byte[]{'a', 'b', 'c'});
        ImportJob failedJob = importJobRepository.findById(failedJobId).orElseThrow();
        failedJob.setState(ImportJobState.FAILED);
        failedJob.setWorkPending(false);
        importJobRepository.saveAndFlush(failedJob);

        RegisteredUser activeOwner = createUser();
        importLimits.setMaxPendingUploadBytesGlobal(5);
        mockMvc.perform(upload(activeOwner, "list.json", "application/json", new byte[]{'d', 'e', 'f'}))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        mockMvc.perform(post("/imports/{importId}/retry", failedJobId)
                        .cookie(failedOwner.accessToken(), failedOwner.csrfToken())
                        .header("X-XSRF-TOKEN", failedOwner.csrfToken().getValue()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("ANALYZING"));

        assertThat(importJobRepository.findById(failedJobId).orElseThrow().getState())
                .isEqualTo(ImportJobState.ANALYZING);
    }

    @Test
    @DisplayName("[retryImport] Should Recheck Quota After A Concurrent Upload - When Retry Validates Its Source First")
    void shouldRecheckQuotaAfterAConcurrentUploadWhenRetryValidatesItsSourceFirst() throws Exception {
        RegisteredUser owner = createUser();
        UUID failedJobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});
        ImportJob failedJob = importJobRepository.findById(failedJobId).orElseThrow();
        failedJob.setState(ImportJobState.FAILED);
        failedJob.setWorkPending(false);
        importJobRepository.saveAndFlush(failedJob);
        importLimits.setMaxPendingJobsPerUser(1);

        CountDownLatch retryStartedFileCheck = new CountDownLatch(1);
        CountDownLatch allowRetryToContinue = new CountDownLatch(1);
        doAnswer(invocation -> {
            retryStartedFileCheck.countDown();
            assertThat(allowRetryToContinue.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(importFileStore).open(failedJob.getStorageKey());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> retryStatus = executor.submit(() -> mockMvc.perform(
                            post("/imports/{importId}/retry", failedJobId)
                                    .cookie(owner.accessToken(), owner.csrfToken())
                                    .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                    .andReturn().getResponse().getStatus());

            assertThat(retryStartedFileCheck.await(10, TimeUnit.SECONDS)).isTrue();
            int uploadStatus = mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'x'}))
                    .andReturn().getResponse().getStatus();
            allowRetryToContinue.countDown();

            assertThat(uploadStatus).isEqualTo(202);
            assertThat(retryStatus.get()).isEqualTo(429);
        } finally {
            allowRetryToContinue.countDown();
        }

        assertThat(importJobRepository.findAll().stream()
                .filter(job -> job.getState() != ImportJobState.FAILED)
                .count()).isEqualTo(1);
        assertThat(importJobRepository.findById(failedJobId).orElseThrow().getState())
                .isEqualTo(ImportJobState.FAILED);
    }

    @Test
    @DisplayName("[createImport] Should Return 400 ApiError - When Platform Does Not Match Source Format")
    void shouldReturn400ApiErrorWhenPlatformDoesNotMatchSourceFormat() throws Exception {
        RegisteredUser owner = createUser();

        mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'[', ']'}
                        , "SINGLE_LIST", "TRAKT_JSON", "LETTERBOXD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        assertThat(importJobRepository.count()).isZero();
    }

    @Test
    @DisplayName("[createImport] Should Return 400 ApiError - When Import Mode Has No Registered Handler")
    void shouldReturn400ApiErrorWhenImportModeHasNoRegisteredHandler() throws Exception {
        RegisteredUser owner = createUser();

        mockMvc.perform(multipart("/imports")
                        .file(file("template.json", "application/json", new byte[]{'{', '}'}))
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .param("inputKind", "PICKS_TEMPLATE")
                        .param("sourceFormat", "WATCHWISE_TEMPLATE_JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        assertThat(importJobRepository.count()).isZero();
    }

    @Test
    @DisplayName("[createImport] Should Return 413 ApiError - When Uploaded File Exceeds The Configured Limit")
    void shouldReturn413ApiErrorWhenUploadedFileExceedsTheConfiguredLimit() throws Exception {
        RegisteredUser owner = createUser();

        mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'[', '1', ']', ' ', ' '}))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.error").value("Payload Too Large"));
        assertThat(importJobRepository.count()).isZero();
    }

    @Test
    @DisplayName("[createImport] Should Reject Concurrent Uploads After Atomic Owner Quota Reservation - When Pending Bytes Exceed Limit")
    void shouldRejectConcurrentUploadsAfterAtomicOwnerQuotaReservationWhenPendingBytesExceedLimit() throws Exception {
        RegisteredUser owner = createUser();
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> uploadAfterBarrier(owner, start, new byte[]{'a', 'b', 'c'}));
            Future<Integer> second = executor.submit(() -> uploadAfterBarrier(owner, start, new byte[]{'d', 'e', 'f'}));

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(202, 429);
        }
        assertThat(importJobRepository.count()).isEqualTo(1);
        assertThat(importJobRepository.findAll()).extracting(ImportJob::getUploadSizeBytes).containsExactly(3L);
    }

    @Test
    @DisplayName("[createImport] Should Enforce Global Pending Byte Quota - When Different Owners Upload Concurrently")
    void shouldEnforceGlobalPendingByteQuotaWhenDifferentOwnersUploadConcurrently() throws Exception {
        RegisteredUser firstOwner = createUser();
        RegisteredUser secondOwner = createUser();
        importLimits.setMaxPendingUploadBytesGlobal(5);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> uploadAfterBarrier(firstOwner, start, new byte[]{'a', 'b', 'c'}));
            Future<Integer> second = executor.submit(() -> uploadAfterBarrier(secondOwner, start, new byte[]{'d', 'e', 'f'}));

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(202, 429);
        }
        assertThat(importJobRepository.count()).isEqualTo(1);
        assertThat(importJobRepository.findAll()).extracting(ImportJob::getUploadSizeBytes).containsExactly(3L);
    }

    @Test
    @DisplayName("[createImport] Should Enforce Pending Job Count - When Owner Reaches Configured Limit")
    void shouldEnforcePendingJobCountWhenOwnerReachesConfiguredLimit() throws Exception {
        RegisteredUser owner = createUser();
        importLimits.setMaxPendingJobsPerUser(1);

        uploadAndReturnJobId(owner, new byte[]{'a'});
        mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'b'}))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        assertThat(importJobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[createImport] Should Keep Reserved Bytes Until Cleanup Confirms Removal - When Durable Storage Fails")
    void shouldKeepReservedBytesUntilCleanupConfirmsRemovalWhenDurableStorageFails() throws Exception {
        RegisteredUser owner = createUser();
        doThrow(new IOException("private storage failure"))
                .when(importFileStore).store(any(), anyLong(), anyString());

        mockMvc.perform(upload(owner, "list.json", "application/json", new byte[]{'[', ']'}))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

        ImportJob failedJob = importJobRepository.findAll().getFirst();
        assertThat(failedJob.getState()).isEqualTo(ImportJobState.FAILED);
        assertThat(failedJob.getUploadSizeBytes()).isEqualTo(2);
        assertThat(failedJob.isWorkPending()).isFalse();
        assertThat(cleanupTaskRepository.findAll())
                .extracting(ImportFileCleanupTask::getStorageKey)
                .containsExactly(failedJob.getStorageKey());

        cleanupJob.runOnce(java.time.Instant.now());

        assertThat(importJobRepository.findById(failedJob.getId()).orElseThrow().getUploadSizeBytes()).isZero();
        assertThat(cleanupTaskRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[cancelImport] Should Return 404 ApiError - When Job Belongs To Another User")
    void shouldReturn404ApiErrorWhenCancelTargetsAnotherUsersJob() throws Exception {
        RegisteredUser owner = createUser();
        RegisteredUser other = createUser();
        UUID jobId = uploadAndReturnJobId(owner, new byte[]{'[', ']'});

        mockMvc.perform(post("/imports/{importId}/cancel", jobId)
                        .cookie(other.accessToken(), other.csrfToken())
                        .header("X-XSRF-TOKEN", other.csrfToken().getValue()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        assertThat(importJobRepository.findById(jobId).orElseThrow().getState()).isEqualTo(ImportJobState.ANALYZING);
    }

    private int uploadAfterBarrier(RegisteredUser owner, CyclicBarrier start, byte[] content) throws Exception {
        start.await();
        return mockMvc.perform(upload(owner, "list.json", "application/json", content))
                .andReturn().getResponse().getStatus();
    }

    private UUID uploadAndReturnJobId(RegisteredUser owner, byte[] content) throws Exception {
        MvcResult result = mockMvc.perform(upload(owner, "list.json", "application/json", content))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private MockMultipartHttpServletRequestBuilder upload(RegisteredUser owner, String filename, String contentType,
                                                  byte[] content) {
        return upload(owner, filename, contentType, content, "SINGLE_LIST", "TRAKT_JSON", "TRAKT");
    }

    private MockMultipartHttpServletRequestBuilder upload(RegisteredUser owner, String filename, String contentType,
                                                  byte[] content, String inputKind, String sourceFormat, String platform) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/imports")
                .file(file(filename, contentType, content))
                .cookie(owner.accessToken(), owner.csrfToken())
                .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                .param("inputKind", inputKind)
                .param("sourceFormat", sourceFormat);
        if (platform != null) builder.param("platform", platform);
        return builder;
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private RegisteredUser createUser() {
        LocalDateTime now = LocalDateTime.now();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = userRepository.save(User.builder()
                .username("import" + suffix)
                .name("Import User " + suffix)
                .email("import" + suffix + "@example.com")
                .password("Password123")
                .createdAt(now)
                .updatedAt(now)
                .build());
        ResponseCookie access = cookieUtil.buildAccessTokenCookie(jwtService.generateToken(user.getId(), TokenType.ACCESS));
        Cookie accessToken = new Cookie(access.getName(), access.getValue());
        accessToken.setHttpOnly(access.isHttpOnly());
        Cookie csrfToken = new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, UUID.randomUUID().toString());
        return new RegisteredUser(user.getId(), accessToken, csrfToken);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

}
