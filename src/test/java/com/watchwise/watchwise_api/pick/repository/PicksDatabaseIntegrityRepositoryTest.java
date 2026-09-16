package com.watchwise.watchwise_api.pick.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PicksDatabaseIntegrityRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long nextContentTmdbId = 1;

    @Test
    @DisplayName("[insertTemplate] Should Reject Incomplete Dates - When Only Start Is Present")
    void shouldRejectStartWithoutEnd() {
        assertThatThrownBy(() -> insertTemplate(null, LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_picks_templates_dates");
    }

    @Test
    @DisplayName("[insertTemplate] Should Reject Incomplete Dates - When Only End Is Present")
    void shouldRejectEndWithoutStart() {
        assertThatThrownBy(() -> insertTemplate(null, null, LocalDate.of(2026, 12, 31)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_picks_templates_dates");
    }

    @Test
    @DisplayName("[picks_templates] Should Reject An Eligibility End Date Before Its Start Date")
    void shouldRejectEligibilityEndDateBeforeStartDate() {
        assertThatThrownBy(() -> insertTemplate(null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_picks_templates_dates");
    }

    @Test
    @DisplayName("[picks_template_options] Should Reject Missing Target")
    void shouldRejectOptionWithoutTarget() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));

        assertThatThrownBy(() -> insertOption(categoryId, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_picks_template_options_one_target");
    }

    @Test
    @DisplayName("[picks_template_options] Should Reject Content And Person Targets Together")
    void shouldRejectOptionWithBothTargets() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));
        UUID contentId = insertContent();

        assertThatThrownBy(() -> insertOption(categoryId, contentId, "287", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_picks_template_options_one_target");
    }

    @Test
    @DisplayName("[picks_template_options] Should Reject Context Content For A Content Target")
    void shouldRejectOptionContextWithoutPersonTarget() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));
        UUID contentId = insertContent();
        UUID contextContentId = insertContent();

        assertThatThrownBy(() -> insertOption(categoryId, contentId, null, contextContentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_picks_template_options_context_is_person");
    }

    @Test
    @DisplayName("[picks_template_options] Should Reject Duplicate Standalone Person Target")
    void shouldRejectDuplicateStandalonePersonOption() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));
        insertOption(categoryId, null, "287", null);

        assertThatThrownBy(() -> insertOption(categoryId, null, "287", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_picks_template_options_fixed_target");
    }

    @Test
    @DisplayName("[picks_template_options] Should Reject Duplicate Contextual Person Target")
    void shouldRejectDuplicateContextualPersonOption() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));
        UUID contextContentId = insertContent();
        insertOption(categoryId, null, "287", contextContentId);

        assertThatThrownBy(() -> insertOption(categoryId, null, "287", contextContentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_picks_template_options_fixed_target");
    }

    @Test
    @DisplayName("[pick_selections] Should Reject Missing Target")
    void shouldRejectSelectionWithoutTarget() {
        PickFixture fixture = insertPickFixture();

        assertThatThrownBy(() -> insertSelection(fixture.pickId(), fixture.categoryId(), null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pick_selections_one_target");
    }

    @Test
    @DisplayName("[pick_selections] Should Reject Content And Person Targets Together")
    void shouldRejectSelectionWithBothTargets() {
        PickFixture fixture = insertPickFixture();
        UUID contentId = insertContent();

        assertThatThrownBy(() -> insertSelection(fixture.pickId(), fixture.categoryId(), contentId, "287", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pick_selections_one_target");
    }

    @Test
    @DisplayName("[pick_selections] Should Reject Context Content For A Content Target")
    void shouldRejectSelectionContextWithoutPersonTarget() {
        PickFixture fixture = insertPickFixture();
        UUID contentId = insertContent();
        UUID contextContentId = insertContent();

        assertThatThrownBy(() -> insertSelection(fixture.pickId(), fixture.categoryId(), contentId, null, contextContentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pick_selections_context_is_person");
    }

    @Test
    @DisplayName("[picks_templates.creator_id] Should Set Creator To Null When Creator Is Deleted")
    void shouldSetTemplateCreatorToNullWhenCreatorIsDeleted() {
        UUID creatorId = insertUser();
        UUID templateId = insertTemplate(creatorId, null, null);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);

        assertThat(jdbcTemplate.queryForObject("SELECT creator_id FROM picks_templates WHERE id = ?", UUID.class, templateId))
                .isNull();
    }

    @Test
    @DisplayName("[picks_template_categories] Should Cascade Categories And Options When Template Is Deleted")
    void shouldCascadeCategoriesAndOptionsWhenTemplateIsDeleted() {
        UUID templateId = insertTemplate(null, null, null);
        UUID categoryId = insertCategory(templateId);
        UUID optionId = insertOption(categoryId, null, "287", null);

        jdbcTemplate.update("DELETE FROM picks_templates WHERE id = ?", templateId);

        assertThat(count("picks_template_categories", categoryId)).isZero();
        assertThat(count("picks_template_options", optionId)).isZero();
    }

    @Test
    @DisplayName("[picks.picks_template_id] Should Restrict Template Deletion When A Pick Exists")
    void shouldRestrictTemplateDeletionWhenPickExists() {
        PickFixture fixture = insertPickFixture();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM picks_templates WHERE id = ?", fixture.templateId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[picks.user_id] Should Cascade Picks When User Is Deleted")
    void shouldCascadePicksWhenUserIsDeleted() {
        PickFixture fixture = insertPickFixture();

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", fixture.userId());

        assertThat(count("picks", fixture.pickId())).isZero();
    }

    @Test
    @DisplayName("[pick_selections.pick_id] Should Cascade Selections When Pick Is Deleted")
    void shouldCascadeSelectionsWhenPickIsDeleted() {
        PickFixture fixture = insertPickFixture();
        UUID selectionId = insertSelection(fixture.pickId(), fixture.categoryId(), null, "287", null);

        jdbcTemplate.update("DELETE FROM picks WHERE id = ?", fixture.pickId());

        assertThat(count("pick_selections", selectionId)).isZero();
    }

    @Test
    @DisplayName("[pick_selections.category_id] Should Restrict Category Deletion When A Selection Exists")
    void shouldRestrictCategoryDeletionWhenSelectionExists() {
        PickFixture fixture = insertPickFixture();
        insertSelection(fixture.pickId(), fixture.categoryId(), null, "287", null);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM picks_template_categories WHERE id = ?", fixture.categoryId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[picks_template_options.content_id] Should Restrict Content Deletion When An Option References It")
    void shouldRestrictContentDeletionWhenOptionReferencesIt() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));
        UUID contentId = insertContent();
        insertOption(categoryId, contentId, null, null);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM contents WHERE id = ?", contentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[pick_selections.content_id] Should Restrict Content Deletion When A Selection References It")
    void shouldRestrictContentDeletionWhenSelectionReferencesIt() {
        PickFixture fixture = insertPickFixture();
        UUID contentId = insertContent();
        insertSelection(fixture.pickId(), fixture.categoryId(), contentId, null, null);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM contents WHERE id = ?", contentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[picks_template_options.context_content_id] Should Restrict Context Content Deletion")
    void shouldRestrictOptionContextContentDeletion() {
        UUID categoryId = insertCategory(insertTemplate(null, null, null));
        UUID contextContentId = insertContent();
        insertOption(categoryId, null, "287", contextContentId);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM contents WHERE id = ?", contextContentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[pick_selections.context_content_id] Should Restrict Context Content Deletion")
    void shouldRestrictSelectionContextContentDeletion() {
        PickFixture fixture = insertPickFixture();
        UUID contextContentId = insertContent();
        insertSelection(fixture.pickId(), fixture.categoryId(), null, "6384", contextContentId);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM contents WHERE id = ?", contextContentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertTemplate(UUID creatorId, LocalDate startDate, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO picks_templates (id, creator_id, origin, name, eligibility_start_date, eligibility_end_date, created_at, updated_at)
                VALUES (?, ?, 'OFFICIAL', ?, ?, ?, ?, ?)
                """, id, creatorId, "Template " + id, startDate, endDate, now, now);
        return id;
    }

    private UUID insertCategory(UUID templateId) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO picks_template_categories (id, picks_template_id, name, category_group, display_order, allowed_type, option_mode, created_at, updated_at)
                VALUES (?, ?, ?, 'PRIMARY', 1, 'PERSON', 'FIXED', ?, ?)
                """, id, templateId, "Category " + id, now, now);
        return id;
    }

    private UUID insertOption(UUID categoryId, UUID contentId, String personTmdbId, UUID contextContentId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO picks_template_options (id, category_id, content_id, person_tmdb_id, context_content_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, categoryId, contentId, personTmdbId, contextContentId, LocalDateTime.now());
        return id;
    }

    private PickFixture insertPickFixture() {
        UUID userId = insertUser();
        UUID templateId = insertTemplate(null, null, null);
        UUID categoryId = insertCategory(templateId);
        UUID pickId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO picks (id, picks_template_id, user_id, visibility, created_at, updated_at)
                VALUES (?, ?, ?, 'PUBLIC', ?, ?)
                """, pickId, templateId, userId, now, now);
        return new PickFixture(userId, templateId, categoryId, pickId);
    }

    private UUID insertSelection(UUID pickId, UUID categoryId, UUID contentId, String personTmdbId, UUID contextContentId) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO pick_selections (id, pick_id, category_id, content_id, person_tmdb_id, context_content_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, pickId, categoryId, contentId, personTmdbId, contextContentId, now, now);
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO users (id, role, username, email, password, profile_picture, is_profile_public, is_email_verified, created_at, updated_at, preferred_language, preferred_region)
                VALUES (?, 'USER', ?, ?, 'hash', 'https://example.com/avatar.png', true, true, ?, ?, 'en-US', 'US')
                """, id, "user_" + id, id + "@example.com", now, now);
        return id;
    }

    private UUID insertContent() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String tmdbId = Long.toString(nextContentTmdbId++);
        jdbcTemplate.update("""
                INSERT INTO contents (id, tmdb_id, type, created_at, updated_at)
                VALUES (?, ?, 'MOVIE', ?, ?)
                """, id, tmdbId, now, now);
        return id;
    }

    private int count(String tableName, UUID id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName + " WHERE id = ?", Integer.class, id);
    }

    private record PickFixture(UUID userId, UUID templateId, UUID categoryId, UUID pickId) {
    }
}
