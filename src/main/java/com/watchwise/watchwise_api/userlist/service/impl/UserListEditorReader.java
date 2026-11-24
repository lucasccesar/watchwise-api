package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.userlist.dto.UserListEditorDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListEditorDTO.Status;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserListEditorReader {
    private static final int MAX_EDITOR_ITEMS = 100;

    private final UserListRepository lists;
    private final UserListItemRepository items;
    private final FollowerRepository followers;
    private final UserBlockAccessService blocks;
    private final UserContentPosterService posters;
    private final ContentCardAssembler cards;

    @Transactional(readOnly = true)
    public UserListEditorDTO read(UUID viewerId, UUID listId) {
        UserList list = lists.findByIdWithUser(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));
        var owner = list.getUser();
        if (!owner.getId().equals(viewerId)) {
            assertVisibleToNonOwner(viewerId, list);
            return UserListEditorDTO.withoutItems(Status.READ_ONLY);
        }

        List<UserListItem> ordered = items.findEditorItems(listId, PageRequest.of(0, MAX_EDITOR_ITEMS + 1));
        if (ordered.size() > MAX_EDITOR_ITEMS || ordered.stream().anyMatch(item -> item.getChildList() != null)) {
            return UserListEditorDTO.withoutItems(Status.LEGACY);
        }
        if (ordered.isEmpty()) {
            return new UserListEditorDTO(Status.READY, list.getName(), list.getDescription(), list.getVisibility(),
                    null, List.of());
        }

        Map<ContentCoordinate, UUID> contentIds = new LinkedHashMap<>();
        Map<ContentCoordinate, ContentCardSpec> specs = new LinkedHashMap<>();
        for (UserListItem item : ordered) {
            var content = item.getContent();
            var coordinate = ContentCoordinate.from(content);
            contentIds.putIfAbsent(coordinate, content.getId());
            specs.putIfAbsent(coordinate,
                    new ContentCardSpec(coordinate, null, null, null, content.getRuntimeMinutes(), content.getGenres()));
        }
        var context = new ContentCardContext(owner.getPreferredLanguage(), owner.getPreferredRegion(), null, viewerId);
        var metadata = cards.assembleBasicMetadataWithKnownIds(specs.values(), context, contentIds);
        var customPosters = posters.findByUserAndContentIds(viewerId, contentIds.values());
        var scope = UserListItemScope.resolve(contentIds.keySet().stream().map(ContentCoordinate::type)
                .collect(Collectors.toSet()), false);
        var editorItems = ordered.stream().map(item -> toEditorItem(item, metadata, customPosters)).toList();
        return new UserListEditorDTO(Status.READY, list.getName(), list.getDescription(), list.getVisibility(),
                scope, editorItems);
    }

    private void assertVisibleToNonOwner(UUID viewerId, UserList list) {
        UUID ownerId = list.getUser().getId();
        blocks.assertContentVisible(viewerId, ownerId);
        if (list.getVisibility() == UserListVisibility.PUBLIC
                || (list.getVisibility() == UserListVisibility.FOLLOWERS
                    && followers.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED))) {
            return;
        }
        throw new ForbiddenException("This list is private");
    }

    private UserListEditorDTO.Item toEditorItem(
            UserListItem item, Map<ContentCoordinate, ContentCardDTO> metadata, Map<UUID, String> posters) {
        var coordinate = ContentCoordinate.from(item.getContent());
        var card = metadata.get(coordinate);
        var editorCard = new UserListEditorDTO.Card(
                coordinate.type(), coordinate.tmdbId(), coordinate.seriesTmdbId(),
                coordinate.seasonNumber(), coordinate.episodeNumber(),
                card == null ? null : card.title(),
                card == null ? null : card.releaseYear(),
                card == null ? null : card.posterPath());
        return new UserListEditorDTO.Item(item.getId(), item.getPosition(), editorCard,
                posters.get(item.getContent().getId()));
    }
}
