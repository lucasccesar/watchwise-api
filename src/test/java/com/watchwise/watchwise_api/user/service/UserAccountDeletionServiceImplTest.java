package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.like.repository.LikeAccountDeletionOperations.Target;
import com.watchwise.watchwise_api.like.repository.LikeAccountDeletionOperations.TargetType;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.platformimport.service.ImportUploadQuotaService;
import com.watchwise.watchwise_api.report.service.ReportReporterDeletionService;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.repository.*;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class UserAccountDeletionServiceImplTest {
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AdvisoryLock advisoryLock;
    @Mock ImportUploadQuotaService importUploadQuotaService;
    @Mock UserListRepository userListRepository;
    @Mock UserListItemRepository userListItemRepository;
    @Mock UserListItemService userListItemService;
    @Mock ReportReporterDeletionService reportReporterDeletionService;
    @InjectMocks UserAccountDeletionServiceImpl userService;
    User savedUser = User.builder().id(UUID.randomUUID()).password("hash").build();
    @Mock
    private LikeRepository accountLikes;


    @Test
    void mergesLikedListsIntoC06LockOrderBeforeRemovingAnyActorLikes() {
        UUID id = savedUser.getId();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ownedId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID last = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var owned = UserList.builder().id(ownedId).user(savedUser).build();
        var comment = new Target(
                TargetType.COMMENT, UUID.randomUUID());
        var listType = TargetType.LIST;
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.findByIdForListCreation(id)).thenReturn(Optional.of(savedUser));
        when(userListRepository.findByUserId(id)).thenReturn(List.of(owned));
        when(userListRepository.findByIdForUpdate(ownedId)).thenReturn(Optional.of(owned));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        when(accountLikes.findAccountLikeTargets(id)).thenReturn(List.of(comment,
                new Target(listType, last),
                new Target(listType, first)));

        userService.deleteAccount(id, new DeleteAccountDTO("Password123"));

        var order = inOrder(userRepository, accountLikes, userListRepository, userListItemService);
        order.verify(userRepository).findByIdForListCreation(id);
        order.verify(accountLikes).findAccountLikeTargets(id);
        order.verify(accountLikes).lockLikeTarget(listType, first);
        order.verify(userListRepository).findByIdForUpdate(ownedId);
        order.verify(accountLikes).lockLikeTarget(listType, last);
        order.verify(accountLikes).lockAccountLikeTargets(List.of(comment));
        order.verify(userListItemService).removeItemsReferencingChildLists(anyCollection());
        order.verify(accountLikes).deleteAccountLikesAndReconcile(id);
        order.verify(userRepository).delete(savedUser);
    }

    @Test
    void propagatesCounterDivergenceAndDoesNotCascadeUserDeletion() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.findByIdForListCreation(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("uncovered counter"))
                .when(accountLikes).deleteAccountLikesAndReconcile(id);

        assertThatThrownBy(() -> userService.deleteAccount(id, new DeleteAccountDTO("Password123")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(userRepository, never()).delete(any());
        verifyNoInteractions(reportReporterDeletionService);
    }
    @Test
    void accountDeletionReconcilesRemovedLikesBeforeDeletingUser() {
        UUID id = savedUser.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.findByIdForListCreation(id)).thenReturn(Optional.of(savedUser));
        when(userListRepository.findByUserId(id)).thenReturn(List.of());
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);

        userService.deleteAccount(id, new DeleteAccountDTO("Password123"));

        InOrder order = inOrder(accountLikes, userRepository);
        order.verify(accountLikes).deleteAccountLikesAndReconcile(id);
        order.verify(userRepository).delete(savedUser);
    }

    @Test
    @DisplayName("[deleteAccount] Should Delete User - When Password Matches")
    void shouldDeleteUserWhenPasswordMatches() {
        UUID id = savedUser.getId();
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(userRepository.findByIdForListCreation(id)).thenReturn(Optional.of(savedUser));
        when(userListRepository.findByUserId(id)).thenReturn(List.of());
        when(passwordEncoder.matches("Password123", savedUser.getPassword())).thenReturn(true);

        userService.deleteAccount(id, deleteAccountDTO);

        verify(importUploadQuotaService).prepareAccountDeletion(id);
        verify(userRepository).delete(savedUser);
        verify(reportReporterDeletionService).removeReporterSubmissions(id);
        InOrder inOrder = inOrder(advisoryLock, importUploadQuotaService, userRepository, reportReporterDeletionService);
        inOrder.verify(advisoryLock).lock(com.watchwise.watchwise_api.common.transaction.UserListStructureLock.IDENTITY);
        inOrder.verify(importUploadQuotaService).prepareAccountDeletion(id);
        inOrder.verify(userRepository).findByIdForListCreation(id);
        inOrder.verify(reportReporterDeletionService).removeReporterSubmissions(id);
    }

    @Test
    @DisplayName("[deleteAccount] Should Throw UnauthorizedException - When Password Does Not Match")
    void shouldThrowUnauthorizedExceptionWhenDeleteAccountPasswordDoesNotMatch() {
        UUID id = savedUser.getId();
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("WrongPassword123");
        when(userRepository.findById(id)).thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("WrongPassword123", savedUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteAccount(id, deleteAccountDTO))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid password");

        verifyNoInteractions(importUploadQuotaService);
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteAccount] Should Throw NotFoundException - When Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenDeletingAccountThatDoesNotExist() {
        UUID id = UUID.randomUUID();
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteAccount(id, deleteAccountDTO))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(importUploadQuotaService);
        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).delete(any());
    }

}
