package com.watchwise.watchwise_api.common.cache;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageBytes;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchCacheKey;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CacheEntryWeigherTest {
    private final CacheEntryWeigher weigher = new CacheEntryWeigher();

    record Payload(List<String> names) {}
    record Nested(Node node) {}
    record Node(Node next) {}
    record UnknownPayload(Object object) {}

    @Test
    void weighsPayloadGrowthAndRejectsValuesLargerThanTheirCache() {
        int small = weigher.weight("key", new Payload(List.of("a")), 100_000);
        int large = weigher.weight("key", new Payload(List.of("a".repeat(1_000))), 100_000);

        assertThat(small).isPositive();
        assertThat(large).isGreaterThan(small);
        assertThat(weigher.weight("key", new byte[4_096], 1_024)).isGreaterThan(1_024);
    }

    @Test
    void weighsTmdbSearchKeysPagesAndLookupVariants() {
        var key = TmdbSearchCacheKey.of("Arrival", TmdbSearchType.MOVIE, "pt-BR", "BR", 1);
        var result = new TmdbMovieSearchResult("1", "Arrival", "/poster.jpg", "2016-11-11");
        var page = new TmdbSearchPage<>(1, List.of(result), 1, 1);
        var found = new TmdbLookupResult.Found<>(page, TmdbLookupOrigin.REMOTE);

        assertThat(weigher.weight(key, found, 100_000)).isPositive();
        assertThat(weigher.weight("not-found", new TmdbLookupResult.NotFound<>(), 100_000)).isPositive();
        assertThat(weigher.weight("unavailable", new TmdbLookupResult.Unavailable<>(), 100_000)).isPositive();
        assertThat(weigher.weight("empty", new TmdbSearchPage<>(1, List.of(), 0, 0), 100_000)).isPositive();
    }

    @Test
    void accountsForImageBytesAndMediaTypeWithoutCopyingTheImage() {
        var shortImage = new TmdbImageBytes(new byte[32], MediaType.IMAGE_JPEG);
        var longImage = new TmdbImageBytes(new byte[512], MediaType.parseMediaType("image/jpeg; profile=watchwise"));
        var parameterizedImage = new TmdbImageBytes(new byte[32],
                MediaType.parseMediaType("image/jpeg; profile=" + "x".repeat(80)));

        int shortWeight = weigher.weight("image", shortImage, 10_000);
        int longWeight = weigher.weight("image", longImage, 10_000);
        int parameterizedWeight = weigher.weight("image", parameterizedImage, 10_000);

        assertThat(longWeight).isGreaterThan(shortWeight);
        assertThat(parameterizedWeight).isGreaterThan(shortWeight);
    }

    @Test
    void countsSupportedScalarsAndOptionalValues() {
        record ScalarPayload(UUID id, Instant createdAt, Optional<String> label) {}

        int absent = weigher.weight("key", new ScalarPayload(UUID.randomUUID(), Instant.EPOCH, Optional.empty()), 10_000);
        int present = weigher.weight("key", new ScalarPayload(UUID.randomUUID(), Instant.EPOCH, Optional.of("label")), 10_000);

        assertThat(absent).isPositive();
        assertThat(present).isGreaterThan(absent);
    }

    @Test
    void rejectsCyclesExcessiveDepthUnknownObjectsAndNodeLimitWithoutThrowing() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        Node deep = null;
        for (int index = 0; index < 40; index++) deep = new Node(deep);

        assertThat(weigher.weight("cycle", cycle, 10_000)).isGreaterThan(10_000);
        assertThat(weigher.weight("deep", new Nested(deep), 10_000)).isGreaterThan(10_000);
        assertThat(weigher.weight("unknown", new UnknownPayload(new Object()), 10_000)).isGreaterThan(10_000);

        var many = new ArrayList<Object>(10_001);
        for (int index = 0; index < 10_001; index++) many.add("x");
        assertThat(weigher.weight("many", many, 1_000_000)).isGreaterThan(1_000_000);
    }

    @Test
    void supportsReferenceAndPrimitiveArraysAndRejectsInvalidBudgets() {
        assertThat(weigher.weight("refs", new String[]{"a", "longer"}, 10_000)).isPositive();
        assertThat(weigher.weight("ints", new int[]{1, 2, 3}, 10_000)).isPositive();
        assertThat(weigher.weight("map", Map.of("key", List.of("value")), 10_000)).isPositive();
        assertThat(weigher.weight("oversized", new byte[4_096], 1_024)).isGreaterThan(1_024);
        assertThatIllegalArgumentException().isThrownBy(() -> weigher.weight("key", "value", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> weigher.weight("key", "value", Integer.MAX_VALUE));
    }
}
