package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreationService;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.parser.PicksTemplateJsonParser;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportSemanticFingerprint;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportUnitTransaction;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportedTemplateAdapter;
import com.watchwise.watchwise_api.platformimport.service.impl.PicksTemplateImportHandler;
import com.watchwise.watchwise_api.platformimport.service.impl.TemplateNomineeResolver;
import com.watchwise.watchwise_api.platformimport.storage.ImportArtifactReader;
import com.watchwise.watchwise_api.platformimport.storage.ImportStorageUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PicksTemplateImportStorageFailureTest {
    @Test
    void preservesStorageFailureWhenLoadingPersistedTemplate() {
        ImportArtifactReader artifacts = mock(ImportArtifactReader.class);
        ImportReadContext context = new ImportReadContext(UUID.randomUUID(), UUID.randomUUID(),
                "00000000-0000-0000-0000-000000000008", "WATCHWISE:OWNER:test", ZoneOffset.UTC,
                3, "0".repeat(64));
        ImportStorageUnavailableException failure =
                new ImportStorageUnavailableException(new IOException("storage unavailable"));
        when(artifacts.open(context.artifact())).thenThrow(failure);
        PicksTemplateImportHandler handler = new PicksTemplateImportHandler(artifacts,
                mock(PicksTemplateJsonParser.class), mock(TemplateNomineeResolver.class),
                mock(ImportedTemplateAdapter.class), mock(PreparedTemplateCreationService.class),
                mock(ImportSemanticFingerprint.class), mock(ImportItemRepository.class),
                mock(ImportUnitTransaction.class), mock(ImportLedger.class), new ObjectMapper(), Clock.systemUTC());

        assertThatThrownBy(() -> handler.normalize(context,
                new ImportSelection(false, false, java.util.Set.of(), null), record -> {}))
                .isSameAs(failure);
    }
}
