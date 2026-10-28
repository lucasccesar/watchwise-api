package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.dto.PickPatchDTO;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.StalePickPreparationException;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PickPreparationRetryTest {
    private final UUID actorId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID pickId = UUID.randomUUID();
    private final PickRepository picks = mock(PickRepository.class);
    private final PicksTemplateRepository templates = mock(PicksTemplateRepository.class);
    private final PickResponseAssembler responses = mock(PickResponseAssembler.class);
    private final PickResponseDTO response = mock(PickResponseDTO.class);
    private PickServiceImpl service;

    @BeforeEach
    void setup() {
        service = new PickServiceImpl(picks, mock(PickSelectionRepository.class), templates,
                mock(PicksTemplateCategoryRepository.class), mock(UserRepository.class), mock(FollowerRepository.class),
                mock(PickTargetService.class), new PageRequestFactory(), responses, mock(PickPreviewAssembler.class),
                mock(UserBlockAccessService.class), new com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor());
        when(responses.prepare(pickId)).thenReturn(new PickResponseAssembler.PreparedResponse(Map.of(), Map.of()));
        when(picks.findOwnedTemplateId(pickId, actorId)).thenReturn(Optional.of(templateId));
        when(templates.findCreatorIdentityById(templateId))
                .thenReturn(Optional.of(mock(PicksTemplateRepository.CreatorIdentity.class)));
        when(picks.findByIdForUpdate(pickId)).thenReturn(Optional.of(Pick.builder().id(pickId)
                .user(User.builder().id(actorId).build()).picksTemplate(PicksTemplate.builder().id(templateId).build())
                .visibility(PickVisibility.PUBLIC).build()));
    }

    @Test
    void shouldPermitInitialAttemptAndThreePreparationRetries() {
        when(responses.assemblePrepared(any(Pick.class), eq(actorId), any()))
                .thenThrow(new StalePickPreparationException(), new StalePickPreparationException(), new StalePickPreparationException())
                .thenReturn(response);

        var result = new AtomicReference<PickResponseDTO>();
        assertThatCode(() -> result.set(service.updatePick(actorId, pickId, new PickPatchDTO(PickVisibility.PRIVATE))))
                .doesNotThrowAnyException();
        assertThat(result.get()).isSameAs(response);

        verify(responses, times(4)).assemblePrepared(any(Pick.class), eq(actorId), any());
    }

    @Test
    void shouldReturnConflictAfterAllFourPreparationAttemptsAreStale() {
        when(responses.assemblePrepared(any(Pick.class), eq(actorId), any())).thenThrow(new StalePickPreparationException());

        assertThatThrownBy(() -> service.updatePick(actorId, pickId, new PickPatchDTO(PickVisibility.PRIVATE)))
                .isInstanceOf(ConflictException.class);

        verify(responses, times(4)).assemblePrepared(any(Pick.class), eq(actorId), any());
    }

    @Test
    void shouldNotRetryTmdbFailure() {
        when(responses.assemblePrepared(any(Pick.class), eq(actorId), any())).thenThrow(new TmdbUnavailableException("unavailable"));

        assertThatThrownBy(() -> service.updatePick(actorId, pickId, new PickPatchDTO(PickVisibility.PRIVATE)))
                .isInstanceOf(TmdbUnavailableException.class);

        verify(responses).assemblePrepared(any(Pick.class), eq(actorId), any());
    }

    @Test
    void shouldNotRetryDatabaseFailure() {
        when(responses.assemblePrepared(any(Pick.class), eq(actorId), any())).thenThrow(new TransientDataAccessResourceException("unavailable"));

        assertThatThrownBy(() -> service.updatePick(actorId, pickId, new PickPatchDTO(PickVisibility.PRIVATE)))
                .isInstanceOf(TransientDataAccessResourceException.class);

        verify(responses).assemblePrepared(any(Pick.class), eq(actorId), any());
    }

    @Test
    void shouldNotRetryDomainFailure() {
        when(responses.assemblePrepared(any(Pick.class), eq(actorId), any())).thenThrow(new BadRequestException("invalid"));

        assertThatThrownBy(() -> service.updatePick(actorId, pickId, new PickPatchDTO(PickVisibility.PRIVATE)))
                .isInstanceOf(BadRequestException.class);

        verify(responses).assemblePrepared(any(Pick.class), eq(actorId), any());
    }
}
