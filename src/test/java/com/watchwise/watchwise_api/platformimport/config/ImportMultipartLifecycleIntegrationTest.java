package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.Http11Nio2Protocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImportMultipartLifecycleIntegrationTest {
    @TempDir
    Path root;

    @Test
    void rejectsAnExtraUploadBeforeTomcatCreatesAnotherMultipartFile() throws Exception {
        try (var temporary = new ImportTemporaryFiles(root.resolve("import-work"), 1024, 2048, 1, 1)) {
            CountDownLatch servletEntered = new CountDownLatch(1);
            CountDownLatch releaseServlet = new CountDownLatch(1);
            AtomicInteger servletCalls = new AtomicInteger();

            Tomcat server = new Tomcat();
            server.setBaseDir(root.resolve("tomcat").toString());
            Path webRoot = Files.createDirectories(root.resolve("webapp"));
            Connector connector = new Connector(Http11Nio2Protocol.class.getName());
            connector.setPort(0);
            connector.setProperty("address", "127.0.0.1");
            server.setConnector(connector);
            Context context = server.addContext("/api/v1", webRoot.toString());
            Wrapper servlet = Tomcat.addServlet(context, "multipart-test", new HttpServlet() {
                @Override
                protected void doPost(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException {
                    try {
                        request.getParts();
                        servletCalls.incrementAndGet();
                        servletEntered.countDown();
                        if (!releaseServlet.await(15, TimeUnit.SECONDS)) {
                            throw new ServletException("Timed out while holding parsed multipart content");
                        }
                        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(exception);
                    }
                }
            });
            servlet.setMultipartConfigElement(new jakarta.servlet.MultipartConfigElement(
                    temporary.multipartDirectory().toString(), 1024, 2048, 0));
            context.addServletMappingDecoded("/imports", "multipart-test");

            FilterDef definition = new FilterDef();
            definition.setFilterName("import-multipart-gate");
            definition.setFilter(new ImportMultipartRequestGateFilter(temporary,
                    new tools.jackson.databind.ObjectMapper(), Clock.systemUTC()));
            context.addFilterDef(definition);
            FilterMap mapping = new FilterMap();
            mapping.setFilterName("import-multipart-gate");
            mapping.addURLPattern("/*");
            context.addFilterMapBefore(mapping);

            ExecutorService requests = Executors.newSingleThreadExecutor();
            Future<HttpResult> firstResponse = null;
            try {
                server.start();
                URI uploadUri = URI.create("http://127.0.0.1:" + server.getConnector().getLocalPort()
                        + "/api/v1/imports");
                firstResponse = requests.submit(() -> postMultipart(uploadUri, "first"));

                assertThat(servletEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(countFiles(temporary.multipartDirectory())).isEqualTo(1);
                assertThat(temporary.reservedBytes()).isEqualTo(2048);

                HttpResult rejected = postMultipart(uploadUri, "second");
                assertThat(rejected.statusCode()).isEqualTo(429);
                assertThat(rejected.body()).contains("Too Many Requests", "/api/v1/imports");
                assertThat(servletCalls).hasValue(1);
                assertThat(countFiles(temporary.multipartDirectory())).isEqualTo(1);
                assertThat(temporary.reservedBytes()).isEqualTo(2048);
            } finally {
                releaseServlet.countDown();
                try {
                    if (firstResponse != null) {
                        assertThat(firstResponse.get(10, TimeUnit.SECONDS).statusCode())
                                .isEqualTo(HttpServletResponse.SC_NO_CONTENT);
                    }
                } finally {
                    requests.shutdownNow();
                    try {
                        server.stop();
                    } finally {
                        server.destroy();
                    }
                }
            }

            assertThat(countFiles(temporary.multipartDirectory())).isZero();
            assertThat(temporary.reservedBytes()).isZero();
        }
    }

    private HttpResult postMultipart(URI uri, String filename) throws IOException {
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + ".txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "payload\r\n--boundary--\r\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=boundary");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        InputStream responseBody = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String content = responseBody == null ? "" : new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new HttpResult(status, content);
    }

    private long countFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private record HttpResult(int statusCode, String body) { }
}
