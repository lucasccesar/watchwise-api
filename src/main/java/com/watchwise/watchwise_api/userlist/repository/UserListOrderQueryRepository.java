package com.watchwise.watchwise_api.userlist.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class UserListOrderQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Long findUserListsRevision(UUID ownerId) {
        List<Long> revisions = jdbc.query("""
                SELECT user_list_order_revision
                FROM users
                WHERE id = :ownerId
                """, Map.of("ownerId", ownerId), (rs, row) -> rs.getLong(1));
        return revisions.stream().findFirst().orElse(null);
    }

    public Long findListItemsRevision(UUID ownerId, UUID listId) {
        List<Long> revisions = jdbc.query("""
                SELECT item_order_revision
                FROM user_lists
                WHERE id = :listId AND user_id = :ownerId
                """, Map.of("listId", listId, "ownerId", ownerId), (rs, row) -> rs.getLong(1));
        return revisions.stream().findFirst().orElse(null);
    }

    public List<ListOrderRow> findListOrderBatch(UUID ownerId, int afterPosition, UUID afterId, int limit) {
        return jdbc.query("""
                SELECT id, rank, name
                FROM user_lists
                WHERE user_id = :ownerId
                  AND (rank > :afterPosition OR (rank = :afterPosition AND id > :afterId))
                ORDER BY rank ASC, id ASC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("afterPosition", afterPosition)
                .addValue("afterId", afterId)
                .addValue("limit", limit),
                (rs, row) -> new ListOrderRow(rs.getObject("id", UUID.class), rs.getInt("rank"),
                        rs.getString("name")));
    }

    public Map<UUID, Integer> findListPositions(UUID ownerId, Collection<UUID> listIds) {
        if (listIds.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT id, rank
                FROM user_lists
                WHERE user_id = :ownerId AND id IN (:ids)
                """, new MapSqlParameterSource().addValue("ownerId", ownerId).addValue("ids", listIds),
                (rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getInt("rank")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<UUID, ItemOrderState> findItemPositions(UUID listId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT id, position, child_list_id
                FROM user_list_items
                WHERE user_list_id = :listId AND id IN (:ids)
                """, new MapSqlParameterSource().addValue("listId", listId).addValue("ids", itemIds),
                (rs, row) -> new ItemOrderState(rs.getObject("id", UUID.class), rs.getInt("position"),
                        rs.getObject("child_list_id", UUID.class)))
                .stream().collect(Collectors.toMap(ItemOrderState::id, Function.identity()));
    }

    public int countLists(UUID ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_lists WHERE user_id = :ownerId",
                Map.of("ownerId", ownerId), Integer.class);
    }

    public int countItems(UUID listId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_list_items WHERE user_list_id = :listId",
                Map.of("listId", listId), Integer.class);
    }

    public void parkList(UUID ownerId, UUID listId, int position) {
        jdbc.update("UPDATE user_lists SET rank = :position WHERE id = :listId AND user_id = :ownerId",
                Map.of("position", position, "listId", listId, "ownerId", ownerId));
    }

    public void parkListRange(UUID ownerId, int start, int end, int offset) {
        jdbc.update("""
                UPDATE user_lists SET rank = rank + :offset
                WHERE user_id = :ownerId AND rank BETWEEN :start AND :end
                """, new MapSqlParameterSource().addValue("ownerId", ownerId).addValue("start", start)
                .addValue("end", end).addValue("offset", offset));
    }

    public void settleListRange(UUID ownerId, int offset, int delta) {
        jdbc.update("""
                UPDATE user_lists SET rank = rank - :offset + :delta
                WHERE user_id = :ownerId AND rank > :offset
                """, new MapSqlParameterSource().addValue("ownerId", ownerId).addValue("offset", offset)
                .addValue("delta", delta));
    }

    public void setListPosition(UUID ownerId, UUID listId, int position) {
        jdbc.update("UPDATE user_lists SET rank = :position, updated_at = :updatedAt WHERE id = :listId AND user_id = :ownerId",
                Map.of("position", position, "updatedAt", LocalDateTime.now(), "listId", listId, "ownerId", ownerId));
    }

    public void parkItem(UUID listId, UUID itemId, int position) {
        jdbc.update("UPDATE user_list_items SET position = :position WHERE id = :itemId AND user_list_id = :listId",
                Map.of("position", position, "itemId", itemId, "listId", listId));
    }

    public void parkItemRange(UUID listId, int start, int end, int offset) {
        jdbc.update("""
                UPDATE user_list_items SET position = position + :offset
                WHERE user_list_id = :listId AND position BETWEEN :start AND :end
                """, new MapSqlParameterSource().addValue("listId", listId).addValue("start", start)
                .addValue("end", end).addValue("offset", offset));
    }

    public void settleItemRange(UUID listId, int offset, int delta) {
        jdbc.update("""
                UPDATE user_list_items SET position = position - :offset + :delta
                WHERE user_list_id = :listId AND position > :offset
                """, new MapSqlParameterSource().addValue("listId", listId).addValue("offset", offset)
                .addValue("delta", delta));
    }

    public void setItemPosition(UUID listId, UUID itemId, int position) {
        jdbc.update("UPDATE user_list_items SET position = :position, updated_at = :updatedAt WHERE id = :itemId AND user_list_id = :listId",
                Map.of("position", position, "updatedAt", LocalDateTime.now(), "itemId", itemId, "listId", listId));
    }

    public record ListOrderRow(UUID id, int position, String label) { }

    public record ItemOrderState(UUID id, int position, UUID childListId) { }
}
