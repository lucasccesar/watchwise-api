package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.search.dto.SearchListPreviewCardDTO;
import com.watchwise.watchwise_api.search.dto.SearchListViewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class SearchListViewAssembler {

    public List<SearchListViewDTO> assemble(List<UserListCardDTO> cards) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        return cards.stream().filter(Objects::nonNull).map(this::toView).toList();
    }

    private SearchListViewDTO toView(UserListCardDTO card) {
        return new SearchListViewDTO(
                card.id(), card.user(), card.name(), card.description(), card.visibility(), card.updatedAt(),
                card.previewCards().stream().map(this::toPreview).toList(), card.itemsCount(), card.displayScope(),
                card.likesCount(), card.commentsCount(), card.likedByMe(), card.ownedByMe());
    }

    private SearchListPreviewCardDTO toPreview(ContentCardDTO card) {
        String posterUrl = card.customPosterUrl() != null
                ? card.customPosterUrl()
                : TmdbImageUrlBuilder.posterUrl(card.posterPath());
        return new SearchListPreviewCardDTO(
                card.type(), card.tmdbId(), card.seriesTmdbId(), card.seasonNumber(), card.episodeNumber(),
                card.title(), posterUrl, card.previewStatus());
    }
}
