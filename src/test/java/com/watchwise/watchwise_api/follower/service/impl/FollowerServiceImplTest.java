package com.watchwise.watchwise_api.follower.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowerServiceImplTest {

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @InjectMocks
    private FollowerServiceImpl followerService;

    @Captor
    private ArgumentCaptor<Follower> followerCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    private UUID followerId;
    private UUID followedId;
    private User followerUser;
    private User followedUser;
    private PublicUserDTO publicUserDTO;

    @BeforeEach
    void setUp() {
        followerId = UUID.randomUUID();
        followedId = UUID.randomUUID();

        followerUser = User.builder()
                .id(followerId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        followedUser = User.builder()
                .id(followedId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        publicUserDTO = new PublicUserDTO(
                followerId,
                "lucas",
                "Some description",
                "https://picture.com/pic.png",
                true,
                LocalDateTime.now()
        );
    }

    private Follower buildFollow(FollowStatus status) {
        return Follower.builder()
                .id(UUID.randomUUID())
                .follower(followerUser)
                .followed(followedUser)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("[followUser] Should Return Accepted And Save With Accepted Status - When Followed User Profile Is Public")
    void shouldReturnAcceptedAndSaveWithAcceptedStatusWhenFollowedUserProfileIsPublic() {
        followedUser.setIsProfilePublic(true);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.of(followerUser));

        FollowStatus result = followerService.followUser(followerId, followedId);

        assertThat(result).isEqualTo(FollowStatus.ACCEPTED);
        verify(followerRepository).save(followerCaptor.capture());
        assertThat(followerCaptor.getValue().getStatus()).isEqualTo(FollowStatus.ACCEPTED);
        assertThat(followerCaptor.getValue().getFollower()).isEqualTo(followerUser);
        assertThat(followerCaptor.getValue().getFollowed()).isEqualTo(followedUser);
    }

    @Test
    @DisplayName("[followUser] Should Return Pending And Save With Pending Status - When Followed User Profile Is Private")
    void shouldReturnPendingAndSaveWithPendingStatusWhenFollowedUserProfileIsPrivate() {
        followedUser.setIsProfilePublic(false);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.of(followerUser));

        FollowStatus result = followerService.followUser(followerId, followedId);

        assertThat(result).isEqualTo(FollowStatus.PENDING);
        verify(followerRepository).save(followerCaptor.capture());
        assertThat(followerCaptor.getValue().getStatus()).isEqualTo(FollowStatus.PENDING);
    }

    @Test
    @DisplayName("[followUser] Should Throw BadRequestException - When Follower And Followed Ids Are The Same")
    void shouldThrowBadRequestExceptionWhenFollowerAndFollowedIdsAreTheSame() {
        assertThatThrownBy(() -> followerService.followUser(followerId, followerId))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Cannot follow yourself");

        verifyNoInteractions(followerRepository, userRepository);
    }

    @Test
    @DisplayName("[followUser] Should Throw NotFoundException - When Followed User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenFollowedUserDoesNotExist() {
        when(userRepository.findById(followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.followUser(followerId, followedId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[followUser] Should Throw NotFoundException - When Follower's Own Account No Longer Exists")
    void shouldThrowNotFoundExceptionWhenFollowersOwnAccountNoLongerExists() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.followUser(followerId, followedId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[followUser] Should Throw ConflictException With Specific Message - When Unique Constraint Is Violated")
    void shouldThrowConflictExceptionWithSpecificMessageWhenUniqueConstraintIsViolated() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.of(followerUser));
        when(followerRepository.save(any(Follower.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_followers_follower_id_followed_id"));

        assertThatThrownBy(() -> followerService.followUser(followerId, followedId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Already following or already requested to follow this user");
    }

    @Test
    @DisplayName("[followUser] Should Throw ConflictException With Generic Message - When Constraint Name Is Unknown")
    void shouldThrowConflictExceptionWithGenericMessageWhenConstraintNameIsUnknown() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.of(followerUser));
        when(followerRepository.save(any(Follower.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_some_other_constraint"));

        assertThatThrownBy(() -> followerService.followUser(followerId, followedId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to follow this user");
    }

    @Test
    @DisplayName("[followUser] Should Throw ConflictException With Generic Message - When Cause Is Not ConstraintViolationException")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNotConstraintViolationException() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.of(followerUser));
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("generic db error", new RuntimeException("unexpected cause"));
        when(followerRepository.save(any(Follower.class))).thenThrow(exception);

        assertThatThrownBy(() -> followerService.followUser(followerId, followedId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to follow this user");
    }

    @Test
    @DisplayName("[followUser] Should Throw ConflictException With Generic Message - When Cause Is Null")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNull() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(userRepository.findById(followerId)).thenReturn(Optional.of(followerUser));
        DataIntegrityViolationException exception = new DataIntegrityViolationException("no cause here");
        when(followerRepository.save(any(Follower.class))).thenThrow(exception);

        assertThatThrownBy(() -> followerService.followUser(followerId, followedId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to follow this user");
    }

    @Test
    @DisplayName("[unfollowUser] Should Delete The Follow Relationship - When It Exists")
    void shouldDeleteTheFollowRelationshipWhenItExists() {
        Follower follow = buildFollow(FollowStatus.ACCEPTED);
        when(followerRepository.findByFollowerIdAndFollowedId(followerId, followedId)).thenReturn(Optional.of(follow));

        followerService.unfollowUser(followerId, followedId);

        verify(followerRepository).delete(follow);
    }

    @Test
    @DisplayName("[unfollowUser] Should Throw NotFoundException - When The Relationship Does Not Exist")
    void shouldThrowNotFoundExceptionWhenTheRelationshipDoesNotExist() {
        when(followerRepository.findByFollowerIdAndFollowedId(followerId, followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.unfollowUser(followerId, followedId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Follow relationship not found");

        verify(followerRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Set Status To Accepted And Save - When A Pending Request Exists")
    void shouldSetStatusToAcceptedAndSaveWhenAPendingRequestExists() {
        Follower pending = buildFollow(FollowStatus.PENDING);
        when(followerRepository.findByFollowerIdAndFollowedIdAndStatus(followerId, followedId, FollowStatus.PENDING))
                .thenReturn(Optional.of(pending));

        followerService.acceptFollowRequest(followedId, followerId);

        verify(followerRepository).save(followerCaptor.capture());
        assertThat(followerCaptor.getValue().getStatus()).isEqualTo(FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Throw NotFoundException - When No Pending Request Exists")
    void shouldThrowNotFoundExceptionWhenNoPendingRequestExistsOnAccept() {
        when(followerRepository.findByFollowerIdAndFollowedIdAndStatus(followerId, followedId, FollowStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.acceptFollowRequest(followedId, followerId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Follow request not found");

        verify(followerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Delete The Request - When A Pending Request Exists")
    void shouldDeleteTheRequestWhenAPendingRequestExists() {
        Follower pending = buildFollow(FollowStatus.PENDING);
        when(followerRepository.findByFollowerIdAndFollowedIdAndStatus(followerId, followedId, FollowStatus.PENDING))
                .thenReturn(Optional.of(pending));

        followerService.rejectFollowRequest(followedId, followerId);

        verify(followerRepository).delete(pending);
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Throw NotFoundException - When No Pending Request Exists")
    void shouldThrowNotFoundExceptionWhenNoPendingRequestExistsOnReject() {
        when(followerRepository.findByFollowerIdAndFollowedIdAndStatus(followerId, followedId, FollowStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.rejectFollowRequest(followedId, followerId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Follow request not found");

        verify(followerRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[getFollowers] Should Return Page Of PublicUserDTO - When Target Profile Is Public")
    void shouldReturnPageOfPublicUserDtoWhenTargetProfileIsPublic() {
        followedUser.setIsProfilePublic(true);
        Follower follow = buildFollow(FollowStatus.ACCEPTED);
        Page<Follower> followerPage = new PageImpl<>(List.of(follow));
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(followerPage);
        when(userMapper.userToPublicUserDto(followerUser)).thenReturn(publicUserDTO);

        Page<PublicUserDTO> result = followerService.getFollowers(UUID.randomUUID(), followedId, 1, 10);

        assertThat(result.getContent()).containsExactly(publicUserDTO);
    }

    @Test
    @DisplayName("[getFollowers] Should Return Empty Page - When Target Has No Accepted Followers")
    void shouldReturnEmptyPageWhenTargetHasNoAcceptedFollowers() {
        followedUser.setIsProfilePublic(true);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<PublicUserDTO> result = followerService.getFollowers(UUID.randomUUID(), followedId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(userMapper, never()).userToPublicUserDto(any());
    }

    @Test
    @DisplayName("[getFollowers] Should Throw NotFoundException - When Target User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenTargetUserDoesNotExistForFollowers() {
        when(userRepository.findById(followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.getFollowers(UUID.randomUUID(), followedId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getFollowers] Should Return Page - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnPageWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() {
        followedUser.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, followedId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(followerRepository.findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(followerService.getFollowers(viewerId, followedId, 1, 10).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowers] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        followedUser.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, followedId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> followerService.getFollowers(viewerId, followedId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(followerRepository, never()).findByFollowedIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("[getFollowers] Should Return Page - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnPageWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselves() {
        followedUser.setIsProfilePublic(false);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(followerService.getFollowers(followedId, followedId, 1, 10).getContent()).isEmpty();
        verify(followerRepository, never()).existsByFollowerIdAndFollowedIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("[getFollowers] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, null, 10);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getFollowers] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, 0, 10);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getFollowers] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, 3, 10);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getFollowers] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));

        assertThatThrownBy(() -> followerService.getFollowers(UUID.randomUUID(), followedId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(followerRepository, never()).findByFollowedIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("[getFollowers] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, 1, null);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getFollowers] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, 1, 1001);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getFollowers] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, 1, 25);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getFollowers] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubFollowersEmptyPage();

        followerService.getFollowers(UUID.randomUUID(), followedId, 1, 1000);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getFollowers] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));

        assertThatThrownBy(() -> followerService.getFollowers(UUID.randomUUID(), followedId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(followerRepository, never()).findByFollowedIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("[getFollowers] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));

        assertThatThrownBy(() -> followerService.getFollowers(UUID.randomUUID(), followedId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(followerRepository, never()).findByFollowedIdAndStatus(any(), any(), any());
    }

    private void stubFollowersEmptyPage() {
        followedUser.setIsProfilePublic(true);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowedIdAndStatus(any(), any(), any(PageRequest.class))).thenReturn(Page.empty());
    }

    @Test
    @DisplayName("[getFollowing] Should Return Page Of PublicUserDTO - When Target Profile Is Public")
    void shouldReturnPageOfPublicUserDtoWhenTargetProfileIsPublicForFollowing() {
        followedUser.setIsProfilePublic(true);
        Follower follow = buildFollow(FollowStatus.ACCEPTED);
        Page<Follower> followerPage = new PageImpl<>(List.of(follow));
        PublicUserDTO followedPublicDto = new PublicUserDTO(
                followedId, "marina", null, "https://picture.com/pic.png", true, LocalDateTime.now());
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowerIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(followerPage);
        when(userMapper.userToPublicUserDto(followedUser)).thenReturn(followedPublicDto);

        Page<PublicUserDTO> result = followerService.getFollowing(UUID.randomUUID(), followedId, 1, 10);

        assertThat(result.getContent()).containsExactly(followedPublicDto);
    }

    @Test
    @DisplayName("[getFollowing] Should Return Empty Page - When Target Follows No One")
    void shouldReturnEmptyPageWhenTargetFollowsNoOne() {
        followedUser.setIsProfilePublic(true);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowerIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<PublicUserDTO> result = followerService.getFollowing(UUID.randomUUID(), followedId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(userMapper, never()).userToPublicUserDto(any());
    }

    @Test
    @DisplayName("[getFollowing] Should Throw NotFoundException - When Target User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenTargetUserDoesNotExistForFollowing() {
        when(userRepository.findById(followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followerService.getFollowing(UUID.randomUUID(), followedId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getFollowing] Should Return Page - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnPageWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollowerForFollowing() {
        followedUser.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, followedId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(followerRepository.findByFollowerIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(followerService.getFollowing(viewerId, followedId, 1, 10).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowing] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollowerForFollowing() {
        followedUser.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, followedId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> followerService.getFollowing(viewerId, followedId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(followerRepository, never()).findByFollowerIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("[getFollowing] Should Return Page - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnPageWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselvesForFollowing() {
        followedUser.setIsProfilePublic(false);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowerIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(followerService.getFollowing(followedId, followedId, 1, 10).getContent()).isEmpty();
        verify(followerRepository, never()).existsByFollowerIdAndFollowedIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("[getFollowing] Should Default Page And Size - When Both Are Null")
    void shouldDefaultPageAndSizeWhenBothAreNullForFollowing() {
        followedUser.setIsProfilePublic(true);
        when(userRepository.findById(followedId)).thenReturn(Optional.of(followedUser));
        when(followerRepository.findByFollowerIdAndStatus(any(), any(), any(PageRequest.class))).thenReturn(Page.empty());

        followerService.getFollowing(UUID.randomUUID(), followedId, null, null);

        verify(followerRepository).findByFollowerIdAndStatus(eq(followedId), eq(FollowStatus.ACCEPTED), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Page Of PublicUserDTO - When Pending Requests Exist")
    void shouldReturnPageOfPublicUserDtoWhenPendingRequestsExist() {
        Follower pending = buildFollow(FollowStatus.PENDING);
        Page<Follower> followerPage = new PageImpl<>(List.of(pending));
        when(followerRepository.findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.PENDING), any(PageRequest.class)))
                .thenReturn(followerPage);
        when(userMapper.userToPublicUserDto(followerUser)).thenReturn(publicUserDTO);

        Page<PublicUserDTO> result = followerService.getPendingFollowRequests(followedId, 1, 10);

        assertThat(result.getContent()).containsExactly(publicUserDTO);
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Empty Page - When No Pending Requests Exist")
    void shouldReturnEmptyPageWhenNoPendingRequestsExist() {
        when(followerRepository.findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.PENDING), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<PublicUserDTO> result = followerService.getPendingFollowRequests(followedId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(userMapper, never()).userToPublicUserDto(any());
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Default Page And Size - When Both Are Null")
    void shouldDefaultPageAndSizeWhenBothAreNullForPendingRequests() {
        when(followerRepository.findByFollowedIdAndStatus(any(), any(), any(PageRequest.class))).thenReturn(Page.empty());

        followerService.getPendingFollowRequests(followedId, null, null);

        verify(followerRepository).findByFollowedIdAndStatus(eq(followedId), eq(FollowStatus.PENDING), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[acceptAllPendingFollowRequestsFor] Should Delegate To Repository - When Called")
    void shouldDelegateToRepositoryWhenAcceptAllPendingFollowRequestsForCalled() {
        followerService.acceptAllPendingFollowRequestsFor(followedId);

        verify(followerRepository).acceptAllPendingFollowRequestsFor(followedId);
    }

    private DataIntegrityViolationException buildDataIntegrityViolationException(String constraintName) {
        ConstraintViolationException cve = new ConstraintViolationException(
                "constraint violated",
                null,
                constraintName
        );
        return new DataIntegrityViolationException("db error", cve);
    }
}