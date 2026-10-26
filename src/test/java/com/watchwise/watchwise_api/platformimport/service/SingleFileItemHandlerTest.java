package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.config.ImportModeConfiguration;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktItemParserImpl;
import com.watchwise.watchwise_api.platformimport.service.impl.SingleListImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.SingleWatchlistImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.StandaloneListIdentityResolver;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SingleFileItemHandlerTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ImportReadContext CONTEXT = new ImportReadContext(OWNER, JOB, "synthetic", "TRAKT:OWNER:" + OWNER, ZoneOffset.UTC);
    private static final ImportSelection SELECTION = new ImportSelection(false, false, Set.of(), null);
    private static final String TRAKT = "[{\"type\":\"movie\",\"movie\":{\"title\":\"Example\",\"year\":2001,\"ids\":{\"tmdb\":11,\"trakt\":22}}}]";
    private static final String LIST = "7\nName,Description,Tags,URL\nFavorites,Notes,,https://letterboxd.com/alice/list/favorites/\nDate,Name,Year,Letterboxd URI,Rating,Watched Date\n2026-01-01,Example,2001,https://boxd.it/AbC,,\n";
    private static final String WATCHLIST = "Date,Name,Year,Letterboxd URI\n2026-01-01,Example,2001,https://boxd.it/AbC\n";

    @Test
    @DisplayName("[normalize] Should Emit Only List Members - When Trakt Array Uses Single List Mode")
    void shouldEmitOnlyListMembersWhenTraktArrayUsesSingleListMode() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        var metadata = new ObjectMapper().createObjectNode().put("name", "Favorites");
        listHandler(ImportSourceFormat.TRAKT_JSON, TRAKT).normalize(CONTEXT,
                new ImportSelection(false, false, Set.of(), metadata), records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.LIST_ITEM, ImportRecordKind.LIST_METADATA);
        assertThat(records.getFirst().parentSourceId()).isEqualTo(records.getLast().sourceKey().sourceId());
    }

    @Test
    @DisplayName("[normalize] Should Keep Trakt Item Namespace Shared With ZIP - When Source Account Is Bound")
    void shouldKeepTraktItemNamespaceSharedWithZipWhenSourceAccountIsBound() throws Exception {
        var accountContext = new ImportReadContext(OWNER, JOB, "synthetic", "TRAKT:ACCOUNT:alice", ZoneOffset.UTC);
        var metadata = new ObjectMapper().createObjectNode().put("name", "Favorites").put("sourceListId", "42");
        List<NormalizedImportRecord> records = new ArrayList<>();

        listHandler(ImportSourceFormat.TRAKT_JSON, TRAKT).normalize(accountContext,
                new ImportSelection(false, false, Set.of(), metadata), records::add);

        assertThat(records.getFirst().sourceKey().namespace()).isEqualTo("TRAKT:ACCOUNT:alice");
        assertThat(records.getLast().sourceKey().namespace()).isEqualTo("TRAKT:ACCOUNT:alice");
        assertThat(records.getLast().sourceKey().sourceId()).isEqualTo("42");
    }

    @Test
    @DisplayName("[normalize] Should Emit Only Watchlist Members - When Same Trakt Array Uses Single Watchlist Mode")
    void shouldEmitOnlyWatchlistMembersWhenSameTraktArrayUsesSingleWatchlistMode() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        watchlistHandler(ImportSourceFormat.TRAKT_JSON, TRAKT).normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind).containsExactly(ImportRecordKind.WATCHLIST_ITEM);
    }

    @Test
    @DisplayName("[normalize] Should Preserve List Metadata And Members - When Letterboxd Version Seven List Is Uploaded")
    void shouldPreserveListMetadataAndMembersWhenLetterboxdVersionSevenListIsUploaded() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        listHandler(ImportSourceFormat.LETTERBOXD_CSV, LIST).normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.LIST_METADATA, ImportRecordKind.LIST_ITEM);
        assertThat(records.getFirst().payload().path("title").asString()).isEqualTo("Favorites");
        assertThat(records.getLast().parentSourceId()).isEqualTo(records.getFirst().sourceKey().sourceId());
    }

    @Test
    @DisplayName("[normalize] Should Recognize Version Seven List - When CSV Starts With UTF-8 BOM")
    void shouldRecognizeVersionSevenListWhenCsvStartsWithUtf8Bom() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        listHandler(ImportSourceFormat.LETTERBOXD_CSV, "\uFEFF" + LIST)
                .normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.LIST_METADATA, ImportRecordKind.LIST_ITEM);
    }

    @Test
    @DisplayName("[normalize] Should Ignore List Metadata - When Letterboxd Watchlist Is Uploaded")
    void shouldIgnoreListMetadataWhenLetterboxdWatchlistIsUploaded() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        watchlistHandler(ImportSourceFormat.LETTERBOXD_CSV, WATCHLIST)
                .normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.WATCHLIST_ITEM);
    }

    @Test
    @DisplayName("[normalize] Should Emit Watchlist Members Only - When Letterboxd List File Uses Watchlist Mode")
    void shouldEmitWatchlistMembersOnlyWhenLetterboxdListFileUsesWatchlistMode() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        watchlistHandler(ImportSourceFormat.LETTERBOXD_CSV, LIST)
                .normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.WATCHLIST_ITEM);
        assertThat(records.getFirst().parentSourceId()).isNull();
    }

    @Test
    @DisplayName("[normalize] Should Reject Metadata Only File - When Trakt List Definitions Have No Items")
    void shouldRejectMetadataOnlyFileWhenTraktListDefinitionsHaveNoItems() throws Exception {
        String metadata = "[{\"name\":\"Favorites\",\"ids\":{\"trakt\":42}}]";
        List<NormalizedImportRecord> records = new ArrayList<>();
        listHandler(ImportSourceFormat.TRAKT_JSON, metadata).normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind).containsExactly(ImportRecordKind.LIST_METADATA);
        assertThat(records.getFirst().payload().path("issueCode").asString())
                .isEqualTo("LIST_ITEM_EXPORT_REQUIRED");
    }

    @Test
    @DisplayName("[normalize] Should Request List Name - When Trakt Item File Has No Name Metadata")
    void shouldRequestListNameWhenTraktItemFileHasNoNameMetadata() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        listHandler(ImportSourceFormat.TRAKT_JSON, TRAKT).normalize(CONTEXT, SELECTION, records::add);
        assertThat(records.getLast().payload().path("issueCode").asString()).isEqualTo("NEEDS_LIST_NAME");
    }

    @Test
    @DisplayName("[normalize] Should Request List Name With Members - When Letterboxd Watchlist Is Used As List")
    void shouldRequestListNameWithMembersWhenLetterboxdWatchlistIsUsedAsList() throws Exception {
        List<NormalizedImportRecord> records = new ArrayList<>();
        listHandler(ImportSourceFormat.LETTERBOXD_CSV, WATCHLIST)
                .normalize(CONTEXT, SELECTION, records::add);
        assertThat(records).extracting(NormalizedImportRecord::kind)
                .containsExactly(ImportRecordKind.LIST_ITEM, ImportRecordKind.LIST_METADATA);
        assertThat(records.getLast().payload().path("issueCode").asString()).isEqualTo("NEEDS_LIST_NAME");
        assertThat(records.getFirst().parentSourceId()).isEqualTo(records.getLast().sourceKey().sourceId());
    }

    @Test
    @DisplayName("[require] Should Resolve Both Standalone Modes - When Native Formats Are Registered")
    void shouldResolveBothStandaloneModesWhenNativeFormatsAreRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("app.import.platform-archive-handlers-enabled", "false")));
            context.registerBean(ImportFileStore.class, () -> mock(ImportFileStore.class));
            context.registerBean(TraktItemParserImpl.class, () -> new TraktItemParserImpl(new ObjectMapper()));
            context.registerBean(LetterboxdItemCsvParserImpl.class,
                    () -> new LetterboxdItemCsvParserImpl(new CsvRecordReader(new ImportLimits())));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ImportModeConfiguration.class);
            context.refresh();
            ImportModeRegistry registry = context.getBean(ImportModeRegistry.class);
            assertThatCode(() -> registry.require(new ImportModeKey(ImportInputKind.SINGLE_LIST,
                    ImportSourceFormat.TRAKT_JSON))).doesNotThrowAnyException();
            assertThatCode(() -> registry.require(new ImportModeKey(ImportInputKind.SINGLE_WATCHLIST,
                    ImportSourceFormat.LETTERBOXD_CSV))).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("[resolve] Should Request List Name - When Staged Standalone List Has No Name")
    void shouldRequestListNameWhenStagedStandaloneListHasNoName() {
        var fixture = identityFixture("{}");
        ListIdentityResolution result = fixture.resolver().resolve(OWNER, JOB, ImportSourceFormat.TRAKT_JSON,
                new ObjectMapper().createObjectNode(), fixture.parentId());
        assertThat(result.issueCode()).isEqualTo("NEEDS_LIST_NAME");
        assertThat(result.key()).isNull();
    }

    @Test
    @DisplayName("[resolve] Should Accept Preview Name Override - When Imported List Metadata Has No Name")
    void shouldAcceptPreviewNameOverrideWhenImportedListMetadataHasNoName() {
        var fixture = identityFixture("{\"_watchwiseResolution\":1,\"source\":{},"
                + "\"resolution\":{\"fields\":{\"TITLE\":\"Favorites\"}}}");

        ListIdentityResolution result = fixture.resolver().resolve(OWNER, JOB, ImportSourceFormat.TRAKT_JSON,
                new ObjectMapper().createObjectNode(), fixture.parentId());

        assertThat(result.issueCode()).isNull();
        assertThat(result.key()).isNotNull();
    }

    @Test
    @DisplayName("[resolve] Should Use Owner Namespace - When Letterboxd Source URI Has No Account Binding")
    void shouldUseOwnerNamespaceWhenLetterboxdSourceUriHasNoAccountBinding() {
        var fixture = identityFixture("{\"title\":\"Favorites\",\"sourceUri\":\"https://letterboxd.com/alice/list/favorites/\"}",
                ImportSourceFormat.LETTERBOXD_CSV, ImportPlatform.LETTERBOXD);
        ListIdentityResolution result = fixture.resolver().resolve(OWNER, JOB,
                ImportSourceFormat.LETTERBOXD_CSV, new ObjectMapper().createObjectNode(), fixture.parentId());
        assertThat(result.issueCode()).isNull();
        assertThat(result.key().namespace()).isEqualTo("LETTERBOXD:OWNER:" + OWNER);
    }

    @Test
    @DisplayName("[resolve] Should Reject Foreign Destination - When Destination Belongs To Another User")
    void shouldRejectForeignDestinationWhenDestinationBelongsToAnotherUser() {
        var fixture = identityFixture("{\"title\":\"Favorites\"}");
        UUID targetId = UUID.randomUUID();
        UserList list = mock(UserList.class);
        User otherOwner = mock(User.class);
        when(otherOwner.getId()).thenReturn(UUID.randomUUID());
        when(list.getUser()).thenReturn(otherOwner);
        when(fixture.lists().findById(targetId)).thenReturn(Optional.of(list));
        var metadata = new ObjectMapper().createObjectNode().put("destinationListId", targetId.toString());
        assertThatThrownBy(() -> fixture.resolver().resolve(OWNER, JOB, ImportSourceFormat.TRAKT_JSON,
                metadata, fixture.parentId())).isInstanceOf(com.watchwise.watchwise_api.common.exception.NotFoundException.class);
    }

    @Test
    @DisplayName("[resolve] Should Share Account Namespace - When Source Account And List Id Are Bound")
    void shouldShareAccountNamespaceWhenSourceAccountAndListIdAreBound() {
        var fixture = identityFixture("{\"title\":\"Favorites\"}", ImportSourceFormat.TRAKT_JSON,
                ImportPlatform.TRAKT, "alice");
        var metadata = new ObjectMapper().createObjectNode().put("sourceListId", "42");
        ListIdentityResolution result = fixture.resolver().resolve(OWNER, JOB, ImportSourceFormat.TRAKT_JSON,
                metadata, fixture.parentId());
        assertThat(result.key()).isEqualTo(new ImportSourceKey("TRAKT:ACCOUNT:alice", "list_metadata", "42"));
    }

    @Test
    @DisplayName("[resolve] Should Bind Preview Source Account - When Account And List Id Are Supplied Together")
    void shouldBindPreviewSourceAccountWhenAccountAndListIdAreSuppliedTogether() {
        var fixture = identityFixture("{\"title\":\"Favorites\"}");
        var metadata = new ObjectMapper().createObjectNode().put("sourceAccount", "alice")
                .put("sourceListId", "42");
        ListIdentityResolution result = fixture.resolver().resolve(OWNER, JOB, ImportSourceFormat.TRAKT_JSON,
                metadata, fixture.parentId());
        assertThat(result.key()).isEqualTo(new ImportSourceKey("TRAKT:ACCOUNT:alice", "list_metadata", "42"));
    }

    @Test
    @DisplayName("[resolve] Should Request Source Account - When External List Id Has No Account")
    void shouldRequestSourceAccountWhenExternalListIdHasNoAccount() {
        var fixture = identityFixture("{\"title\":\"Favorites\"}");
        var metadata = new ObjectMapper().createObjectNode().put("sourceListId", "42");
        ListIdentityResolution result = fixture.resolver().resolve(OWNER, JOB, ImportSourceFormat.TRAKT_JSON,
                metadata, fixture.parentId());
        assertThat(result.issueCode()).isEqualTo("NEEDS_SOURCE_ACCOUNT");
    }

    @Test
    @DisplayName("[resolve] Should Reject Mismatched Format - When Requested Format Differs From Job")
    void shouldRejectMismatchedFormatWhenRequestedFormatDiffersFromJob() {
        var fixture = identityFixture("{\"title\":\"Favorites\"}");
        assertThatThrownBy(() -> fixture.resolver().resolve(OWNER, JOB,
                ImportSourceFormat.LETTERBOXD_CSV, new ObjectMapper().createObjectNode(), fixture.parentId()))
                .isInstanceOf(BadRequestException.class);
    }

    private IdentityFixture identityFixture(String payload) {
        return identityFixture(payload, ImportSourceFormat.TRAKT_JSON, ImportPlatform.TRAKT);
    }

    private IdentityFixture identityFixture(String payload, ImportSourceFormat format, ImportPlatform platform) {
        return identityFixture(payload, format, platform, null);
    }

    private IdentityFixture identityFixture(String payload, ImportSourceFormat format, ImportPlatform platform,
                                            String account) {
        ImportJobRepository jobs = mock(ImportJobRepository.class);
        ImportItemRepository items = mock(ImportItemRepository.class);
        UserListRepository lists = mock(UserListRepository.class);
        UUID parentId = UUID.randomUUID();
        when(jobs.findOwnedById(JOB, OWNER)).thenReturn(Optional.of(ImportJob.builder().userId(OWNER)
                .inputKind(ImportInputKind.SINGLE_LIST).sourceFormat(format).platform(platform)
                .sourceAccountId(account)
                .sourceNamespace(account == null ? platform.name() + ":OWNER:" + OWNER
                        : platform.name() + ":ACCOUNT:" + account).build()));
        when(items.findById(parentId)).thenReturn(Optional.of(ImportItem.builder().id(parentId).jobId(JOB)
                .normalizedKind(ImportRecordKind.LIST_METADATA).sourceId("parent")
                .payloadJson(payload).build()));
        return new IdentityFixture(new StandaloneListIdentityResolver(jobs, items, lists, new ObjectMapper()),
                lists, parentId);
    }

    private record IdentityFixture(StandaloneListIdentityResolver resolver, UserListRepository lists, UUID parentId) {}

    private SingleListImportHandler listHandler(ImportSourceFormat format, String data) throws Exception {
        return new SingleListImportHandler(format, store(data), new TraktItemParserImpl(new ObjectMapper()),
                new LetterboxdItemCsvParserImpl(new CsvRecordReader(new ImportLimits())), new ObjectMapper());
    }

    private SingleWatchlistImportHandler watchlistHandler(ImportSourceFormat format, String data) throws Exception {
        return new SingleWatchlistImportHandler(format, store(data), new TraktItemParserImpl(new ObjectMapper()),
                new LetterboxdItemCsvParserImpl(new CsvRecordReader(new ImportLimits())));
    }

    private ImportFileStore store(String data) throws Exception {
        ImportFileStore store = mock(ImportFileStore.class);
        when(store.open("synthetic")).thenAnswer(invocation ->
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
        return store;
    }
}
