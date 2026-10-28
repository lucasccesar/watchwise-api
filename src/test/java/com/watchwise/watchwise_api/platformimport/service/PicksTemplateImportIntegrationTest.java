package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePatchDTO;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.platformimport.dto.ImportCommitDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportResolutionDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.parser.PicksTemplateJsonParser;
import com.watchwise.watchwise_api.platformimport.service.ImportJobService;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportSemanticFingerprint;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.platformimport.storage.PrivateImportFileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers

class PicksTemplateImportIntegrationTest extends PicksDomainIntegrationSupport {
    @Autowired private PicksTemplateJsonParser parser;
    @Autowired private ImportSemanticFingerprint fingerprints;
    @Autowired private ImportJobService importJobs;
    @Autowired private ImportJobRepository importJobRows;
    @Autowired private ImportItemRepository stagedItems;
    @Autowired private ImportWorker worker;
    @Autowired private PrivateImportFileStore importFiles;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private PicksTemplateOptionRepository optionRepositorySpy;
    private final List<String> storageKeys = new ArrayList<>();

    @DynamicPropertySource
    static void importProperties(DynamicPropertyRegistry registry) {
        registry.add("app.import.storage-directory", () -> Path.of(System.getProperty("java.io.tmpdir"),
                "watchwise-picks-template-import-tests").toAbsolutePath().toString());
    }

    @AfterEach
    void cleanImportedFiles() throws IOException {
        for (String storageKey : storageKeys) importFiles.delete(storageKey);
        storageKeys.clear();
    }

    @Test
    @DisplayName("[ofTemplate] Should Ignore Formatting And Nominee Order - When Aggregate Content Is Equivalent")
    void shouldIgnoreFormattingAndNomineeOrderWhenAggregateContentIsEquivalent() {
        var first = parse("""
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42"},{"personTmdbId":"7"}]}
                ]}
                """);
        var reformatted = parse("""
                {
                  "schemaVersion": 1,
                  "title": "People Awards",
                  "categories": [{"name":"Best lead", "type":"PERSON", "nominees":[
                    {"personTmdbId":"7"}, {"personTmdbId":"42"}]}]
                }
                """);
        List<List<ImportTarget>> firstTargets = List.of(List.of(
                new ImportTarget.PersonTarget("42", null), new ImportTarget.PersonTarget("7", null)));
        List<List<ImportTarget>> reorderedTargets = List.of(List.of(
                new ImportTarget.PersonTarget("7", null), new ImportTarget.PersonTarget("42", null)));

        assertThat(fingerprints.ofTemplate(first, firstTargets))
                .isEqualTo(fingerprints.ofTemplate(reformatted, reorderedTargets));
    }

    @Test
    @DisplayName("[ofTemplate] Should Preserve Category Order - When Category Sequence Changes")
    void shouldPreserveCategoryOrderWhenCategorySequenceChanges() {
        var first = parse("""
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]},
                  {"name":"Best film","type":"movie","nominees":[{"tmdbId":"550"}]}
                ]}
                """);
        var reordered = parse("""
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Best film","type":"movie","nominees":[{"tmdbId":"550"}]},
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]}
                ]}
                """);
        List<List<ImportTarget>> firstTargets = List.of(
                List.of(new ImportTarget.PersonTarget("42", null)),
                List.of(new ImportTarget.ContentTarget(MediaReference.movie("550"))));
        List<List<ImportTarget>> reorderedTargets = List.of(
                List.of(new ImportTarget.ContentTarget(MediaReference.movie("550"))),
                List.of(new ImportTarget.PersonTarget("42", null)));

        assertThat(fingerprints.ofTemplate(first, firstTargets))
                .isNotEqualTo(fingerprints.ofTemplate(reordered, reorderedTargets));
    }

