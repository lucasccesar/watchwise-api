package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickTargetPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTemplatePickViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickViewCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
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
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock PickTargetPreviewAssembler targetPreviewAssembler;
    @Mock UserRepository userRepository;
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
        when(commentRepository.countVisiblePickComments(viewerId, List.of(pick.getId()))).thenReturn(List.of());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"collection", "page", "view", "templateView", "detail"})
    void excludesBlockedAuthorsFromStandardPickCounts(String path) {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        Pick pick = pick(owner, template);
        List<UUID> ids = List.of(pick.getId());
        org.mockito.Mockito.lenient().when(commentRepository.countVisiblePickComments(viewerId, ids))
                .thenReturn(List.of(new CommentRepository.PickCommentCount() {
                    public UUID getPickId() { return pick.getId(); }
                    public long getCount() { return 1L; }
                }));
        org.mockito.Mockito.lenient().when(templatePreviewAssembler.assemble(anyCollection(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), preview(template)));
        PageImpl<Pick> page = new PageImpl<>(List.of(pick));

        long count = switch (path) {
            case "page" -> assembler.assemblePage(page, viewerId).getContent().getFirst().commentsCount();
            case "view" -> assembler.assembleViewPage(page, viewerId).getContent().getFirst().commentsCount();
            case "templateView" -> assembler.assembleTemplateViewPage(page, viewerId).getContent().getFirst().commentsCount();
            case "detail" -> assembler.assembleDetail(pick, viewerId).commentsCount();
            default -> assembler.assemble(List.of(pick), viewerId).get(pick.getId()).commentsCount();
        };

        assertThat(count).isEqualTo(1L);
        verify(commentRepository, never()).countByPickIdIn(any());
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
        when(commentRepository.countVisiblePickComments(viewerId, List.of(pick.getId()))).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, List.of(pick.getId()))).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(user)).thenReturn(new UserPreviewDTO(user.getId(), "marina", null, true));

        PickPreviewDTO preview = assembler.assemble(List.of(pick), viewerId).get(pick.getId());

        assertThat(preview.recentComments()).isEmpty();
        verify(commentPreviewAssembler, never()).assemblePickPreviews(any(), any());
    }

    @Test
    @DisplayName("[assembleViewPage] Should Count Only Valid Answers And Batch Template Previews - When Picks Have Different Progress")
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
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
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
        verify(commentRepository).countVisiblePickComments(viewerId, pickIds);
    }

    @Test
    @DisplayName("[assembleViewPage] Should Preserve Visual Target Positions - When Repeated Targets Share One Batch")
    void shouldAssembleVisualTargetPreviewsOnceAndPreserveContentPersonAndContextTargetsWhenTargetsRepeat() {
        UUID viewerId = UUID.randomUUID();
        User viewer = User.builder().id(viewerId).username("viewer").preferredLanguage("pt-BR")
                .preferredRegion("BR").build();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory movieCategory = category(template, 1);
        PicksTemplateCategory personCategory = PicksTemplateCategory.builder().id(UUID.randomUUID())
                .picksTemplate(template).name("Actor").group(PickCategoryGroup.SECONDARY).displayOrder(2)
                .allowedType(PickAllowedType.PERSON).optionMode(PickCategoryOptionMode.OPEN).build();
        Pick pick = pick(owner, template);
        PickSelection movieSelection = selection(pick, movieCategory);
        PickSelection personSelection = selection(pick, personCategory);
        ContentRefDTO movieRef = contentRef("550", ContentType.MOVIE);
        ContentRefDTO contextRef = contentRef("550", ContentType.MOVIE);
        PickOptionSearchDTO movieTarget = new PickOptionSearchDTO(movieSelection.getId(), movieRef, null, null);
        PickOptionSearchDTO personTarget = new PickOptionSearchDTO(personSelection.getId(), null, "42", contextRef);
        PickTargetPreviewDTO moviePreview = new PickTargetPreviewDTO(contentCard("550", "Back to the Future"), null, null);
        PickTargetPreviewDTO personPreview = new PickTargetPreviewDTO(null,
                new PickPersonPreviewDTO("42", "Christopher Lloyd", "/lloyd.jpg", ContentPreviewStatus.AVAILABLE),
                contentCard("550", "Back to the Future"));
        List<UUID> pickIds = List.of(pick.getId());

        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(movieSelection, personSelection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(movieCategory, personCategory));
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(movieSelection)).thenReturn(movieTarget);
        when(pickMapper.pickSelectionToSearchDto(personSelection)).thenReturn(personTarget);
        when(targetPreviewAssembler.assemble(List.of(movieTarget, personTarget),
                new ContentCardContext("pt-BR", "BR", null, null)))
                .thenReturn(List.of(moviePreview, personPreview));
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));
        when(templatePreviewAssembler.assemble(anyCollection(), org.mockito.ArgumentMatchers.eq(viewerId)))
                .thenReturn(Map.of(template.getId(), preview(template)));

        PickViewCardDTO result = assembler.assembleViewPage(new PageImpl<>(List.of(pick)), viewerId).getContent().getFirst();

        assertThat(result.answeredCategories()).hasSize(2);
        assertThat(result.answeredCategories().get(0).target().content().tmdbId()).isEqualTo("550");
        assertThat(result.answeredCategories().get(0).preview().contentCard().title())
                .isEqualTo("Back to the Future");
        assertThat(result.answeredCategories().get(1).target().personTmdbId()).isEqualTo("42");
        assertThat(result.answeredCategories().get(1).preview().person().name()).isEqualTo("Christopher Lloyd");
        assertThat(result.answeredCategories().get(1).preview().contextCard().title())
                .isEqualTo("Back to the Future");
        verify(targetPreviewAssembler).assemble(List.of(movieTarget, personTarget),
                new ContentCardContext("pt-BR", "BR", null, null));
        verify(userRepository).findById(viewerId);
    }

    @Test
    @DisplayName("[assembleTemplateViewPage] Should Delegate Target Previews Once - When A Template Pick Is Assembled")
    void assemblesTemplatePickViewWithTheSameVisualAnswers() {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory category = category(template, 1);
        Pick pick = pick(owner, template);
        PickSelection selection = selection(pick, category);
        PickOptionSearchDTO target = new PickOptionSearchDTO(selection.getId(), contentRef("550", ContentType.MOVIE), null, null);
        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(contentCard("550", "Back to the Future"), null, null);
        List<UUID> pickIds = List.of(pick.getId());
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(selection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(category));
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(selection)).thenReturn(target);
        when(targetPreviewAssembler.assemble(any(), any())).thenReturn(List.of(preview));
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));

        PickTemplatePickViewDTO result = assembler.assembleTemplateViewPage(new PageImpl<>(List.of(pick)), viewerId)
                .getContent().getFirst();

        assertThat(result.answeredCategories()).singleElement().satisfies(answer -> {
            assertThat(answer.target()).isEqualTo(target);
            assertThat(answer.preview()).isEqualTo(preview);
        });
        assertThat(result.progress()).isEqualTo(PickProgress.COMPLETE);
        verify(targetPreviewAssembler, times(1)).assemble(any(), any());
    }

    @Test
    @DisplayName("[assembleTemplateViewPage] Should Keep Full Count While Showing Five Answers - When Six Targets Are Valid")
    void limitsTemplatePickAnswersToFiveWhileKeepingTheFullValidCount() {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        Pick pick = pick(owner, template);
        List<PicksTemplateCategory> categories = new ArrayList<>();
        List<PickSelection> selections = new ArrayList<>();
        for (int order = 1; order <= 6; order++) {
            PicksTemplateCategory category = category(template, order);
            categories.add(category);
            selections.add(selection(pick, category));
        }
        List<UUID> pickIds = List.of(pick.getId());
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(selections);
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(categories);
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(any(PickSelection.class))).thenAnswer(invocation -> {
            PickSelection selection = invocation.getArgument(0);
            return new PickOptionSearchDTO(selection.getId(), contentRef(String.valueOf(selection.getCategory().getDisplayOrder()),
                    ContentType.MOVIE), null, null);
        });
        when(targetPreviewAssembler.assemble(any(), any())).thenReturn(List.of());
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));

        PickTemplatePickViewDTO result = assembler.assembleTemplateViewPage(new PageImpl<>(List.of(pick)), viewerId)
                .getContent().getFirst();

        assertThat(result.answeredCategories()).hasSize(5);
        assertThat(result.answeredCategories()).extracting(answer -> answer.displayOrder())
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(result.answeredCategoriesCount()).isEqualTo(6);
        assertThat(result.progress()).isEqualTo(PickProgress.COMPLETE);
    }

    @Test
    @DisplayName("[assembleDetail] Should Include All Categories Without Dynamic Validation - When A Pick Is Detailed")
    void assemblesDetailWithAllCategoriesAndDoesNotRunDynamicTargetValidation() {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory answeredCategory = category(template, 1);
        PicksTemplateCategory unansweredCategory = category(template, 2);
        Pick pick = pick(owner, template);
        PickSelection selection = selection(pick, answeredCategory);
        PickOptionSearchDTO target = new PickOptionSearchDTO(selection.getId(), contentRef("550", ContentType.MOVIE), null, null);
        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(contentCard("550", "Back to the Future"), null, null);
        List<UUID> pickIds = List.of(pick.getId());
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(selection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(answeredCategory, unansweredCategory));
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(selection)).thenReturn(target);
        when(targetPreviewAssembler.assemble(any(), any())).thenReturn(List.of(preview));
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));
        when(templatePreviewAssembler.assemble(anyCollection(), org.mockito.ArgumentMatchers.eq(viewerId)))
                .thenReturn(Map.of(template.getId(), preview(template)));

        var result = assembler.assembleDetail(pick, viewerId);

        assertThat(result.answers()).hasSize(2);
        assertThat(result.answers().get(0).preview()).isEqualTo(preview);
        assertThat(result.answers().get(0).target()).isEqualTo(target);
        assertThat(result.answers().get(1).isAnswered()).isFalse();
        assertThat(result.answers().get(1).target()).isNull();
        assertThat(result.answers().get(1).preview()).isNull();
        assertThat(result.answeredCategoriesCount()).isEqualTo(1);
        verify(targetService, never()).isValid(any(), any(), any(), any());
        verify(targetPreviewAssembler, times(1)).assemble(any(), any());
    }

    @Test
    @DisplayName("[assembleViewPage] Should Preserve Raw IDs And Preview Statuses - When Metadata Is Partial Or Unavailable")
    void shouldPreserveRawIdsAndPreviewStatusesWhenMetadataIsPartialOrUnavailable() {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory partialCategory = category(template, 1);
        PicksTemplateCategory unavailableCategory = category(template, 2);
        Pick pick = pick(owner, template);
        PickSelection partialSelection = selection(pick, partialCategory);
        PickSelection unavailableSelection = selection(pick, unavailableCategory);
        PickOptionSearchDTO partialTarget = new PickOptionSearchDTO(partialSelection.getId(),
                contentRef("550", ContentType.MOVIE), null, null);
        PickOptionSearchDTO unavailableTarget = new PickOptionSearchDTO(unavailableSelection.getId(),
                contentRef("999", ContentType.MOVIE), null, null);
        PickTargetPreviewDTO partialPreview = new PickTargetPreviewDTO(
                contentCard("550", "Back to the Future", ContentPreviewStatus.PARTIAL), null, null);
        PickTargetPreviewDTO unavailablePreview = new PickTargetPreviewDTO(
                contentCard("999", null, ContentPreviewStatus.UNAVAILABLE), null, null);
        List<UUID> pickIds = List.of(pick.getId());
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(partialSelection, unavailableSelection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(partialCategory, unavailableCategory));
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(partialSelection)).thenReturn(partialTarget);
        when(pickMapper.pickSelectionToSearchDto(unavailableSelection)).thenReturn(unavailableTarget);
        when(targetPreviewAssembler.assemble(any(), any())).thenReturn(List.of(partialPreview, unavailablePreview));
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));
        when(templatePreviewAssembler.assemble(anyCollection(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), preview(template)));

        PickViewCardDTO result = assembler.assembleViewPage(new PageImpl<>(List.of(pick)), viewerId)
                .getContent().getFirst();

        assertThat(result.answeredCategories()).extracting(answer -> answer.target().content().tmdbId())
                .containsExactly("550", "999");
        assertThat(result.answeredCategories()).extracting(answer -> answer.preview().contentCard().previewStatus())
                .containsExactly(ContentPreviewStatus.PARTIAL, ContentPreviewStatus.UNAVAILABLE);
        verify(targetPreviewAssembler, times(1)).assemble(any(), any());
    }

    @Test
    @DisplayName("[assembleViewPage] Should Delegate Repeated Targets In One Ordered Batch - When Targets Repeat Across Answers")
    void shouldDelegateRepeatedTargetsInOneOrderedBatchWhenTargetsRepeatAcrossAnswers() {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory firstCategory = category(template, 1);
        PicksTemplateCategory secondCategory = category(template, 2);
        Pick pick = pick(owner, template);
        PickSelection firstSelection = selection(pick, firstCategory);
        PickSelection secondSelection = selection(pick, secondCategory);
        ContentRefDTO repeatedRef = contentRef("550", ContentType.MOVIE);
        PickOptionSearchDTO firstTarget = new PickOptionSearchDTO(firstSelection.getId(), repeatedRef, null, null);
        PickOptionSearchDTO secondTarget = new PickOptionSearchDTO(secondSelection.getId(), repeatedRef, null, null);
        PickTargetPreviewDTO firstPreview = new PickTargetPreviewDTO(contentCard("550", "First", ContentPreviewStatus.AVAILABLE), null, null);
        PickTargetPreviewDTO secondPreview = new PickTargetPreviewDTO(contentCard("550", "Second", ContentPreviewStatus.AVAILABLE), null, null);
        List<UUID> pickIds = List.of(pick.getId());
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(firstSelection, secondSelection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(firstCategory, secondCategory));
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(firstSelection)).thenReturn(firstTarget);
        when(pickMapper.pickSelectionToSearchDto(secondSelection)).thenReturn(secondTarget);
        when(targetPreviewAssembler.assemble(any(), any())).thenReturn(List.of(firstPreview, secondPreview));
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));
        when(templatePreviewAssembler.assemble(anyCollection(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), preview(template)));

        PickViewCardDTO result = assembler.assembleViewPage(new PageImpl<>(List.of(pick)), viewerId)
                .getContent().getFirst();

        assertThat(result.answeredCategories()).extracting(answer -> answer.preview().contentCard().title())
                .containsExactly("First", "Second");
        ArgumentCaptor<List<PickOptionSearchDTO>> targets = ArgumentCaptor.forClass(List.class);
        verify(targetPreviewAssembler, times(1)).assemble(targets.capture(), any());
        assertThat(targets.getValue()).containsExactly(firstTarget, secondTarget);
    }

    @Test
    @DisplayName("[assembleDetail] Should Preserve Repeated Target Positions - When Detail Uses One Delegated Batch")
    void shouldPreserveRepeatedTargetPositionsWhenDetailUsesOneDelegatedBatch() {
        UUID viewerId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").build();
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).name("Awards").build();
        PicksTemplateCategory firstCategory = category(template, 1);
        PicksTemplateCategory secondCategory = category(template, 2);
        Pick pick = pick(owner, template);
        PickSelection firstSelection = selection(pick, firstCategory);
        PickSelection secondSelection = selection(pick, secondCategory);
        ContentRefDTO repeatedRef = contentRef("550", ContentType.MOVIE);
        PickOptionSearchDTO firstTarget = new PickOptionSearchDTO(firstSelection.getId(), repeatedRef, null, null);
        PickOptionSearchDTO secondTarget = new PickOptionSearchDTO(secondSelection.getId(), repeatedRef, null, null);
        PickTargetPreviewDTO firstPreview = new PickTargetPreviewDTO(contentCard("550", "First", ContentPreviewStatus.AVAILABLE), null, null);
        PickTargetPreviewDTO secondPreview = new PickTargetPreviewDTO(contentCard("550", "Second", ContentPreviewStatus.AVAILABLE), null, null);
        List<UUID> pickIds = List.of(pick.getId());
        when(selectionRepository.findByPickIdIn(pickIds)).thenReturn(List.of(firstSelection, secondSelection));
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(List.of(template.getId())))
                .thenReturn(List.of(firstCategory, secondCategory));
        when(targetService.isStructurallyValid(any(), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(firstSelection)).thenReturn(firstTarget);
        when(pickMapper.pickSelectionToSearchDto(secondSelection)).thenReturn(secondTarget);
        when(targetPreviewAssembler.assemble(any(), any())).thenReturn(List.of(firstPreview, secondPreview));
        when(commentRepository.countVisiblePickComments(viewerId, pickIds)).thenReturn(List.of());
        when(likeService.getLikedPickIds(viewerId, pickIds)).thenReturn(Set.of());
        when(userMapper.userToUserPreviewDto(owner)).thenReturn(new UserPreviewDTO(owner.getId(), "marina", null, true));
        when(templatePreviewAssembler.assemble(anyCollection(), eq(viewerId)))
                .thenReturn(Map.of(template.getId(), preview(template)));

        var result = assembler.assembleDetail(pick, viewerId);

        assertThat(result.answers()).extracting(answer -> answer.preview().contentCard().title())
                .containsExactly("First", "Second");
        ArgumentCaptor<List<PickOptionSearchDTO>> targets = ArgumentCaptor.forClass(List.class);
        verify(targetPreviewAssembler, times(1)).assemble(targets.capture(), any());
        assertThat(targets.getValue()).containsExactly(firstTarget, secondTarget);
    }

    private Pick pick(User user, PicksTemplate template) {
        return Pick.builder().id(UUID.randomUUID()).user(user).picksTemplate(template)
                .visibility(PickVisibility.PUBLIC).createdAt(LocalDateTime.now()).likesCount(0).build();
    }

    private PicksTemplateCategory category(PicksTemplate template, int order) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Category " + order)
                .group(PickCategoryGroup.PRIMARY).displayOrder(order).allowedType(PickAllowedType.MOVIE)
                .optionMode(PickCategoryOptionMode.OPEN).build();
    }

    private PickSelection selection(Pick pick, PicksTemplateCategory category) {
        return PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).build();
    }

    private ContentRefDTO contentRef(String tmdbId, ContentType type) {
        return new ContentRefDTO(null, tmdbId, type, null, null, null, null, null, null, null);
    }

    private ContentCardDTO contentCard(String tmdbId, String title) {
        return contentCard(tmdbId, title, ContentPreviewStatus.AVAILABLE);
    }

    private ContentCardDTO contentCard(String tmdbId, String title, ContentPreviewStatus status) {
        return new ContentCardDTO(null, ContentType.MOVIE, tmdbId, null, null, null, title, null, null,
                null, null, null, null, null, null, null, null, null, status);
    }

    private PicksTemplatePreviewDTO preview(PicksTemplate template) {
        return new PicksTemplatePreviewDTO(template.getId(), null, null, template.getName(), null, null,
                null, 0, 0, List.of(), 0, 0, false, 0, null);
    }
}
