package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportMultipartRequestGateFilterTest {

    private ImportMultipartRequestGateFilter filter;
    private ImportTemporaryFiles temporaryFiles;

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws Exception {
        temporaryFiles = new ImportTemporaryFiles(root.resolve("work"), 1024, 2048, 1, 1);
        filter = new ImportMultipartRequestGateFilter(temporaryFiles, new ObjectMapper(), Clock.systemUTC());
    }

    @AfterEach
    void closeTemporaryFiles() {
        temporaryFiles.close();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Return 429 ApiError - When Concurrent Import Upload Slots Are Full")
    void shouldReturn429ApiErrorWhenConcurrentImportUploadSlotsAreFull() throws Exception {
        CountDownLatch firstRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread firstRequest = new Thread(() -> {
            try {
                filter.doFilter(importMultipartRequest(), new MockHttpServletResponse(), (request, response) -> {
                    firstRequestEntered.countDown();
                    try {
                        if (!releaseFirstRequest.await(5, TimeUnit.SECONDS)) {
                            throw new ServletException("Timed out while holding the upload slot");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(exception);
                    }
                });
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        firstRequest.start();

        assertThat(firstRequestEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(temporaryFiles.reservedBytes()).isEqualTo(2048);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(importMultipartRequest(), response, (request, servletResponse) -> {
            throw new AssertionError("A saturated upload must not reach the multipart resolver");
        });

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"status\":429", "\"error\":\"Too Many Requests\"",
                "\"path\":\"/api/v1/imports\"");

        releaseFirstRequest.countDown();
        firstRequest.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(firstRequest.isAlive()).isFalse();
        assertThat(firstFailure.get()).isNull();
        assertThat(temporaryFiles.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Bypass Upload Semaphore - When Request Is Not A Multipart Import Post")
    void shouldBypassUploadSemaphoreWhenRequestIsNotMultipartImportPost() throws Exception {
        CountDownLatch firstRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        Thread firstRequest = new Thread(() -> {
            try {
                filter.doFilter(importMultipartRequest(), new MockHttpServletResponse(), (request, response) -> {
                    firstRequestEntered.countDown();
                    try {
                        if (!releaseFirstRequest.await(5, TimeUnit.SECONDS)) {
                            throw new ServletException("Timed out while holding the upload slot");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(exception);
                    }
                });
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        firstRequest.start();
        assertThat(firstRequestEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(temporaryFiles.reservedBytes()).isEqualTo(2048);

        MockHttpServletRequest unrelatedRequest = new MockHttpServletRequest("POST", "/api/v1/other");
        unrelatedRequest.setContextPath("/api/v1");
        unrelatedRequest.setContentType("multipart/form-data; boundary=test");
        MockHttpServletResponse unrelatedResponse = new MockHttpServletResponse();
        filter.doFilter(unrelatedRequest, unrelatedResponse,
                (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));

        MockHttpServletRequest wrongMethod = importMultipartRequest();
        wrongMethod.setMethod("PUT");
        MockHttpServletResponse methodResponse = new MockHttpServletResponse();
        filter.doFilter(wrongMethod, methodResponse,
                (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));

        MockHttpServletRequest wrongContentType = importMultipartRequest();
        wrongContentType.setContentType("application/json");
        MockHttpServletResponse contentTypeResponse = new MockHttpServletResponse();
        filter.doFilter(wrongContentType, contentTypeResponse,
                (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));

        assertThat(unrelatedResponse.getStatus()).isEqualTo(204);
        assertThat(methodResponse.getStatus()).isEqualTo(204);
        assertThat(contentTypeResponse.getStatus()).isEqualTo(204);

        releaseFirstRequest.countDown();
        firstRequest.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(firstRequest.isAlive()).isFalse();
        assertThat(temporaryFiles.reservedBytes()).isZero();
    }

    @Test
    @DisplayName("[doFilterInternal] Should Release Upload Slot - When Request Chain Throws")
    void shouldReleaseUploadSlotWhenRequestChainThrows() {
        assertThatThrownBy(() -> filter.doFilter(importMultipartRequest(), new MockHttpServletResponse(),
                (request, response) -> { throw new ServletException("resolver failed"); }))
                .isInstanceOf(ServletException.class)
                .hasMessage("resolver failed");
        assertThat(temporaryFiles.reservedBytes()).isZero();

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatCode(() -> filter.doFilter(importMultipartRequest(), response,
                (request, servletResponse) -> ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(204)))
                .doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(temporaryFiles.reservedBytes()).isZero();
    }

    private MockHttpServletRequest importMultipartRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/imports");
        request.setContextPath("/api/v1");
        request.setContentType("multipart/form-data; boundary=test");
        return request;
    }
}
