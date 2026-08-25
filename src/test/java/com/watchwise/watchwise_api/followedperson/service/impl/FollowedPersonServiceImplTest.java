package com.watchwise.watchwise_api.followedperson.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.followedperson.entity.FollowedPerson;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowedPersonServiceImplTest {

    @Mock
    private FollowedPersonRepository followedPersonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @InjectMocks
    private FollowedPersonServiceImpl followedPersonService;

    @Captor
    private ArgumentCaptor<FollowedPerson> followedPersonCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    private UUID userId;
    private User user;
    private String personTmdbId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        personTmdbId = "123";

        user = User.builder()
                .id(userId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("[followPerson] Should Save New FollowedPerson - When Not Already Following")
    void shouldSaveNewFollowedPersonWhenNotAlreadyFollowing() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        followedPersonService.followPerson(userId, personTmdbId);

        verify(followedPersonRepository).saveAndFlush(followedPersonCaptor.capture());
        assertThat(followedPersonCaptor.getValue().getUser()).isEqualTo(user);
        assertThat(followedPersonCaptor.getValue().getPersonTmdbId()).isEqualTo(personTmdbId);
        assertThat(followedPersonCaptor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[followPerson] Should Attempt Save In A New Transaction - When Not Already Following")
    void shouldAttemptSaveInANewTransactionWhenNotAlreadyFollowing() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        followedPersonService.followPerson(userId, personTmdbId);

        verify(newTransactionExecutor).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[followPerson] Should Do Nothing - When Already Following That Person")
    void shouldDoNothingWhenAlreadyFollowingThatPerson() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(true);

        followedPersonService.followPerson(userId, personTmdbId);

        verify(followedPersonRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("[followPerson] Should Throw NotFoundException - When The Acting User's Account No Longer Exists")
    void shouldThrowNotFoundExceptionWhenTheActingUsersAccountNoLongerExists() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followedPersonService.followPerson(userId, personTmdbId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(followedPersonRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[followPerson] Should Resolve Successfully - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldResolveSuccessfullyWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExists() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId))
                .thenReturn(false)
                .thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followedPersonRepository.saveAndFlush(any(FollowedPerson.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> followedPersonService.followPerson(userId, personTmdbId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[followPerson] Should Rethrow DataIntegrityViolationException - When Row Still Does Not Exist After Save Fails")
    void shouldRethrowDataIntegrityViolationExceptionWhenRowStillDoesNotExistAfterSaveFails() {
        when(followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected db error");
        when(followedPersonRepository.saveAndFlush(any(FollowedPerson.class))).thenThrow(exception);

        assertThatThrownBy(() -> followedPersonService.followPerson(userId, personTmdbId))
                .isSameAs(exception);
    }

    @Test
    @DisplayName("[followPerson] Should Throw BadRequestException - When PersonTmdbId Is Not Numeric")
    void shouldThrowBadRequestExceptionWhenPersonTmdbIdIsNotNumericOnFollow() {
        assertThatThrownBy(() -> followedPersonService.followPerson(userId, "abc"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(followedPersonRepository, userRepository);
    }

    @Test
    @DisplayName("[followPerson] Should Throw BadRequestException - When PersonTmdbId Exceeds 20 Digits")
    void shouldThrowBadRequestExceptionWhenPersonTmdbIdExceedsTwentyDigitsOnFollow() {
        assertThatThrownBy(() -> followedPersonService.followPerson(userId, "123456789012345678901"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(followedPersonRepository, userRepository);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Throw BadRequestException - When PersonTmdbId Is Not Numeric")
    void shouldThrowBadRequestExceptionWhenPersonTmdbIdIsNotNumericOnUnfollow() {
        assertThatThrownBy(() -> followedPersonService.unfollowPerson(userId, "abc"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(followedPersonRepository);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Delete The Row - When It Exists")
    void shouldDeleteTheRowWhenItExists() {
        FollowedPerson followedPerson = FollowedPerson.builder()
                .id(UUID.randomUUID())
                .user(user)
                .personTmdbId(personTmdbId)
                .createdAt(LocalDateTime.now())
                .build();
        when(followedPersonRepository.findByUserIdAndPersonTmdbId(userId, personTmdbId))
                .thenReturn(Optional.of(followedPerson));

        followedPersonService.unfollowPerson(userId, personTmdbId);

        verify(followedPersonRepository).delete(followedPerson);
    }

    @Test
    @DisplayName("[unfollowPerson] Should Do Nothing - When The Row Does Not Exist")
    void shouldDoNothingWhenTheRowDoesNotExist() {
        when(followedPersonRepository.findByUserIdAndPersonTmdbId(userId, personTmdbId))
                .thenReturn(Optional.empty());

        followedPersonService.unfollowPerson(userId, personTmdbId);

        verify(followedPersonRepository, never()).delete(any());
    }

    private FollowedPerson buildFollowedPerson(String followedPersonTmdbId) {
        return FollowedPerson.builder()
                .id(UUID.randomUUID())
                .user(user)
                .personTmdbId(followedPersonTmdbId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Page Of PersonTmdbIds - When Target Profile Is Public")
    void shouldReturnPageOfPersonTmdbIdsWhenTargetProfileIsPublic() {
        user.setIsProfilePublic(true);
        Page<FollowedPerson> page = new PageImpl<>(List.of(buildFollowedPerson("603")));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followedPersonRepository.findByUserId(eq(userId), any(PageRequest.class))).thenReturn(page);

        Page<String> result = followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 10);

        assertThat(result.getContent()).containsExactly("603");
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Empty Page - When Target Follows No One")
    void shouldReturnEmptyPageWhenTargetFollowsNoOne() {
        user.setIsProfilePublic(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followedPersonRepository.findByUserId(eq(userId), any(PageRequest.class))).thenReturn(Page.empty());

        Page<String> result = followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Throw NotFoundException - When Target User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenTargetUserDoesNotExist() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(followedPersonRepository, followerRepository);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Page - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnPageWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() {
        user.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, userId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(followedPersonRepository.findByUserId(eq(userId), any(PageRequest.class))).thenReturn(Page.empty());

        assertThat(followedPersonService.getFollowedPeople(viewerId, userId, 1, 10).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        user.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, userId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> followedPersonService.getFollowedPeople(viewerId, userId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(followedPersonRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Page - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnPageWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselves() {
        user.setIsProfilePublic(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followedPersonRepository.findByUserId(eq(userId), any(PageRequest.class))).thenReturn(Page.empty());

        assertThat(followedPersonService.getFollowedPeople(userId, userId, 1, 10).getContent()).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, null, 10);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 0, 10);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 3, 10);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(followedPersonRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, null);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 1001);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 25);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubFollowedPeopleEmptyPage();

        followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 1000);

        verify(followedPersonRepository).findByUserId(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(followedPersonRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> followedPersonService.getFollowedPeople(UUID.randomUUID(), userId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(followedPersonRepository, never()).findByUserId(any(), any());
    }

    private void stubFollowedPeopleEmptyPage() {
        user.setIsProfilePublic(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followedPersonRepository.findByUserId(any(), any(PageRequest.class))).thenReturn(Page.empty());
    }
}
