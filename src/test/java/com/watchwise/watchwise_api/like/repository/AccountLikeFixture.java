package com.watchwise.watchwise_api.like.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Raw fixtures deliberately keep counts independent of the production mutation code. */
public final class AccountLikeFixture {
    public record Target(String table, String column, UUID id) {}
    public final JdbcTemplate jdbc;
    public final UUID actor = UUID.randomUUID(), owner = UUID.randomUUID(), keeper = UUID.randomUUID();
    public final UUID content = UUID.randomUUID();
    public final List<Target> targets = new ArrayList<>();
    public AccountLikeFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void seed() {
        for (UUID user : List.of(actor, owner, keeper)) {
            jdbc.update("INSERT INTO users(id,username,email,password,profile_picture,name) VALUES (?,?,?,'hash','','Fixture')",
                    user, "u" + user.toString().replace("-", "").substring(0, 18), user + "@example.test");
        }
        jdbc.update("INSERT INTO contents(id,tmdb_id,type) VALUES (?,?,'MOVIE')", content, content.toString().substring(0, 18));
        UUID list = UUID.randomUUID(), comment = UUID.randomUUID(), diary = UUID.randomUUID(), dropped = UUID.randomUUID();
        UUID pick = UUID.randomUUID(), template = UUID.randomUUID();
        jdbc.update("INSERT INTO user_lists(id,user_id,name,visibility,rank,likes_count) VALUES (?,?,'Liked','PUBLIC',1,2)", list, owner);
        jdbc.update("INSERT INTO comments(id,user_id,content_id,text,likes_count) VALUES (?,?,?,'Comment',2)", comment, owner, content);
        jdbc.update("INSERT INTO diary_entries(id,user_id,content_id,watch_number,likes_count) VALUES (?,?,?,1,2)", diary, owner, content);
        jdbc.update("INSERT INTO dropped_entries(id,user_id,content_id,type,likes_count) VALUES (?,?,?,'MOVIE',2)", dropped, owner, content);
        jdbc.update("INSERT INTO picks_templates(id,creator_id,origin,name,created_at,updated_at,likes_count) VALUES (?,?,'COMMUNITY','Template',now(),now(),2)", template, owner);
        jdbc.update("INSERT INTO picks(id,user_id,picks_template_id,visibility,created_at,updated_at,likes_count) VALUES (?,?,?,'PUBLIC',now(),now(),2)", pick, owner, template);
        targets.addAll(List.of(new Target("user_lists", "list_id", list), new Target("comments", "comment_id", comment),
                new Target("diary_entries", "diary_entry_id", diary), new Target("dropped_entries", "dropped_entry_id", dropped),
                new Target("picks", "pick_id", pick), new Target("picks_templates", "picks_template_id", template)));
        for (Target target : targets) for (UUID liker : List.of(actor, keeper)) addLike(liker, target);
    }

    public void addLike(UUID liker, Target target) {
        jdbc.update("INSERT INTO likes(id,user_id," + target.column + ") VALUES (?,?,?)", UUID.randomUUID(), liker, target.id);
    }

    public void assertCounts(int expected) {
        for (Target target : targets) {
            assertThat(jdbc.queryForObject("SELECT likes_count FROM " + target.table + " WHERE id = ?", Integer.class, target.id))
                    .as(target.table + " stored count").isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes WHERE " + target.column + " = ?", Long.class, target.id))
                    .as(target.table + " live likes").isEqualTo(expected);
        }
    }
}
