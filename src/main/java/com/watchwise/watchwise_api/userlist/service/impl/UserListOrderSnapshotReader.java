package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListOrderQueryRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListOrderSnapshotReader {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 30;
    private static final UUID FIRST_CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String ITEMS_SCOPE = "items";

    private final UserListOrderQueryRepository orderQueries;
    private final UserListRepository userListRepository;
    private final UserListItemRepository userListItemRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemOrderSnapshot read(UUID userId, UUID listId, String cursor, Integer size) {
        int limit = effectivePageSize(size);
        UserList userList = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));
        if (!userList.getUser().getId().equals(userId)) throw new NotFoundException("List not found");
        Long revision = orderQueries.findListItemsRevision(userId, listId);
        if (revision == null) throw new NotFoundException("List not found");
        UserListOrderCursorCodec.Position after = UserListOrderCursorCodec.decode(cursor, ITEMS_SCOPE, listId);
        if (after != null && after.revision() != revision) {
            throw new ConflictException("Order changed while loading; restart the order view");
        }

        int afterPosition = after == null ? 0 : after.position();
        UUID afterId = after == null ? FIRST_CURSOR_ID : after.itemId();
        List<UserListItem> rows = userListItemRepository.findOrderBatch(
                listId, afterPosition, afterId, PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        List<UserListItem> page = hasNext ? rows.subList(0, limit) : rows;

        Set<UUID> childIds = page.stream()
                .map(UserListItem::getChildList)
                .filter(java.util.Objects::nonNull)
                .map(UserList::getId)
                .collect(Collectors.toSet());
        Set<UUID> visibleChildIds = childIds.isEmpty() ? Set.of()
                : Set.copyOf(userListItemRepository.findVisibleChildListIdsByIdIn(childIds, userId));
        List<Item> items = page.stream().map(row -> {
            if (row.getContent() != null) {
                var content = row.getContent();
                var coordinate = ContentCoordinate.from(content);
                return new Item(row.getId(), row.getPosition(),
                        new ContentCardSpec(coordinate, null, null, null,
                                content.getRuntimeMinutes(), content.getGenres()), null, true);
            }
            UserList child = row.getChildList();
            boolean visible = child != null && visibleChildIds.contains(child.getId());
            return new Item(row.getId(), row.getPosition(), null,
                    visible ? child.getName() : null, visible);
        }).toList();
        User viewer = userRepository.findById(userId).orElse(null);
        String nextCursor = hasNext
                ? UserListOrderCursorCodec.encode(ITEMS_SCOPE, listId, revision,
                        page.getLast().getPosition(), page.getLast().getId())
                : null;
        return new ItemOrderSnapshot(revision, userId,
                viewer == null ? "en-US" : viewer.getPreferredLanguage(),
                viewer == null ? "US" : viewer.getPreferredRegion(), items, nextCursor);
    }

    private int effectivePageSize(Integer size) {
        int result = size == null ? DEFAULT_PAGE_SIZE : size;
        if (result < 1 || result > MAX_PAGE_SIZE) {
            throw new BadRequestException("Order page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return result;
    }

    public record ItemOrderSnapshot(
            long revision,
            UUID viewerId,
            String language,
            String region,
            List<Item> items,
            String nextCursor) {
        public ItemOrderSnapshot {
            items = List.copyOf(items);
        }
    }

    public record Item(
            UUID id,
            Integer position,
            ContentCardSpec contentSpec,
            String label,
            boolean movable) {
    }
}