    @Test
    @DisplayName("[runAvailable] Should Create A Template And Reuse Its Semantic Identity - When JSON Formatting And Nominee Order Change")
    void shouldCreateOnceWhenReimportingReformattedTemplateWithReorderedNominees() {
        String first = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42"},{"personTmdbId":"7"}]}
                ]}
                """;
        String reordered = """
                {
                  "schemaVersion": 1,
                  "title": "People Awards",
                  "categories": [{"name":"Best lead", "type":"PERSON", "nominees":[
                    {"personTmdbId":"7"}, {"personTmdbId":"42"}]}]
                }
                """;
        when(tmdb.getPersonDetails("42")).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.Found<>(
                    new com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails("42"));
        });

        completeImport(first);
        assertThat(templates.count()).isEqualTo(1);
        verify(tmdb, times(2)).getPersonDetails(any(String.class));
        PicksTemplate imported = templates.findAll().getFirst();
        templateService.updateTemplate(owner.getId(), imported.getId(),
                new PicksTemplatePatchDTO("Local title", null, null, null, null, null, false));

        completeImport(reordered);

        assertThat(templates.count()).isEqualTo(1);
        assertThat(options.count()).isEqualTo(2);
        assertThat(templates.findAll().getFirst().getName()).isEqualTo("Local title");
        verify(tmdb, times(2)).getPersonDetails(any(String.class));
    }

    @Test
    @DisplayName("[runAvailable] Should Create A New Template - When Category Order Changes")
    void shouldCreateNewTemplateWhenCategoryOrderChanges() {
        String first = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Lead","type":"person","nominees":[{"personTmdbId":"42"}]},
                  {"name":"Supporting","type":"person","nominees":[{"personTmdbId":"7"}]}
                ]}
                """;
        String reordered = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Supporting","type":"person","nominees":[{"personTmdbId":"7"}]},
                  {"name":"Lead","type":"person","nominees":[{"personTmdbId":"42"}]}
                ]}
                """;

        completeImport(first);
        completeImport(reordered);

        assertThat(templates.count()).isEqualTo(2);
        assertThat(options.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("[validateResolved] Should Validate Two Hundred Nominees Once Per Distinct Target - When Targets Repeat Across Categories")
    void shouldValidateTwoHundredNomineesOncePerDistinctTarget() {
        AtomicInteger tmdbValidations = new AtomicInteger();
        when(tmdb.getPersonDetails(any(String.class))).thenAnswer(call -> {
            String id = call.getArgument(0);
            tmdbValidations.incrementAndGet();
            return new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.Found<>(
                    new com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails(id));
        });
        String source = twoHundredNomineeTemplate();
        assertThat(source.getBytes(StandardCharsets.UTF_8).length).isLessThan(5 * 1024 * 1024);
        ImportJobDTO job = upload(source);

        runNormalizationAndResolution(job);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.READY);
        assertThat(stagedItems.countByJobIdAndPlanGeneration(job.id(), 0)).isEqualTo(251);
        assertThat(tmdbValidations).hasValue(50);
    }

    @Test
    @DisplayName("[resolve] Should Report Duplicate Nominees For Explicit Removal - When Targets Repeat In One Fixed Category")
    void shouldRequireRemovingRepeatedNomineeBeforeCommit() {
        String source = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42"},{"personTmdbId":"42"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var duplicate = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_OPTION)
                .filter(item -> item.issue() != null && item.issue().startsWith("DUPLICATE_TEMPLATE_NOMINEE"))
                .findFirst().orElseThrow();

        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(
                preview.previewVersion(), List.of(new ImportResolutionOperation.RemoveNominee(duplicate.itemId()))));
        worker.runAvailable(1);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        assertThat(ready.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        worker.runAvailable(1);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(templates.count()).isEqualTo(1);
        assertThat(options.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[runAvailable] Should Keep Contextual Person Targets Distinct - When Their Explicit Contexts Differ")
    void shouldAllowSamePersonWithDifferentExplicitContexts() {
        String source = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42","contextContent":{"type":"movie","tmdbId":"550"}},
                    {"personTmdbId":"42","contextContent":{"type":"movie","tmdbId":"551"}}]}
                ]}
                """;

        completeImport(source);

        assertThat(templates.count()).isEqualTo(1);
        assertThat(options.count()).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT context.tmdb_id FROM picks_template_options option
                JOIN contents context ON context.id=option.context_content_id
                """, String.class)).containsExactlyInAnyOrder("550", "551");
    }

    @Test
    @DisplayName("[validateResolved] Should Keep An Invalid Strong ID In The Preview - When TMDB Rejects The Nominee")
    void shouldAllowReplacingStrongNomineeRejectedByTmdb() {
        when(tmdb.getPersonDetails(any(String.class))).thenAnswer(call -> {
            String id = call.getArgument(0);
            if (id.equals("42")) return new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.NotFound<>();
            return new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.Found<>(
                    new com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails(id));
        });
        String source = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42","name":"Carrie Example"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var invalid = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_OPTION)
                .filter(item -> item.issue() != null && item.issue().startsWith("FIXED_OPTION_NOT_FOUND"))
                .findFirst().orElseThrow();

        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(preview.previewVersion(), List.of(
                new ImportResolutionOperation.ResolveTarget(invalid.itemId(),
                        new ImportTarget.PersonTarget("7", null)))));
        worker.runAvailable(1);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        assertThat(ready.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        worker.runAvailable(1);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(templates.count()).isEqualTo(1);
        assertThat(options.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[resolve] Should Allow Removing An Invalid Nominee - When Another Fixed Nominee Remains")
    void shouldAllowRemovingInvalidStrongNominee() {
        when(tmdb.getPersonDetails(any(String.class))).thenAnswer(call -> {
            String id = call.getArgument(0);
            if (id.equals("42")) return new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.NotFound<>();
            return new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.Found<>(
                    new com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails(id));
        });
        String source = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42"},{"personTmdbId":"7"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var invalid = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_OPTION)
                .filter(item -> item.issue() != null && item.issue().startsWith("FIXED_OPTION_NOT_FOUND"))
                .findFirst().orElseThrow();

        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(preview.previewVersion(), List.of(
                new ImportResolutionOperation.RemoveNominee(invalid.itemId()))));
        worker.runAvailable(1);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        assertThat(ready.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        worker.runAvailable(1);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(options.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[resolve] Should Keep At Least One Template Category - When Removing Categories")
    void shouldRejectRemovingEveryTemplateCategory() {
        String source = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Lead","type":"person","nominees":[{"personTmdbId":"42"}]},
                  {"name":"Supporting","type":"person","nominees":[{"personTmdbId":"7"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var category = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_CATEGORY)
                .findFirst().orElseThrow();
        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(
                preview.previewVersion(), List.of(new ImportResolutionOperation.RemoveCategory(category.itemId()))));
        jdbc.update("UPDATE import_jobs SET next_run_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), job.id());
        worker.runAvailable(1);
        ImportPreviewDTO afterFirstRemoval = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var remaining = afterFirstRemoval.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_CATEGORY)
                .filter(item -> item.decision() != com.watchwise.watchwise_api.platformimport.model.ImportDecision.REMOVE_CATEGORY)
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(
                afterFirstRemoval.previewVersion(), List.of(new ImportResolutionOperation.RemoveCategory(remaining.itemId())))))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class)
                .hasMessageContaining("at least one category");
        assertThat(importJobs.get(owner.getId(), job.id()).previewVersion())
                .isEqualTo(afterFirstRemoval.previewVersion());
    }

    @Test
    @DisplayName("[resolve] Should Keep An Invalid Eligibility Pair In Preview - Until Both Dates Are Ordered")
    void shouldKeepInvertedTemplateEligibilityPeriodInPreviewUntilCorrected() {
        String source = """
                {"schemaVersion":1,"title":"Awards","eligibility":{"start":"2000-01-01","end":"2030-12-31"},
                 "categories":[{"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]}]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var root = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE)
                .findFirst().orElseThrow();

        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(
                preview.previewVersion(), List.of(new ImportResolutionOperation.EditField(root.itemId(),
                        ImportResolutionOperation.EditableField.ELIGIBILITY_START, "2040-01-01"))));
        worker.runAvailable(1);
        ImportPreviewDTO invalidPreview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var pendingRoot = invalidPreview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE)
                .findFirst().orElseThrow();
        assertThat(pendingRoot.issue()).startsWith("TEMPLATE_ELIGIBILITY_PERIOD_INVALID");
        assertThat(pendingRoot.templateEligibilityStart()).isEqualTo(LocalDate.of(2040, 1, 1));
        assertThat(pendingRoot.templateEligibilityEnd()).isEqualTo(LocalDate.of(2030, 12, 31));

        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(
                invalidPreview.previewVersion(), List.of(new ImportResolutionOperation.EditField(root.itemId(),
                        ImportResolutionOperation.EditableField.ELIGIBILITY_END, "2050-12-31"))));
        worker.runAvailable(1);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        assertThat(ready.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        worker.runAvailable(1);

        PicksTemplate saved = templates.findAll().getFirst();
        assertThat(saved.getEligibilityStartDate()).isEqualTo(LocalDate.of(2040, 1, 1));
        assertThat(saved.getEligibilityEndDate()).isEqualTo(LocalDate.of(2050, 12, 31));
    }

    @Test
    @DisplayName("[resolve] Should Convert A Fixed Category To Open - When User Requests An Open Category")
    void shouldOpenFixedCategoryWithoutPersistingItsNominees() {
        String source = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var category = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_CATEGORY)
                .findFirst().orElseThrow();
        ImportJobDTO updated = importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(
                preview.previewVersion(), List.of(new ImportResolutionOperation.SetCategoryOpen(
                category.itemId(), true))));
        assertThat(updated.previewVersion()).isGreaterThan(preview.previewVersion());
        worker.runAvailable(1);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        assertThat(ready.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        worker.runAvailable(1);

        assertThat(templates.count()).isEqualTo(1);
        assertThat(categories.findAll()).singleElement()
                .extracting(com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory::getOptionMode)
                .isEqualTo(com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode.OPEN);
        assertThat(options.count()).isZero();
    }

    @Test
    @DisplayName("[detachOrDeleteTemplate] Should Tombstone The Original Import Link - When An Admin Deletes The Template")
    void shouldPreventReimportAfterAdminDeletesImportedTemplate() {
        String source = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]}
                ]}
                """;
        completeImport(source);
        PicksTemplate imported = templates.findAll().getFirst();
        templateService.detachOrDeleteTemplate(admin.getId(), imported.getId());

        assertThat(templates.count()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM external_import_links
                WHERE target_type='PICKS_TEMPLATE' AND target_id=? AND state='TOMBSTONE'
                """, Integer.class, imported.getId())).isEqualTo(1);

        completeImport(source);
        assertThat(templates.count()).isZero();
    }

    @Test
    @DisplayName("[runAvailable] Should Roll Back The Aggregate And Source Link - When The Last Option Fails")
    void shouldRollbackTemplateWhenAnOptionWriteFails() {
        String source = """
                {"schemaVersion":1,"title":"People Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42"},{"personTmdbId":"7"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));

        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("injected option failure");
            return invocation.callRealMethod();
        }).when(optionRepositorySpy).saveAndFlush(any(PicksTemplateOption.class));
        worker.runAvailable(1);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.FAILED);
        assertThat(templates.count()).isZero();
        assertThat(options.count()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM external_import_links
                WHERE user_id=? AND source_namespace='WATCHWISE_TEMPLATE_V1'
                  AND object_kind='PICKS_TEMPLATE'
                """, Integer.class, owner.getId())).isZero();
    }

    @Test
    @DisplayName("[commit] Should Reject A Preview After Its Job Expires - Without Starting Apply")
    void shouldRejectCommitAfterImportJobExpires() {
        String source = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        jdbc.update("UPDATE import_jobs SET expires_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), job.id());

        assertThatThrownBy(() -> importJobs.commit(owner.getId(), job.id(),
                new ImportCommitDTO(ready.previewVersion(), ready.previewDigest())))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.ConflictException.class)
                .hasMessageContaining("expired");
        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.READY);
    }

    @Test
    @DisplayName("[runAvailable] Should Finish A Committed Template After Preview Expiry - When Apply Has Started")
    void shouldFinishCommittedTemplateAfterPreviewExpiry() {
        String source = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[{"personTmdbId":"42"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        jdbc.update("UPDATE import_jobs SET expires_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), job.id());

        worker.runAvailable(1);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(templates.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[prepareForApply] Should Return A Stale Target To Preview - When TMDB Rejects Revalidation Before Apply")
    void shouldReturnStaleTargetToPreviewAfterCommit() throws Exception {
        String source = """
                {"schemaVersion":1,"title":"Awards","categories":[
                  {"name":"Best lead","type":"person","nominees":[
                    {"personTmdbId":"42"},{"personTmdbId":"7"}]}
                ]}
                """;
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));

        var root = stagedItems.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                job.id(), 0, com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE).getFirst();
        ObjectNode rootPayload = (ObjectNode) mapper.readTree(root.getPayloadJson());
        ObjectNode cached = (ObjectNode) rootPayload.path("resolution").path("preparedTemplate")
                .path("preparedCreation");
        cached.put("validatedAt", LocalDateTime.now().minusHours(25).toString());
        root.setPayloadJson(mapper.writeValueAsString(rootPayload));
        stagedItems.saveAndFlush(root);
        when(tmdb.getPersonDetails("42")).thenReturn(new com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult.NotFound<>());

        worker.runAvailable(1);

        ImportJobDTO resolving = importJobs.get(owner.getId(), job.id());
        assertThat(resolving.state()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(resolving.previewVersion()).isGreaterThan(ready.previewVersion());
        ImportPreviewDTO preview = importJobs.preview(owner.getId(), job.id(), 0, 50);
        var rejected = preview.items().stream()
                .filter(item -> item.kind() == com.watchwise.watchwise_api.platformimport.model.ImportRecordKind.TEMPLATE_OPTION)
                .filter(item -> item.issue() != null && item.issue().startsWith("FIXED_OPTION_NOT_FOUND"))
                .findFirst().orElseThrow();

        importJobs.resolve(owner.getId(), job.id(), new ImportResolutionDTO(preview.previewVersion(), List.of(
                new ImportResolutionOperation.RemoveNominee(rejected.itemId()))));
        worker.runAvailable(1);
        ImportJobDTO readyAgain = importJobs.get(owner.getId(), job.id());
        assertThat(readyAgain.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(
                readyAgain.previewVersion(), readyAgain.previewDigest()));
        worker.runAvailable(1);

        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(options.count()).isEqualTo(1);
    }

    private ImportJobDTO upload(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        ImportJobDTO job = importJobs.create(owner.getId(), new MockMultipartFile(
                        "file", "template.json", "application/json", bytes), ImportInputKind.PICKS_TEMPLATE,
                ImportSourceFormat.WATCHWISE_TEMPLATE_JSON, null, null, null);
        ImportJob row = importJobRows.findById(job.id()).orElseThrow();
        storageKeys.add(row.getStorageKey());
        assertThat(row.getWorkPhase()).isEqualTo("NORMALIZE");
        return job;
    }

    private ImportJobDTO completeImport(String source) {
        ImportJobDTO job = upload(source);
        runNormalizationAndResolution(job);
        ImportJobDTO ready = importJobs.get(owner.getId(), job.id());
        assertThat(ready.state()).isEqualTo(ImportJobState.READY);
        importJobs.commit(owner.getId(), job.id(), new ImportCommitDTO(ready.previewVersion(), ready.previewDigest()));
        worker.runAvailable(1);
        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.COMPLETED);
        return importJobs.get(owner.getId(), job.id());
    }

    private void runNormalizationAndResolution(ImportJobDTO job) {
        worker.runAvailable(1);
        assertThat(importJobs.get(owner.getId(), job.id()).state()).isEqualTo(ImportJobState.RESOLVING);
        jdbc.update("UPDATE import_jobs SET next_run_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), job.id());
        worker.runAvailable(1);
    }

    private String twoHundredNomineeTemplate() {
        StringBuilder json = new StringBuilder(20_000)
                .append("{\"schemaVersion\":1,\"title\":\"Large Awards\",\"categories\":[");
        for (int category = 0; category < 50; category++) {
            if (category > 0) json.append(',');
            json.append("{\"name\":\"Category ").append(category)
                    .append("\",\"type\":\"person\",\"nominees\":[");
            for (int nominee = 1; nominee <= 4; nominee++) {
                if (nominee > 1) json.append(',');
                int personId = ((category * 4 + nominee - 1) % 50) + 1;
                json.append("{\"personTmdbId\":\"").append(personId).append("\"}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private com.watchwise.watchwise_api.platformimport.model.TemplateImportDocument parse(String value) {
        return parser.parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
}
