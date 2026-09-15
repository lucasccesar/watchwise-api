package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PickTargetServiceImpl implements PickTargetService {

    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final PicksTemplateOptionRepository optionRepository;
    private final TmdbClient tmdbClient;

    @Override
    public ResolvedPickTarget validateForCategory(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickTargetDTO target) {
        requireCategory(category);
        if (category.getOptionMode() == PickCategoryOptionMode.FIXED) {
            return resolveFixedSelection(category, target);
        }
        return resolveTarget(template, category, target);
    }

    @Override
    public ResolvedPickTarget validateFixedOption(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickTargetDTO target) {
        requireCategory(category);
        if (category.getOptionMode() != PickCategoryOptionMode.FIXED) {
            throw new BadRequestException("Open categories cannot receive fixed options");
        }
        return resolveTarget(template, category, target);
    }

    @Override
    public boolean matches(PicksTemplateOption option, ResolvedPickTarget target) {
        return option != null && target != null && optionKey(option).equals(target.key());
    }

    @Override
    public boolean isValid(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickSelection selection) {
        if (category == null || selection == null || category.getAllowedType() == null || category.getOptionMode() == null) {
            return false;
        }
        ResolvedPickTarget target = fromSelection(selection);
        if (!matchesCategory(category, target.key())) {
            return false;
        }
        return category.getOptionMode() != PickCategoryOptionMode.FIXED
                || optionRepository.findByCategoryId(category.getId()).stream().anyMatch(option -> matches(option, target));
    }

    private ResolvedPickTarget resolveFixedSelection(PicksTemplateCategory category, PickTargetDTO target) {
        ResolvedPickTarget.TargetKey key = normalize(category, target);
        return optionRepository.findByCategoryId(category.getId()).stream()
                .filter(option -> optionKey(option).equals(key))
                .findFirst()
                .map(option -> new ResolvedPickTarget(
                        option.getContent(), key.personTmdbId(), option.getContextContent(), key))
                .orElseThrow(() -> new BadRequestException("Target is not a fixed option for this category"));
    }

    private ResolvedPickTarget resolveTarget(
            PicksTemplate template,
            PicksTemplateCategory category,
            PickTargetDTO target) {
        ResolvedPickTarget.TargetKey key = normalize(category, target);
        if (key.personTmdbId() != null) {
            requireFound(tmdbClient.getPersonDetails(key.personTmdbId()), "person");
            Content context = resolveContext(key.contextContent());
            return new ResolvedPickTarget(null, key.personTmdbId(), context, key);
        }

        LocalDate releaseDate = validateContentWithTmdb(key.content());
        ensureEligible(template, releaseDate);
        Content content = resolveContent(key.content());
        return new ResolvedPickTarget(content, null, null, key);
    }

    private Content resolveContext(ResolvedPickTarget.ContentKey contextKey) {
        if (contextKey == null) {
            return null;
        }
        validateContentWithTmdb(contextKey);
        return resolveContent(contextKey);
    }

    private LocalDate validateContentWithTmdb(ResolvedPickTarget.ContentKey key) {
        return switch (key.type()) {
            case MOVIE -> parseDate(requireFound(
                    tmdbClient.getMovieFullDetails(key.tmdbId(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE), "movie").releaseDate());
            case SERIES -> parseDate(requireFound(
                    tmdbClient.getTvFullDetails(key.tmdbId(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE), "series").firstAirDate());
            case EPISODE -> parseDate(requireFound(tmdbClient.getEpisodeFullDetails(
                    key.seriesTmdbId(), key.seasonNumber(), key.episodeNumber(),
                    TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE), "episode").airDate());
            case SEASON -> throw new BadRequestException("Season targets are not supported");
        };
    }

    private Content resolveContent(ResolvedPickTarget.ContentKey key) {
        ContentRefDTO reference = contentService.getOrCreateReference(new ContentRefCreationDTO(
                key.tmdbId(), key.type(), key.seriesTmdbId(), key.seasonNumber(), key.episodeNumber(),
                null, null, null, null, null, null));
        return contentRepository.getReferenceById(reference.id());
    }

    private ResolvedPickTarget.TargetKey normalize(PicksTemplateCategory category, PickTargetDTO target) {
        if (target == null) {
            throw new BadRequestException("Target must be provided");
        }
        return switch (category.getAllowedType()) {
            case MOVIE, SERIES, EPISODE -> normalizeContentTarget(category.getAllowedType(), target);
            case PERSON -> normalizePersonTarget(target);
        };
    }

    private ResolvedPickTarget.TargetKey normalizeContentTarget(PickAllowedType allowedType, PickTargetDTO target) {
        if (target.content() == null || target.personTmdbId() != null || target.contextContent() != null) {
            throw new BadRequestException("This category requires exactly one content target");
        }
        ResolvedPickTarget.ContentKey contentKey = contentKey(target.content());
        if (contentKey.type() != contentType(allowedType)) {
            throw new BadRequestException("Content target type does not match the category");
        }
        return new ResolvedPickTarget.TargetKey(contentKey, null, null);
    }

    private ResolvedPickTarget.TargetKey normalizePersonTarget(PickTargetDTO target) {
        if (target.content() != null) {
            throw new BadRequestException("Person targets cannot contain a primary content target");
        }
        String personTmdbId = numericIdentity(target.personTmdbId(), "Person TMDB ID must be provided");
        ResolvedPickTarget.ContentKey contextKey = target.contextContent() == null ? null : contentKey(target.contextContent());
        return new ResolvedPickTarget.TargetKey(null, personTmdbId, contextKey);
    }

    private ResolvedPickTarget.ContentKey contentKey(PickContentTargetDTO target) {
        if (target == null || target.type() == null) {
            throw new BadRequestException("Content target type must be provided");
        }
        return switch (target.type()) {
            case MOVIE -> {
                requireAbsent(target.seriesTmdbId(), target.seasonNumber(), target.episodeNumber());
                yield new ResolvedPickTarget.ContentKey(ContentType.MOVIE,
                        numericIdentity(target.tmdbId(), "Movie TMDB ID must be provided"), null, null, null);
            }
            case SERIES -> {
                requireAbsent(target.seriesTmdbId(), target.seasonNumber(), target.episodeNumber());
                yield new ResolvedPickTarget.ContentKey(ContentType.SERIES,
                        numericIdentity(target.tmdbId(), "Series TMDB ID must be provided"), null, null, null);
            }
            case EPISODE -> {
                if (hasText(target.tmdbId()) || target.seasonNumber() == null || target.seasonNumber() < 0
                        || target.episodeNumber() == null || target.episodeNumber() <= 0) {
                    throw new BadRequestException("Episode targets require series TMDB ID, a non-negative season number and a positive episode number");
                }
                yield new ResolvedPickTarget.ContentKey(ContentType.EPISODE, null,
                        numericIdentity(target.seriesTmdbId(), "Episode series TMDB ID must be provided"),
                        target.seasonNumber(), target.episodeNumber());
            }
            case PERSON -> throw new BadRequestException("Only movie, series and episode content targets are supported");
        };
    }

    private void requireAbsent(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        if (hasText(seriesTmdbId) || seasonNumber != null || episodeNumber != null) {
            throw new BadRequestException("Content target has fields that do not belong to its type");
        }
    }

    private void ensureEligible(PicksTemplate template, LocalDate releaseDate) {
        if (template == null || template.getEligibilityStartDate() == null || template.getEligibilityEndDate() == null) {
            return;
        }
        if (releaseDate == null || releaseDate.isBefore(template.getEligibilityStartDate())
                || releaseDate.isAfter(template.getEligibilityEndDate())) {
            throw new BadRequestException("Content is outside the template eligibility period");
        }
    }

    private boolean matchesCategory(PicksTemplateCategory category, ResolvedPickTarget.TargetKey key) {
        if (key == null) {
            return false;
        }
        if (category.getAllowedType() == PickAllowedType.PERSON) {
            return key.content() == null && hasText(key.personTmdbId()) && isContextKey(key.contextContent());
        }
        return key.personTmdbId() == null && key.contextContent() == null && key.content() != null
                && key.content().type() == contentType(category.getAllowedType());
    }

    private boolean isContextKey(ResolvedPickTarget.ContentKey key) {
        return key == null || key.type() == ContentType.MOVIE || key.type() == ContentType.SERIES || key.type() == ContentType.EPISODE;
    }

    private ResolvedPickTarget fromSelection(PickSelection selection) {
        ResolvedPickTarget.ContentKey content = contentKey(selection.getContent());
        ResolvedPickTarget.ContentKey context = contentKey(selection.getContextContent());
        return new ResolvedPickTarget(selection.getContent(), trim(selection.getPersonTmdbId()), selection.getContextContent(),
                new ResolvedPickTarget.TargetKey(content, trim(selection.getPersonTmdbId()), context));
    }

    private ResolvedPickTarget.TargetKey optionKey(PicksTemplateOption option) {
        return new ResolvedPickTarget.TargetKey(contentKey(option.getContent()), trim(option.getPersonTmdbId()),
                contentKey(option.getContextContent()));
    }

    private ResolvedPickTarget.ContentKey contentKey(Content content) {
        if (content == null) {
            return null;
        }
        return new ResolvedPickTarget.ContentKey(content.getType(), trim(content.getTmdbId()), trim(content.getSeriesTmdbId()),
                content.getSeasonNumber(), content.getEpisodeNumber());
    }

    private ContentType contentType(PickAllowedType type) {
        return switch (type) {
            case MOVIE -> ContentType.MOVIE;
            case SERIES -> ContentType.SERIES;
            case EPISODE -> ContentType.EPISODE;
            case PERSON -> throw new BadRequestException("Person categories do not use a primary content target");
        };
    }

    private void requireCategory(PicksTemplateCategory category) {
        if (category == null || category.getAllowedType() == null || category.getOptionMode() == null) {
            throw new BadRequestException("Category configuration is incomplete");
        }
    }

    private String numericIdentity(String value, String message) {
        String normalized = trim(value);
        if (normalized == null || !normalized.matches("[0-9]+")) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return trim(value) != null;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LocalDate parseDate(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private <T> T requireFound(TmdbLookupResult<T> result, String targetType) {
        if (result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        return result.toOptional().orElseThrow(() -> new NotFoundException(targetType + " was not found in TMDB"));
    }
}
