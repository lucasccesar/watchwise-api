package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonViewDTOTest {

    @Test
    @DisplayName("[PersonViewDTO] Should Preserve Credit Data And Defensively Copy Roles")
    void shouldPreserveCreditDataAndDefensivelyCopyRoles() {
        List<String> characters = new ArrayList<>(List.of("Narrator"));
        List<String> jobs = new ArrayList<>();
        ContentCardViewerStateDTO viewerState = new ContentCardViewerStateDTO(
                WatchStatus.UNWATCHED, null, null, null, null, null, false, false, null, null);
        PersonCreditViewDTO credit = new PersonCreditViewDTO(
                "550", MovieOrSeriesType.MOVIE, "Fight Club", "https://image.tmdb.org/t/p/w342/x.jpg",
                LocalDate.of(1999, 10, 15), characters, jobs, PersonParticipation.CAST,
                null, 42.0d, new ContentCardStatsDTO(8.2d, 12L, 4L, 3L), viewerState, false,
                ContentPreviewStatus.AVAILABLE);

        characters.add("Tyler Durden");

        assertThat(credit.creditedEpisodeCount()).isNull();
        assertThat(credit.viewerState().inWatchlist()).isFalse();
        assertThat(credit.characters()).containsExactly("Narrator");
        assertThat(credit.jobs()).isEmpty();
        assertThatThrownBy(() -> credit.characters().add("Tyler Durden"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> credit.jobs().add("Director"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("[PersonViewDTO] Should Preserve Credit Page Metadata And Allow Missing Hero")
    void shouldPreserveCreditPageMetadataAndAllowMissingHero() {
        PageResponseDTO<PersonCreditViewDTO> credits = new PageResponseDTO<>(List.of(), 3, 10, 24, 3, false);
        PersonViewDTO view = new PersonViewDTO(null, 7L, null, null, credits);

        assertThat(view.hero()).isNull();
        assertThat(view.followersCount()).isEqualTo(7L);
        assertThat(view.credits()).isSameAs(credits);
        assertThat(view.credits().page()).isEqualTo(3);
        assertThat(view.credits().size()).isEqualTo(10);
        assertThat(view.credits().totalElements()).isEqualTo(24L);
        assertThat(view.credits().totalPages()).isEqualTo(3);
        assertThat(view.credits().hasNext()).isFalse();
    }
}
