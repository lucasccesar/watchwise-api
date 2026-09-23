package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
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
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickPreviewAssemblerTest {

    @Mock PickSelectionRepository selectionRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock PicksTemplateOptionRepository optionRepository;
    @Mock CommentRepository commentRepository;
    @Mock LikeService likeService;
    @Mock PickMapper pickMapper;
    @Mock PickTargetService targetService;
    @Mock UserMapper userMapper;
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
}
