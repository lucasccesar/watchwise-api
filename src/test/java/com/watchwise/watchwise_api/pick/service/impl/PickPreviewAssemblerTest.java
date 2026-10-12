package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickViewCardDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PickPreviewAssemblerTest {

    @Mock PickSelectionRepository selectionRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock PicksTemplateOptionRepository optionRepository;
    @Mock CommentRepository commentRepository;
    @Mock CommentPreviewAssembler commentPreviewAssembler;
    @Mock LikeService likeService;
    @Mock PickMapper pickMapper;
    @Mock PickTargetService targetService;
    @Mock UserMapper userMapper;
    @Mock PicksTemplatePreviewAssembler templatePreviewAssembler;
    @InjectMocks PickPreviewAssembler assembler;

    @Test
    void assemblesPickPreviewWithAnsweredCategoryAndSocialFields() {
        UUID viewerId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory category = PicksTemplateCategory.builder()
                .id(UUID.randomUUID()).picksTemplate(template).name("Movie")
                .group(PickCategoryGroup.PRIMARY).displayOrder(1)
                .allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.OPEN).build();
        Pick pick = Pick.builder().id(UUID.randomUUID()).user(user).picksTemplate(template)
                .visibility(PickVisibility.PUBLIC).createdAt(LocalDateTime.now()).likesCount(4).build();
        PickSelection selection = PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).build();
        PickOptionSearchDTO selectionDto = new PickOptionSearchDTO(UUID.randomUUID(), null, null, null);

        when(selectionRepository.findByPickIdIn(List.of(pick.getId()))).thenReturn(List.of(selection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(category));
        when(targetService.isStructurallyValid(same(category), same(selection), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(selection)).thenReturn(selectionDto);
        when(commentRepository.countByPickIdIn(List.of(pick.getId()))).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, List.of(pick.getId()))).thenReturn(Set.of(pick.getId()));
        UserPreviewDTO userPreview = new UserPreviewDTO(user.getId(), user.getUsername(), null, true);
        when(userMapper.userToUserPreviewDto(user)).thenReturn(userPreview);

        Map<UUID, PickPreviewDTO> result = assembler.assemble(List.of(pick), viewerId);

        PickPreviewDTO preview = result.get(pick.getId());
        assertThat(preview.user()).isEqualTo(userPreview);
        assertThat(preview.visibility()).isEqualTo(PickVisibility.PUBLIC);
        assertThat(preview.likesCount()).isEqualTo(4);
        assertThat(preview.commentsCount()).isZero();
        assertThat(preview.isLikedByViewer()).isTrue();
        assertThat(preview.answeredCategories()).hasSize(1);
        assertThat(preview.answeredCategories().getFirst().target()).isEqualTo(selectionDto);
    }

    @Test
    void assemblesFeedPickPreviewWithOwnRecentCommentsAndCountFromSharedAssembler() {
        UUID viewerId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        Pick pick = Pick.builder().id(UUID.randomUUID()).user(user).picksTemplate(template)
                .visibility(PickVisibility.PUBLIC).createdAt(LocalDateTime.now()).likesCount(4).build();
        CommentResponseDTO comment = new CommentResponseDTO(UUID.randomUUID(), null, null, null, null, null,
                pick.getId(), null, null, "nice", false, LocalDateTime.now(), LocalDateTime.now(), 0, false);

        when(selectionRepository.findByPickIdIn(List.of(pick.getId()))).thenReturn(List.of());
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, List.of(pick.getId()))).thenReturn(Set.of());
        when(commentPreviewAssembler.assemblePickPreviews(List.of(pick.getId()), viewerId))
                .thenReturn(Map.of(pick.getId(), new CommentPreviewData(7, List.of(comment))));
        when(userMapper.userToUserPreviewDto(user)).thenReturn(new UserPreviewDTO(user.getId(), "marina", null, true));

        PickPreviewDTO preview = assembler.assembleForFeed(List.of(pick), viewerId).get(pick.getId());

        assertThat(preview.commentsCount()).isEqualTo(7);
        assertThat(preview.recentComments()).containsExactly(comment);
        verify(commentRepository, never()).countByPickIdIn(any());
    }

    @Test
    void returnsEmptyRecentCommentsWithoutLoadingThemWhenAssemblingOutsideTheFeed() {
        UUID viewerId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        Pick pick = Pick.builder().id(UUID.randomUUID()).user(user).picksTemplate(template)
                .visibility(PickVisibility.PUBLIC).createdAt(LocalDateTime.now()).likesCount(0).build();

        when(selectionRepository.findByPickIdIn(List.of(pick.getId()))).thenReturn(List.of());
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of());
        when(commentRepository.countByPickIdIn(List.of(pick.getId()))).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, List.of(pick.getId()))).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(user)).thenReturn(new UserPreviewDTO(user.getId(), "marina", null, true));

        PickPreviewDTO preview = assembler.assemble(List.of(pick), viewerId).get(pick.getId());

        assertThat(preview.recentComments()).isEmpty();
        verify(commentPreviewAssembler, never()).assemblePickPreviews(any(), any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[assembleViewPage] Should Count Only Valid Answers And Batch Template Previews - When Picks Have Different Progress")
    void shouldCountOnlyValidAnswersAndBatchTemplatePreviewsWhenPicksHaveDifferentProgress() {
        UUID viewerId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate emptyTemplate = PicksTemplate.builder().id(UUID.randomUUID()).name("Empty").build();
        PicksTemplate partialTemplate = PicksTemplate.builder().id(UUID.randomUUID()).name("Partial").build();
        PicksTemplate completeTemplate = PicksTemplate.builder().id(UUID.randomUUID()).name("Complete").build();
        Pick emptyPick = pick(user, emptyTemplate);
        Pick partialPick = pick(user, partialTemplate);
        Pick completePick = pick(user, completeTemplate);
        PicksTemplateCategory validPartial = category(partialTemplate, 1);
        PicksTemplateCategory invalidPartial = category(partialTemplate, 2);
        PicksTemplateCategory unselectedPartial = category(partialTemplate, 3);
        PicksTemplateCategory completeCategory = category(completeTemplate, 1);
        PickSelection validSelection = selection(partialPick, validPartial);
        PickSelection invalidSelection = selection(partialPick, invalidPartial);
        PickSelection completeSelection = selection(completePick, completeCategory);
        List<Pick> picks = List.of(emptyPick, partialPick, completePick);
        List<UUID> pickIds = picks.stream().map(Pick::getId).toList();
        List<PicksTemplateCategory> categories = List.of(validPartial, invalidPartial, unselectedPartial, completeCategory);
        PickOptionSearchDTO target = new PickOptionSearchDTO(UUID.randomUUID(), null, null, null);
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(validSelection, invalidSelection, completeSelection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(
                emptyTemplate.getId(), partialTemplate.getId(), completeTemplate.getId()))).thenReturn(categories);
        when(targetService.isStructurallyValid(validPartial, validSelection, List.of())).thenReturn(true);
        when(targetService.isStructurallyValid(invalidPartial, invalidSelection, List.of())).thenReturn(false);
        when(targetService.isStructurallyValid(completeCategory, completeSelection, List.of())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(target);
        when(commentRepository.countByPickIdIn(pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(user)).thenReturn(new UserPreviewDTO(user.getId(), "marina", null, true));
        when(templatePreviewAssembler.assemble(anyCollection(), org.mockito.ArgumentMatchers.eq(viewerId)))
                .thenReturn(Map.of(emptyTemplate.getId(), preview(emptyTemplate),
                        partialTemplate.getId(), preview(partialTemplate), completeTemplate.getId(), preview(completeTemplate)));
        PageImpl<Pick> page = new PageImpl<>(picks, PageRequest.of(0, 5), picks.size());

        var result = assembler.assembleViewPage(page, viewerId);

        assertThat(result.getContent()).extracting(PickViewCardDTO::progress)
                .containsExactly(PickProgress.EMPTY, PickProgress.PARTIAL, PickProgress.COMPLETE);
        PickViewCardDTO partial = result.getContent().get(1);
        assertThat(partial.answeredCategoriesCount()).isEqualTo(1);
        assertThat(partial.totalCategoriesCount()).isEqualTo(3);
        assertThat(partial.answeredCategories()).extracting(answer -> answer.categoryId()).containsExactly(validPartial.getId());
        verify(templatePreviewAssembler, times(1)).assemble(anyCollection(), org.mockito.ArgumentMatchers.eq(viewerId));
        verify(commentRepository).countByPickIdIn(pickIds);
    }

    private Pick pick(User user, PicksTemplate template) {
        return Pick.builder().id(UUID.randomUUID()).user(user).picksTemplate(template)
                .visibility(PickVisibility.PRIVATE).createdAt(LocalDateTime.now()).likesCount(0).build();
    }

    private PicksTemplateCategory category(PicksTemplate template, int order) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Category " + order)
                .group(PickCategoryGroup.PRIMARY).displayOrder(order).allowedType(PickAllowedType.MOVIE)
                .optionMode(PickCategoryOptionMode.OPEN).build();
    }

    private PickSelection selection(Pick pick, PicksTemplateCategory category) {
        return PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).build();
    }

    private PicksTemplatePreviewDTO preview(PicksTemplate template) {
        return new PicksTemplatePreviewDTO(template.getId(), null, null, template.getName(), null, null,
                null, 0, 0, List.of(), 0, 0, false, 0, null);
    }
}
