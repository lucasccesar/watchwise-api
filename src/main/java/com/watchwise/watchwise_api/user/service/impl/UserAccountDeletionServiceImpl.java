package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.UserListStructureLock;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.like.repository.LikeAccountDeletionOperations.Target;
import com.watchwise.watchwise_api.like.repository.LikeAccountDeletionOperations.TargetType;
import com.watchwise.watchwise_api.platformimport.service.ImportUploadQuotaService;
import com.watchwise.watchwise_api.report.service.ReportReporterDeletionService;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserAccountDeletionService;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserAccountDeletionServiceImpl implements UserAccountDeletionService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdvisoryLock advisoryLock;
    private final ImportUploadQuotaService importUploadQuotaService;
    private final UserListRepository userListRepository;
    private final UserListItemRepository userListItemRepository;
    private final UserListItemService userListItemService;
    private final ReportReporterDeletionService reportReporterDeletionService;
    private final LikeRepository likeRepository;

    @Override
    @Transactional
    public void deleteAccount(UUID id, DeleteAccountDTO deleteAccountDTO) {
        User userSnapshot = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (!passwordEncoder.matches(deleteAccountDTO.password(), userSnapshot.getPassword())) {
            throw new UnauthorizedException("Invalid password");
        }

        advisoryLock.lock(UserListStructureLock.IDENTITY);
        importUploadQuotaService.prepareAccountDeletion(id);

        TreeSet<UUID> ownedListIds = userListRepository.findByUserId(id).stream()
                .map(UserList::getId)
                .collect(Collectors.toCollection(TreeSet::new));
        TreeSet<UUID> parentListIds = ownedListIds.isEmpty()
                ? new TreeSet<>()
                : new TreeSet<>(userListItemRepository.findDistinctParentListIdsByChildListIdIn(ownedListIds));
        List<UserList> parentSnapshots = parentListIds.isEmpty()
                ? List.of()
                : userListRepository.findByIdInWithUser(parentListIds);

        TreeSet<UUID> ownerIdsToLock = new TreeSet<>();
        ownerIdsToLock.add(id);
        Map<UUID, UUID> expectedOwnerByParentId = new HashMap<>();
        for (UserList parent : parentSnapshots) {
            expectedOwnerByParentId.put(parent.getId(), parent.getUser().getId());
            ownerIdsToLock.add(parent.getUser().getId());
        }

        User lockedUser = null;
        for (UUID ownerId : ownerIdsToLock) {
            User lockedOwner = userRepository.findByIdForListCreation(ownerId)
                    .orElseThrow(() -> new NotFoundException("User not found"));
            if (ownerId.equals(id)) {
                lockedUser = lockedOwner;
            }
        }

        if (!passwordEncoder.matches(deleteAccountDTO.password(), lockedUser.getPassword())) {
            throw new UnauthorizedException("Invalid password");
        }

        TreeSet<UUID> lockedOwnedListIds = userListRepository.findByUserId(id).stream()
                .map(UserList::getId)
                .collect(Collectors.toCollection(TreeSet::new));
        ownedListIds.addAll(lockedOwnedListIds);
        TreeSet<UUID> currentParentListIds = ownedListIds.isEmpty()
                ? new TreeSet<>()
                : new TreeSet<>(userListItemRepository.findDistinctParentListIdsByChildListIdIn(ownedListIds));
        if (!currentParentListIds.equals(parentListIds)) {
            throw new ConflictException("List structure changed while preparing account deletion");
        }

        TreeSet<UUID> affectedListIds = new TreeSet<>(ownedListIds);
        affectedListIds.addAll(parentListIds);
        // The actor's exclusive owner/lifecycle lock prevents new owned likes after this capture.
        // Disposable targets need no social locks/decrements; C06 still locks owned lists below.
        List<Target> likeTargets = likeRepository.findSurvivingAccountLikeTargets(id);
        // Merge social list targets into the C06 UUID order before locking any list row.
        TreeSet<UUID> listsToLock = new TreeSet<>(affectedListIds);
        likeTargets.stream()
                .filter(target -> target.type() == TargetType.LIST)
                .map(Target::id)
                .forEach(listsToLock::add);
        for (UUID listId : listsToLock) {
            if (!affectedListIds.contains(listId)) {
                likeRepository.lockLikeTarget(TargetType.LIST, listId);
                continue;
            }
            UserList affectedList = userListRepository.findByIdForUpdate(listId)
                    .orElseThrow(() -> new NotFoundException("List not found"));
            UUID expectedOwnerId = ownedListIds.contains(listId)
                    ? id
                    : expectedOwnerByParentId.get(listId);
            if (expectedOwnerId == null || !expectedOwnerId.equals(affectedList.getUser().getId())) {
                throw new ConflictException("List ownership changed while preparing account deletion");
            }
        }
        for (UUID listId : affectedListIds) {
            userListItemRepository.findByUserListIdForUpdateOrderByPositionAsc(listId);
        }

        likeRepository.lockAccountLikeTargets(likeTargets.stream()
                .filter(target -> target.type() != TargetType.LIST)
                .toList());
        userListItemService.removeItemsReferencingChildLists(ownedListIds);
        likeRepository.deleteAccountLikesAndReconcile(id, likeTargets);

        reportReporterDeletionService.removeReporterSubmissions(id);
        userRepository.delete(lockedUser);
    }

}
