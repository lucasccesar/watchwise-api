package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.pickstemplate.dto.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparedTemplateCreationIntegrationTest extends PicksDomainIntegrationSupport {
    @Autowired PreparedTemplateCreationService preparedService;

    @Test
    @DisplayName("[createPrepared] Should Persist Person Targets Without Inventing Context - When Created Normally And Prepared")
    void normalAndPreparedCreationPersistEquivalentPersonTargetsWithoutContext() {
        when(tmdb.getPersonDetails("6384")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TmdbLookupResult.Found<>(new com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails("6384"));
        });
        var request = creation(PickAllowedType.PERSON, List.of(new PickTargetDTO(null, "6384", null)), null, null);
        var normal = templateService.createTemplate(owner.getId(), request);
        var prepared = preparedService.prepare(owner.getId(), request);
        var imported = preparedService.createPrepared(owner.getId(), prepared);

        assertThat(normal.categories().getFirst().options().getFirst().target().personTmdbId()).isEqualTo("6384");
        assertThat(imported.categories().getFirst().options().getFirst().target().personTmdbId()).isEqualTo("6384");
        assertThat(imported.categories().getFirst().options().getFirst().target().contextContent()).isNull();
        assertThat(options.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("[prepare] Should Reject Null Category - Before TMDB Or Template Writes")
    void rejectsNullCategoryAsBadRequestBeforeTmdbOrTemplateWrites() {
        List<PicksTemplateCategoryCreationDTO> categories = new java.util.ArrayList<>();
        categories.add(null);
        var request = new PicksTemplateCreationDTO("Broken", null, null, null, null, null, categories);

        assertThatThrownBy(() -> preparedService.prepare(owner.getId(), request))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tmdb);
        assertThat(templates.count()).isZero();
    }

    @Test
    @DisplayName("[createPrepared] Should Use Current Persisted Role - When Role Changes After Preparation")
    void writerUsesCurrentPersistedRole() {
        var request = creation(PickAllowedType.PERSON, List.of(new PickTargetDTO(null, "6384", null)), null, null);
        var prepared = preparedService.prepare(owner.getId(), request);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", owner.getId());
        assertThat(preparedService.createPrepared(owner.getId(), prepared).origin()).isEqualTo(PickOrigin.OFFICIAL);
    }

    @Test
    @DisplayName("[prepare] Should Reject Ineligible Fixed Movie - Before Template Writes")
    void fixedMovieOutsideEligibilityIsRejectedBeforeAnyTemplateWrite() {
        var request = creation(PickAllowedType.MOVIE,
                List.of(new PickTargetDTO(new PickContentTargetDTO(PickAllowedType.MOVIE, "550", null, null, null), null, null)),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        assertThatThrownBy(() -> preparedService.prepare(owner.getId(), request)).isInstanceOf(BadRequestException.class);
        assertThat(templates.count()).isZero();
    }

    @Test
    @DisplayName("[prepare] Should Reject Duplicate Target Only Within Category - When Preparing Fixed Options")
    void repeatedTargetWithinCategoryRequiresExplicitResolutionButAcrossCategoriesIsAllowed() {
        var target = new PickTargetDTO(null, "6384", null);
        assertThatThrownBy(() -> preparedService.prepare(owner.getId(),
                creation(PickAllowedType.PERSON, List.of(target, target), null, null)))
                .isInstanceOf(ConflictException.class);
        var category = category(PickAllowedType.PERSON, List.of(target));
        var request = new PicksTemplateCreationDTO("People", null, null, null, null, null,
                List.of(category, new PicksTemplateCategoryCreationDTO("Second", null, PickCategoryGroup.PRIMARY, 2,
                        PickAllowedType.PERSON, PickCategoryOptionMode.FIXED,
                        List.of(new PicksTemplateOptionCreationDTO(target)))));
        assertThat(preparedService.createPrepared(owner.getId(), preparedService.prepare(owner.getId(), request))
                .categories()).hasSize(2);
    }

    @Test
    @DisplayName("[createPrepared] Should Reject Expired Validation - When Preparation Is Older Than Twenty Four Hours")
    void rejectsExpiredPreparedValidation() {
        var request = creation(PickAllowedType.PERSON,
                List.of(new PickTargetDTO(null, "6384", null)), null, null);
        var prepared = preparedService.prepare(owner.getId(), request);
        var stale = new com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation(
                prepared.actorId(), prepared.sourceDto(), prepared.options(), prepared.validationDigest(),
                prepared.validatedAt().minusHours(24));

        assertThatThrownBy(() -> preparedService.createPrepared(owner.getId(), stale))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expired");
        assertThat(templates.count()).isZero();
    }

    @Test
    @DisplayName("[createPrepared] Should Reject Future Validation - When Prepared Timestamp Is In The Future")
    void rejectsFuturePreparedValidation() {
        var request = creation(PickAllowedType.PERSON,
                List.of(new PickTargetDTO(null, "6384", null)), null, null);
        var prepared = preparedService.prepare(owner.getId(), request);
        var future = new com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation(
                prepared.actorId(), prepared.sourceDto(), prepared.options(), prepared.validationDigest(),
                prepared.validatedAt().plusMinutes(1));

        assertThatThrownBy(() -> preparedService.createPrepared(owner.getId(), future))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expired");
        assertThat(templates.count()).isZero();
    }

    private PicksTemplateCreationDTO creation(PickAllowedType type, List<PickTargetDTO> targets,
                                              LocalDate start, LocalDate end) {
        return new PicksTemplateCreationDTO("People", null, null, null, start, end, List.of(category(type, targets)));
    }

    private PicksTemplateCategoryCreationDTO category(PickAllowedType type, List<PickTargetDTO> targets) {
        return new PicksTemplateCategoryCreationDTO("First", null, PickCategoryGroup.PRIMARY, 1, type,
                PickCategoryOptionMode.FIXED, targets.stream().map(PicksTemplateOptionCreationDTO::new).toList());
    }
}
