package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.UserListStructureLock;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderItemDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderMoveDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderPageDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderRevisionDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListOrderSide;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListOrderQueryRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListOrderServiceImpl implements UserListOrderService {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 30;
    private static final int POSITION_PARK_OFFSET = 1_000_000_000;
    private static final UUID FIRST_CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String LIST_SCOPE = "lists";
    private static final String ITEMS_SCOPE = "items";
    private static final Set<ContentCardFieldSet> CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.SOCIAL_METADATA);

    private final UserListOrderQueryRepository orderQueries;
    private final UserListRepository userListRepository;
    private final UserListItemRepository userListItemRepository;
    private final UserRepository userRepository;
    private final AdvisoryLock advisoryLock;
    private final ContentCardAssembler contentCardAssembler;
    private final UserListOrderSnapshotReader snapshotReader;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserListOrderPageDTO getOwnedListOrder(UUID userId, String cursor, Integer size) {
        int limit = effectivePageSize(size);
        Long revision = orderQueries.findUserListsRevision(userId);
        if (revision == null) throw new NotFoundException("User not found");
        UserListOrderCursorCodec.Position after = UserListOrderCursorCodec.decode(cursor, LIST_SCOPE, userId);
        assertCurrentCursorRevision(after, revision);

        int afterPosition = after == null ? 0 : after.position();
        UUID afterId = after == null ? FIRST_CURSOR_ID : after.itemId();
        List<UserListOrderQueryRepository.ListOrderRow> rows =
                orderQueries.findListOrderBatch(userId, afterPosition, afterId, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<UserListOrderQueryRepository.ListOrderRow> page = hasNext ? rows.subList(0, limit) : rows;
        List<UserListOrderItemDTO> items = page.stream()
                .map(row -> new UserListOrderItemDTO(row.id(), row.position(), row.label(), null, true))
                .toList();
        String nextCursor = hasNext
                ? UserListOrderCursorCodec.encode(LIST_SCOPE, userId, revision,
                        page.getLast().position(), page.getLast().id())
                : null;
        return new UserListOrderPageDTO(Long.toString(revision), items, nextCursor);
    }

    @Override
    public UserListOrderPageDTO getListItemOrder(UUID userId, UUID listId, String cursor, Integer size) {
        UserListOrderSnapshotReader.ItemOrderSnapshot snapshot = snapshotReader.read(userId, listId, cursor, size);
        List<ContentCardSpec> specs = snapshot.items().stream()
                .map(UserListOrderSnapshotReader.Item::contentSpec)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = specs.isEmpty() ? Map.of()
                : contentCardAssembler.assemble(specs,
                        new ContentCardContext(snapshot.language(), snapshot.region(), snapshot.viewerId(), null),
                        CARD_FIELDS);
        List<UserListOrderItemDTO> items = snapshot.items().stream().map(item -> {
            if (item.contentSpec() == null) {
                return new UserListOrderItemDTO(item.id(), item.position(), item.label(), null, item.movable());
            }
            ContentCardDTO card = cards.get(item.contentSpec().coordinate());
            return new UserListOrderItemDTO(item.id(), item.position(), card == null ? null : card.title(),
                    card == null ? null : card.customPosterUrl() != null
                            ? card.customPosterUrl() : card.posterPath(), item.movable());
        }).toList();
        return new UserListOrderPageDTO(Long.toString(snapshot.revision()), items, snapshot.nextCursor());
    }

    @Override
    @Transactional
    public UserListOrderRevisionDTO reorderOwnedLists(UUID userId, UserListOrderPatchDTO patch) {
        userRepository.findByIdForListCreation(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        long currentRevision = currentListRevision(userId);
        assertExpectedRevision(patch.revision(), currentRevision);
        validateMoves(patch.moves());
        Map<UUID, Integer> positions = orderQueries.findListPositions(userId, referencedIds(patch.moves()));
        assertAllTargetsPresent(referencedIds(patch.moves()), positions.keySet(), "List move target was not found");

        int count = orderQueries.countLists(userId);
        try {
            for (UserListOrderMoveDTO move : patch.moves()) {
                Map<UUID, Integer> latest = orderQueries.findListPositions(userId,
                        Set.of(move.movedId(), move.anchorId()));
                Integer oldPosition = latest.get(move.movedId());
                Integer anchorPosition = latest.get(move.anchorId());
                if (oldPosition == null || anchorPosition == null) {
                    throw new BadRequestException("List move target was not found");
                }
                int targetPosition = targetPosition(oldPosition, anchorPosition, move.side());
                if (targetPosition != oldPosition) {
                    applyListMove(userId, move.movedId(), oldPosition, targetPosition, count);
                }
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Lists could not be reordered due to a concurrent update");
        }
        return new UserListOrderRevisionDTO(Long.toString(currentListRevision(userId)));
    }

    @Override
    @Transactional
    public UserListOrderRevisionDTO reorderListItems(UUID userId, UUID listId, UserListOrderPatchDTO patch) {
        advisoryLock.lock(UserListStructureLock.IDENTITY);
        UserList userList = userListRepository.findByIdForUpdate(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));
        if (!userList.getUser().getId().equals(userId)) throw new NotFoundException("List not found");

        long currentRevision = currentItemRevision(userId, listId);
        assertExpectedRevision(patch.revision(), currentRevision);
        validateMoves(patch.moves());
        Set<UUID> allTargetIds = referencedIds(patch.moves());
        Map<UUID, UserListOrderQueryRepository.ItemOrderState> initialStates =
                orderQueries.findItemPositions(listId, allTargetIds);
        assertAllTargetsPresent(allTargetIds, initialStates.keySet(), "List item move target was not found");
        assertNestedTargetsVisible(userId, initialStates.values());

        int count = orderQueries.countItems(listId);
        try {
            for (UserListOrderMoveDTO move : patch.moves()) {
                Map<UUID, UserListOrderQueryRepository.ItemOrderState> latest =
                        orderQueries.findItemPositions(listId, Set.of(move.movedId(), move.anchorId()));
                UserListOrderQueryRepository.ItemOrderState moved = latest.get(move.movedId());
                UserListOrderQueryRepository.ItemOrderState anchor = latest.get(move.anchorId());
                if (moved == null || anchor == null) {
                    throw new BadRequestException("List item move target was not found");
                }
                assertNestedTargetsVisible(userId, List.of(moved, anchor));
                assertNoInaccessibleNestedListBetween(userId, listId, moved.position(), anchor.position());
                int targetPosition = targetPosition(moved.position(), anchor.position(), move.side());
                if (targetPosition != moved.position()) {
                    applyItemMove(listId, move.movedId(), moved.position(), targetPosition, count);
                }
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("List items could not be reordered due to a concurrent update");
        }
        return new UserListOrderRevisionDTO(Long.toString(currentItemRevision(userId, listId)));
    }

    private void applyListMove(UUID userId, UUID movedId, int oldPosition, int newPosition, int count) {
        orderQueries.parkList(userId, movedId, count + 1);
        boolean movingForward = newPosition < oldPosition;
        int rangeStart = movingForward ? newPosition : oldPosition + 1;
        int rangeEnd = movingForward ? oldPosition - 1 : newPosition;
        int shiftDelta = movingForward ? 1 : -1;
        orderQueries.parkListRange(userId, rangeStart, rangeEnd, POSITION_PARK_OFFSET);
        orderQueries.settleListRange(userId, POSITION_PARK_OFFSET, shiftDelta);
        orderQueries.setListPosition(userId, movedId, newPosition);
    }

    private void applyItemMove(UUID listId, UUID movedId, int oldPosition, int newPosition, int count) {
        orderQueries.parkItem(listId, movedId, count + 1);
        boolean movingForward = newPosition < oldPosition;
        int rangeStart = movingForward ? newPosition : oldPosition + 1;
        int rangeEnd = movingForward ? oldPosition - 1 : newPosition;
        int shiftDelta = movingForward ? 1 : -1;
        orderQueries.parkItemRange(listId, rangeStart, rangeEnd, POSITION_PARK_OFFSET);
        orderQueries.settleItemRange(listId, POSITION_PARK_OFFSET, shiftDelta);
        orderQueries.setItemPosition(listId, movedId, newPosition);
    }

    private void assertNestedTargetsVisible(
            UUID viewerId, Collection<UserListOrderQueryRepository.ItemOrderState> states) {
        Set<UUID> childListIds = states.stream().map(UserListOrderQueryRepository.ItemOrderState::childListId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (childListIds.isEmpty()) return;
        Set<UUID> visibleChildIds = Set.copyOf(
                userListItemRepository.findVisibleChildListIdsByIdIn(childListIds, viewerId));
        if (!visibleChildIds.containsAll(childListIds)) {
            throw new BadRequestException("An inaccessible nested list cannot be used in an order move");
        }
    }

    private void assertNoInaccessibleNestedListBetween(
            UUID viewerId, UUID listId, int firstPosition, int secondPosition) {
        int lowerPosition = Math.min(firstPosition, secondPosition);
        int upperPosition = Math.max(firstPosition, secondPosition);
        if (upperPosition - lowerPosition <= 1) return;
        if (userListItemRepository.existsInaccessibleNestedListBetweenPositions(
                listId, viewerId, lowerPosition, upperPosition)) {
            throw new BadRequestException("List items cannot be moved across an inaccessible nested list");
        }
    }

    private int effectivePageSize(Integer size) {
        int result = size == null ? DEFAULT_PAGE_SIZE : size;
        if (result < 1 || result > MAX_PAGE_SIZE) {
            throw new BadRequestException("Order page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return result;
    }

    private int targetPosition(int oldPosition, int anchorPosition, UserListOrderSide side) {
        return side == UserListOrderSide.BEFORE
                ? anchorPosition - (oldPosition < anchorPosition ? 1 : 0)
                : anchorPosition + (oldPosition > anchorPosition ? 1 : 0);
    }

    private void validateMoves(List<UserListOrderMoveDTO> moves) {
        if (moves == null || moves.isEmpty() || moves.size() > 100) {
            throw new BadRequestException("Between 1 and 100 order moves are required");
        }
        if (moves.stream().anyMatch(move -> move == null || move.movedId() == null || move.anchorId() == null
                || move.side() == null || move.movedId().equals(move.anchorId()))) {
            throw new BadRequestException("Each order move requires distinct moved and anchor IDs and a side");
        }
        Set<UUID> movedIds = new HashSet<>();
        for (UserListOrderMoveDTO move : moves) {
            if (!movedIds.add(move.movedId())) {
                throw new BadRequestException("Each item can be moved at most once per order update");
            }
        }
    }

    private Set<UUID> referencedIds(List<UserListOrderMoveDTO> moves) {
        Set<UUID> ids = new HashSet<>();
        moves.forEach(move -> {
            ids.add(move.movedId());
            ids.add(move.anchorId());
        });
        return ids;
    }

    private void assertAllTargetsPresent(Set<UUID> requested, Set<UUID> found, String message) {
        if (!found.containsAll(requested)) throw new BadRequestException(message);
    }

    private void assertExpectedRevision(String rawExpected, long current) {
        long expected = UserListOrderCursorCodec.parseRevision(rawExpected);
        if (expected != current) throw new ConflictException("Order changed since it was loaded; reload before saving");
    }

    private void assertCurrentCursorRevision(UserListOrderCursorCodec.Position cursor, long current) {
        if (cursor != null && cursor.revision() != current) {
            throw new ConflictException("Order changed while loading; restart the order view");
        }
    }

    private long currentListRevision(UUID userId) {
        Long revision = orderQueries.findUserListsRevision(userId);
        if (revision == null) throw new NotFoundException("User not found");
        return revision;
    }

    private long currentItemRevision(UUID userId, UUID listId) {
        Long revision = orderQueries.findListItemsRevision(userId, listId);
        if (revision == null) throw new NotFoundException("List not found");
        return revision;
    }

    private UserList findOwnedList(UUID userId, UUID listId) {
        UserList list = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));
        if (!list.getUser().getId().equals(userId)) throw new NotFoundException("List not found");
        return list;
    }
}
