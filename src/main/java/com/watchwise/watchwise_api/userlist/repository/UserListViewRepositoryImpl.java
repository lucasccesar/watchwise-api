package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.userlist.dto.*;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class UserListViewRepositoryImpl implements UserListViewRepository {
    private final EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public Page<UserList> findPage(UserListViewQuery request) {
        validate(request);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewerId", request.viewerId());
        String where = predicates(request, params);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM user_lists ul WHERE " + where);
        bind(count, params);
        long total = ((Number) count.getSingleResult()).longValue();
        if (total == 0) return new PageImpl<>(java.util.List.of(), request.pageable(), 0);

        Query select = entityManager.createNativeQuery(
                "SELECT ul.* FROM user_lists ul WHERE " + where + orderBy(request), UserList.class);
        bind(select, params);
        select.setFirstResult(Math.toIntExact(request.pageable().getOffset()));
        select.setMaxResults(request.pageable().getPageSize());
        return new PageImpl<>(select.getResultList(), request.pageable(), total);
    }

    private void bind(Query query, Map<String, Object> params) {
        query.getParameters().forEach(parameter -> query.setParameter(
                parameter.getName(), params.get(parameter.getName())));
    }

    private void validate(UserListViewQuery request) {
        if (request == null || request.viewerId() == null || request.mode() == null || request.pageable() == null)
            throw new IllegalArgumentException("Viewer, mode and pageable are required");
        switch (request.mode()) {
            case MY -> {
                if (request.ownerId() != null || request.source() == null)
                    throw new IllegalArgumentException("MY requires source and rejects ownerId");
            }
            case USER -> {
                if (request.ownerId() == null || request.source() != null)
                    throw new IllegalArgumentException("USER requires ownerId and rejects source");
            }
            case DISCOVER -> {
                if (request.ownerId() != null || request.source() != null)
                    throw new IllegalArgumentException("DISCOVER rejects ownerId and source");
            }
        }
    }

    private String predicates(UserListViewQuery request, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("1=1");
        switch (request.mode()) {
            case MY -> {
                switch (request.source()) {
                    case OWNED -> where.append(" AND ul.user_id = :viewerId");
                    case LIKED -> where.append(" AND EXISTS (SELECT 1 FROM likes vl WHERE vl.user_id = :viewerId AND vl.list_id = ul.id)");
                    case ALL -> where.append(" AND (ul.user_id = :viewerId OR EXISTS (SELECT 1 FROM likes vl WHERE vl.user_id = :viewerId AND vl.list_id = ul.id))");
                }
            }
            case USER -> {
                params.put("ownerId", request.ownerId());
                where.append(" AND ul.user_id = :ownerId")
                        .append(" AND (ul.user_id = :viewerId OR EXISTS (SELECT 1 FROM users profile WHERE profile.id = :ownerId AND profile.is_profile_public = true) OR EXISTS (SELECT 1 FROM followers f WHERE f.follower_id = :viewerId AND f.followed_id = :ownerId AND f.status = 'ACCEPTED'))")
                        .append(" AND (ul.user_id = :viewerId OR ul.visibility = 'PUBLIC' OR (ul.visibility = 'FOLLOWERS' AND EXISTS (SELECT 1 FROM followers f WHERE f.follower_id = :viewerId AND f.followed_id = :ownerId AND f.status = 'ACCEPTED')))");
            }
            case DISCOVER -> where.append(" AND ul.visibility = 'PUBLIC'");
        }
        if (request.query() != null && !request.query().isBlank()) {
            where.append(" AND LOWER(ul.name) LIKE '%' || LOWER(:nameQuery) || '%' ESCAPE '\\'");
            params.put("nameQuery", request.query());
        }
        boolean directType = request.itemType() != null && request.itemType() != UserListViewItemType.LIST;
        boolean hasGenre = request.genre() != null && !request.genre().isBlank();
        if (request.itemType() != null) {
            if (request.itemType() == UserListViewItemType.LIST) {
                where.append(" AND EXISTS (SELECT 1 FROM user_list_items item WHERE item.user_list_id = ul.id AND item.child_list_id IS NOT NULL)");
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM user_list_items item JOIN contents c ON c.id = item.content_id WHERE item.user_list_id = ul.id AND c.type = :itemType");
                if (hasGenre) where.append(" AND EXISTS (SELECT 1 FROM unnest(c.genres) genre WHERE LOWER(genre) = LOWER(:genre))");
                where.append(')');
                params.put("itemType", request.itemType().name());
            }
        }
        if (hasGenre && !directType) {
            where.append(" AND EXISTS (SELECT 1 FROM user_list_items item JOIN contents c ON c.id = item.content_id CROSS JOIN LATERAL unnest(c.genres) genre WHERE item.user_list_id = ul.id AND LOWER(genre) = LOWER(:genre))");
        }
        if (hasGenre) params.put("genre", request.genre().trim());
        return where.toString();
    }

    private String orderBy(UserListViewQuery request) {
        if (request.sortBy() == null) {
            return switch (request.mode()) {
                case DISCOVER -> " ORDER BY ul.likes_count DESC, ul.created_at DESC, ul.id DESC";
                case USER -> " ORDER BY ul.created_at DESC, ul.id DESC";
                case MY -> switch (request.source()) {
                    case OWNED -> " ORDER BY ul.rank ASC NULLS LAST, ul.created_at DESC, ul.id DESC";
                    case LIKED -> " ORDER BY (SELECT vl.created_at FROM likes vl WHERE vl.user_id = :viewerId AND vl.list_id = ul.id) DESC, (SELECT vl.id FROM likes vl WHERE vl.user_id = :viewerId AND vl.list_id = ul.id) DESC";
                    case ALL -> " ORDER BY ul.updated_at DESC, ul.id DESC";
                };
            };
        }
        String expression = switch (request.sortBy()) {
            case NAME -> "LOWER(ul.name)";
            case LIKES_COUNT -> "ul.likes_count";
            case ITEMS_COUNT -> "(SELECT COUNT(*) FROM user_list_items item WHERE item.user_list_id = ul.id)";
            case COMMENTS_COUNT -> "(SELECT COUNT(*) FROM comments c WHERE c.list_id = ul.id)";
            case UPDATED_AT -> "ul.updated_at";
            case RANK -> "ul.rank";
        };
        String direction = request.direction() == Sort.Direction.DESC ? " DESC" : " ASC";
        return " ORDER BY " + expression + direction + " NULLS LAST, ul.id DESC";
    }
}
