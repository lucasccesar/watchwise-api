package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickListRow;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.MyPicksListService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.PickTargetServiceImpl;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MyPicksListServiceTest {
    @Mock PickRepository picks;
    @Mock PickSelectionRepository selections;
    @Mock PicksTemplateCategoryRepository categories;
    @Mock PicksTemplateOptionRepository options;
    @Mock CommentRepository comments;
    @Mock PickTargetService targets;

    @Test
    void preservesPageOrderTotalsAndValidChipsWithOneBatchPerRepository() {
        UUID viewer = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        var createdAt = LocalDateTime.of(2026, 11, 1, 10, 0);
        var rows = IntStream.range(0, 10).mapToObj(i -> new PickListRow(UUID.randomUUID(), templateId,
                "Template", "cover", "creator", "Creator", "avatar", PickVisibility.PRIVATE, createdAt, 2)).toList();
        var ids = rows.stream().map(PickListRow::id).toList();
        var orderedCategories = IntStream.range(0, 8).mapToObj(i -> PicksTemplateCategory.builder()
                .id(UUID.randomUUID()).name("Category " + i).optionMode(PickCategoryOptionMode.FIXED)
                .picksTemplate(PicksTemplate.builder().id(templateId).build())
                .build()).toList();
        var stored = orderedCategories.stream().map(category -> PickSelection.builder()
                .pick(Pick.builder().id(ids.getFirst()).build()).category(category).build()).toList();
        when(picks.findMyPickList(viewer, PageRequest.of(1, 10)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(1, 10), 31));
        when(categories.findByPicksTemplateIdInOrderByDisplayOrder(List.of(templateId))).thenReturn(orderedCategories);
        when(selections.findByPickIdIn(ids)).thenReturn(stored.reversed());
        when(options.findByCategoryIdIn(orderedCategories.stream().map(PicksTemplateCategory::getId).toList())).thenReturn(List.of());
        when(comments.countVisiblePickComments(viewer, ids)).thenReturn(List.of());
        for (int i = 0; i < 6; i++) {
            when(targets.isStructurallyValid(orderedCategories.get(i), stored.get(i), List.of())).thenReturn(true);
        }

        var result = service().list(viewer, 2, 100);

        assertThat(result.getContent()).extracting(card -> card.id()).containsExactlyElementsOf(ids);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(31);
        assertThat(result.getTotalPages()).isEqualTo(4);
        assertThat(result.hasNext()).isTrue();
        var card = result.getContent().getFirst();
        assertThat(card.visibility()).isEqualTo(PickVisibility.PRIVATE);
        assertThat(card.picksTemplate().creator().username()).isEqualTo("creator");
        assertThat(card.picksTemplate().coverImage()).isEqualTo("cover");
        assertThat(card.answeredCategoriesCount()).isEqualTo(6);
        assertThat(card.totalCategoriesCount()).isEqualTo(8);
        assertThat(card.answeredCategories()).extracting(c -> c.name())
                .containsExactly("Category 0", "Category 1", "Category 2", "Category 3", "Category 4");
        assertThat(result.getContent().getLast().answeredCategories()).isEmpty();

        verify(picks).findMyPickList(viewer, PageRequest.of(1, 10));
        verify(categories).findByPicksTemplateIdInOrderByDisplayOrder(List.of(templateId));
        verify(selections).findByPickIdIn(ids);
        verify(options).findByCategoryIdIn(orderedCategories.stream().map(PicksTemplateCategory::getId).toList());
        verify(comments).countVisiblePickComments(viewer, ids);
        verify(targets, times(8)).isStructurallyValid(any(), any(), any());
        verifyNoMoreInteractions(picks, categories, selections, options, comments, targets);
    }

    @Test
    void stopsAfterThePageLookupWhenEmpty() {
        UUID viewer = UUID.randomUUID();
        var request = PageRequest.of(0, 10);
        when(picks.findMyPickList(viewer, request)).thenReturn(Page.empty(request));

        assertThat(service().list(viewer, null, null).getContent()).isEmpty();
        verify(picks).findMyPickList(viewer, request);
        verifyNoMoreInteractions(picks);
        verifyNoInteractions(selections, categories, options, comments, targets);
    }

    @Test
    void usesStructuralRulesForDifferentTargetsWithoutResolvingTheirMetadata() {
        UUID viewer = UUID.randomUUID();
        UUID pickId = UUID.randomUUID();
        var template = PicksTemplate.builder().id(UUID.randomUUID()).build();
        var pick = Pick.builder().id(pickId).build();
        var movie = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        var person = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        var episode = category(template, PickAllowedType.EPISODE, PickCategoryOptionMode.OPEN);
        var wrongType = category(template, PickAllowedType.SERIES, PickCategoryOptionMode.OPEN);
        var movieRef = Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("510").build();
        var episodeRef = Content.builder().id(UUID.randomUUID()).type(ContentType.EPISODE)
                .seriesTmdbId("520").seasonNumber(1).episodeNumber(2).build();
        var request = PageRequest.of(0, 10);
        var row = new PickListRow(pickId, template.getId(), "Sparse catalogue", null, null, null, null,
                PickVisibility.FOLLOWERS, LocalDateTime.of(2026, 11, 1, 10, 0), null);
        when(picks.findMyPickList(viewer, request)).thenReturn(new PageImpl<>(List.of(row), request, 1));
        when(categories.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(movie, person, episode, wrongType));
        when(selections.findByPickIdIn(List.of(pickId))).thenReturn(List.of(
                PickSelection.builder().pick(pick).category(movie).content(movieRef).build(),
                PickSelection.builder().pick(pick).category(person).personTmdbId("530").build(),
                PickSelection.builder().pick(pick).category(episode).content(episodeRef).build(),
                PickSelection.builder().pick(pick).category(wrongType).content(movieRef).build()));
        when(options.findByCategoryIdIn(List.of(movie.getId())))
                .thenReturn(List.of(PicksTemplateOption.builder().category(movie).content(movieRef).build()));

        var tmdb = mock(TmdbClient.class);
        var structural = new PickTargetServiceImpl(null, null, options, tmdb, null);
        var list = new MyPicksListService(picks, selections, categories, options, comments, structural, new PageRequestFactory());
        var card = list.list(viewer, 1, 10).getContent().getFirst();
        assertThat(card.answeredCategories()).extracting(chip -> chip.categoryId())
                .containsExactly(movie.getId(), person.getId(), episode.getId());
        assertThat(card.totalCategoriesCount()).isEqualTo(4);
        assertThat(card.picksTemplate().creator()).isNull();
        assertThat(card.likesCount()).isZero();
        verifyNoInteractions(tmdb);
    }

    private PicksTemplateCategory category(PicksTemplate template, PickAllowedType type, PickCategoryOptionMode mode) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template)
                .name(type.name()).allowedType(type).optionMode(mode).build();
    }

    private MyPicksListService service() {
        return new MyPicksListService(picks, selections, categories, options, comments, targets, new PageRequestFactory());
    }
}
