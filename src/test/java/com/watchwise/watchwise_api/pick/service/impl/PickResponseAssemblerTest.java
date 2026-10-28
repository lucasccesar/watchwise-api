package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetServiceImpl;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PickResponseAssemblerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldKeepCheckedFactsWhenValidAndInvalidCategoriesShareTheSameTarget(boolean invalidLast) {
        UUID viewerId = UUID.randomUUID();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).build();
        PicksTemplateCategory valid = category(template, PickAllowedType.MOVIE);
        PicksTemplateCategory invalid = category(template, PickAllowedType.PERSON);
        Content content = Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("550").build();
        Pick pick = Pick.builder().id(UUID.randomUUID()).picksTemplate(template)
                .user(User.builder().id(viewerId).build()).visibility(PickVisibility.PUBLIC).build();
        PickSelection validSelection = selection(pick, valid, content);
        PickSelection invalidSelection = selection(pick, invalid, content);
        List<PickSelection> orderedSelections = invalidLast ? List.of(validSelection, invalidSelection)
                : List.of(invalidSelection, validSelection);
        PickSelectionRepository selections = mock(PickSelectionRepository.class);
        PicksTemplateCategoryRepository categories = mock(PicksTemplateCategoryRepository.class);
        TmdbClient tmdb = mock(TmdbClient.class);
        when(tmdb.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails("550", "Movie", null, null,
                        null, null, "2025-06-01", null, null, null, null, null, null, null, null, null, null)));
        when(selections.findByPickIdIn(List.of(pick.getId()))).thenReturn(orderedSelections);
        when(categories.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(valid, invalid));
        NewTransactionExecutor transactions = new NewTransactionExecutor();
        var targets = new PickTargetServiceImpl(mock(ContentService.class), mock(ContentRepository.class),
                mock(PicksTemplateOptionRepository.class), tmdb, transactions);
        var assembler = new PickResponseAssembler(selections, categories, mock(PicksTemplatePreviewAssembler.class),
                mock(CommentRepository.class), mock(LikeService.class), mock(PickMapper.class), targets, transactions);
        PickResponseAssembler.PreparedResponse prepared = assembler.prepare(pick.getId());
        var response = new AtomicReference<PickResponseDTO>();

        assertThatCode(() -> response.set(assembler.assemblePrepared(pick, viewerId, prepared))).doesNotThrowAnyException();

        Map<UUID, Boolean> validity = response.get().selections().stream().collect(java.util.stream.Collectors.toMap(
                com.watchwise.watchwise_api.pick.dto.PickSelectionDTO::categoryId,
                com.watchwise.watchwise_api.pick.dto.PickSelectionDTO::isValid));
        assertThat(validity).containsEntry(valid.getId(), true).containsEntry(invalid.getId(), false);
        assertThat(response.get().progress()).isEqualTo(PickProgress.PARTIAL);
        verify(tmdb).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    private PicksTemplateCategory category(PicksTemplate template, PickAllowedType type) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template)
                .allowedType(type).optionMode(PickCategoryOptionMode.OPEN).build();
    }

    private PickSelection selection(Pick pick, PicksTemplateCategory category, Content content) {
        return PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).content(content).build();
    }
}
