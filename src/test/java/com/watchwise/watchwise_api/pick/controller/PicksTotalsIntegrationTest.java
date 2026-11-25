package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PicksTotalsIntegrationTest extends PicksDomainIntegrationSupport {
    private static final String TOTALS = "/users/me/picks/totals";

    @Autowired UserBlockRepository blocks;

    @DynamicPropertySource
    static void inspectSql(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", SqlCapture.class::getName);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(TOTALS)).andExpect(status().isUnauthorized());
    }

    @Test
    void matchesTheLegacyPagesForPrivatePicksBlockedCreatorsAndMultiplePicksPerTemplate() throws Exception {
        var ownFixture = template();
        personPick(ownFixture, PickVisibility.PRIVATE);
        var ownTemplate = templates.findById(ownFixture.id()).orElseThrow();
        savePick(owner, ownTemplate);
        var outboundTemplate = saveTemplate(other, PickOrigin.COMMUNITY);
        var inboundTemplate = saveTemplate(admin, PickOrigin.OFFICIAL);
        savePick(owner, outboundTemplate);
        savePick(owner, inboundTemplate);
        savePick(owner, saveTemplate(null, PickOrigin.OFFICIAL));
        savePick(other, outboundTemplate);
        block(owner, other);
        block(admin, owner);

        assertTotals(owner, 3, 1);
        assertTotals(other, 1, 1);
        assertTotals(admin, 0, 1);

        clearInvocations(tmdb, tmdbRestClient);
        List<String> queries = new ArrayList<>();
        SqlCapture.statements.set(queries);
        try {
            mvc.perform(authenticated(owner, HttpMethod.GET, TOTALS, ""))
                    .andExpect(status().isOk());
        } finally {
            SqlCapture.statements.remove();
        }
        List<String> picksQueries = queries.stream()
                .filter(sql -> sql.matches("(?s).*\\b(from|join)\\s+picks(?:_\\w+)?\\b.*"))
                .toList();
        assertThat(picksQueries).hasSize(2).allSatisfy(sql -> {
            assertThat(sql).contains("count(");
            assertThat(sql).doesNotContain(" order by ", " fetch first ", " limit ", "pick_selections",
                    "picks_template_categories");
        });
        verifyNoInteractions(tmdb, tmdbRestClient);
    }

    private void assertTotals(User viewer, int picksCount, int templatesCount) throws Exception {
        mvc.perform(authenticated(viewer, HttpMethod.GET, "/users/me/picks/view?page=1&size=1", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(picksCount));
        mvc.perform(authenticated(viewer, HttpMethod.GET, "/users/me/picks-templates/view?page=1&size=1", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(templatesCount));
        mvc.perform(authenticated(viewer, HttpMethod.GET, TOTALS, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picksCount").value(picksCount))
                .andExpect(jsonPath("$.templatesCount").value(templatesCount));
    }

    private PicksTemplate saveTemplate(User creator, PickOrigin origin) {
        var now = LocalDateTime.now();
        return templates.saveAndFlush(PicksTemplate.builder().creator(creator).origin(origin)
                .name("Synthetic template").createdAt(now).updatedAt(now).build());
    }

    private void savePick(User viewer, PicksTemplate template) {
        var now = LocalDateTime.now().minusDays(1);
        picks.saveAndFlush(Pick.builder().user(viewer).picksTemplate(template).visibility(PickVisibility.PUBLIC)
                .createdAt(now).updatedAt(now).build());
    }

    private void block(User blocker, User blocked) {
        blocks.saveAndFlush(UserBlock.builder().blocker(blocker).blocked(blocked)
                .createdAt(LocalDateTime.now()).build());
    }

    public static final class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            List<String> captured = statements.get();
            if (captured != null) captured.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }
    }
}
