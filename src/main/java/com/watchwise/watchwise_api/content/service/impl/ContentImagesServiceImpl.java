package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbContentImage;
import com.watchwise.watchwise_api.common.tmdb.TmdbContentImages;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.content.dto.ContentImageDTO;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentImagesService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

@Service
public class ContentImagesServiceImpl implements ContentImagesService {

    private final TmdbClient tmdbClient;

    public ContentImagesServiceImpl(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
    }

    @Override
    public List<ContentImageDTO> listPosters(MovieOrSeriesType type, String tmdbId) {
        return listImages(type, tmdbId, TmdbContentImages::posters, true);
    }

    @Override
    public List<ContentImageDTO> listBackdrops(MovieOrSeriesType type, String tmdbId) {
        return listImages(type, tmdbId, TmdbContentImages::backdrops, false);
    }

    private List<ContentImageDTO> listImages(
            MovieOrSeriesType type,
            String tmdbId,
            Function<TmdbContentImages, List<TmdbContentImage>> imageList,
            boolean poster) {
        String id = validateTmdbId(tmdbId);
        if (type == null) {
            throw new BadRequestException("type must be MOVIE or SERIES");
        }

        TmdbLookupResult<TmdbContentImages> lookup = switch (type) {
            case MOVIE -> tmdbClient.getMovieImages(id);
            case SERIES -> tmdbClient.getTvImages(id);
        };
        TmdbContentImages contentImages = requireFound(lookup, type);
        List<TmdbContentImage> images = imageList.apply(contentImages);
        if (images == null) {
            return List.of();
        }

        return images.stream()
                .filter(Objects::nonNull)
                .filter(image -> image.filePath() != null && !image.filePath().isBlank())
                .map(image -> toDto(image, poster))
                .toList();
    }

    private ContentImageDTO toDto(TmdbContentImage image, boolean poster) {
        String url = poster
                ? TmdbImageUrlBuilder.posterUrl(image.filePath())
                : TmdbImageUrlBuilder.backdropUrl(image.filePath());
        return new ContentImageDTO(url, image.width(), image.height(), image.aspectRatio(), image.language(),
                image.voteAverage(), image.voteCount());
    }

    private TmdbContentImages requireFound(TmdbLookupResult<TmdbContentImages> lookup, MovieOrSeriesType type) {
        if (lookup == null || lookup.isUnavailable()) {
            throw tmdbUnavailable();
        }
        if (lookup instanceof TmdbLookupResult.NotFound<TmdbContentImages>) {
            throw new NotFoundException("No " + type.name().toLowerCase(Locale.ROOT)
                    + " found on TMDB for the given id");
        }
        if (lookup instanceof TmdbLookupResult.Found<TmdbContentImages> found && found.value() != null) {
            return found.value();
        }
        throw tmdbUnavailable();
    }

    private String validateTmdbId(String tmdbId) {
        if (tmdbId == null || !tmdbId.matches("[1-9]\\d*")) {
            throw new BadRequestException("tmdbId must be a positive numeric identifier");
        }
        return tmdbId;
    }

    private TmdbUnavailableException tmdbUnavailable() {
        return new TmdbUnavailableException("TMDB is currently unavailable");
    }
}
