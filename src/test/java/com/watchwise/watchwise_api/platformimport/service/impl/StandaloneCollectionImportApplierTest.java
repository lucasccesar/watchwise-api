package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportListWriter;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StandaloneCollectionImportApplierTest {
    @Mock private ImportJobRepository jobs;
    @Mock private ExternalImportLinkRepository links;
    @Mock private ImportSemanticFingerprint fingerprints;
    @Mock private ImportListWriter lists;
    @Mock private ImportLedger ledger;
    @Mock private UserListRepository userLists;
    @Mock private ObjectMapper mapper;
    @InjectMocks private StandaloneCollectionImportApplier applier;

    @Test
    void waitsForSemanticAliasOwnerBeforeCallingListWriter() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID otherJobId = UUID.randomUUID();
        UUID parentItemId = UUID.randomUUID();
        ImportExecutionContext context = new ImportExecutionContext(ownerId, jobId, 1, 1,
                "LETTERBOXD:ACCOUNT:account", UUID.randomUUID());
        ImportSourceKey externalIdentity = new ImportSourceKey("LETTERBOXD:ACCOUNT:account",
                "list_metadata", "external-list-91");
        PreparedListImport plan = new PreparedListImport(externalIdentity, externalIdentity,
                "List", null, parentItemId, 0, null, null);
        doReturn("semantic-fingerprint").when(fingerprints).ofList(any(), any());
        when(ledger.reserve(any(), any(), any(), anyString()))
                .thenReturn(new LinkReservation(UUID.randomUUID(), ImportLinkState.IN_PROGRESS, otherJobId));

        assertThatThrownBy(() -> applier.apply(context, plan))
                .isInstanceOf(ImportUnitTransaction.WaitForOtherJobException.class);

        verify(lists, never()).apply(any(), any());
    }
}
