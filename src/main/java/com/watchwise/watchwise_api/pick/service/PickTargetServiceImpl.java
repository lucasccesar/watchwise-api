package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.PreparedContentReference;
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
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PickTargetServiceImpl implements PickTargetService {

    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final PicksTemplateOptionRepository optionRepository;
    private final TmdbClient tmdbClient;
    private final NewTransactionExecutor transactions;

    @Override
    public PreparedPickTarget prepareForCategory(UUID viewerId, PicksTemplate template,
            PicksTemplateCategory category, PickTargetDTO target) {
        requireCategory(category);
        ResolvedPickTarget.TargetKey key = normalize(category, target);
        FixedOptionReference fixed = category.getOptionMode() == PickCategoryOptionMode.FIXED
                ? fixedOptions(category.getId()).stream().filter(option -> option.key().equals(key)).findFirst()
                    .orElseThrow(() -> new BadRequestException("Target is not a fixed option for this category")) : null;
        LocalDate releaseDate = null;
        if (key.personTmdbId() != null) {
            requireFound(tmdbClient.getPersonDetails(key.personTmdbId()), "person");
            if (key.contextContent() != null) validateContentWithTmdb(key.contextContent());
        } else {
            releaseDate = validateContentWithTmdb(key.content());
            ensureEligible(template, releaseDate);
        }
        return new PreparedPickTarget(key,
                fixed == null && key.content() != null ? prepareContent(key.content()) : null,
                fixed == null && key.contextContent() != null ? prepareContent(key.contextContent()) : null,
                fixed == null ? null : fixed.contentId(),
                fixed == null ? null : fixed.contextContentId(),
                new PreparedTargetValidation(key, true, releaseDate));
    }

    @Override
    public ResolvedPickTarget resolvePreparedForCategory(PicksTemplate template, PicksTemplateCategory category,
            PreparedPickTarget prepared) {
        requireCategory(category);
        ResolvedPickTarget.TargetKey key = prepared.key();
        if (!matchesCategory(category, key)) {
            throw new BadRequestException("Content target type does not match the category");
        }
        if (key.personTmdbId() == null) ensureEligible(template, prepared.validation().releaseDate());
        if (category.getOptionMode() == PickCategoryOptionMode.FIXED) {
            return optionRepository.findByCategoryId(category.getId()).stream()
                    .filter(option -> optionKey(option).equals(key))
                    .findFirst().map(option -> new ResolvedPickTarget(option.getContent(), key.personTmdbId(),
                            option.getContextContent(), key))
                    .orElseThrow(() -> new BadRequestException("Target is not a fixed option for this category"));
        }
        return new ResolvedPickTarget(persistContent(prepared.content(), prepared.existingContentId()),
                key.personTmdbId(), persistContent(prepared.contextContent(), prepared.existingContextContentId()), key);
    }

    @Override
    public PreparedTargetValidation prepareValidation(PickSelection selection) {
        ResolvedPickTarget.TargetKey key = ResolvedPickTarget.keyOf(selection);
        PicksTemplateCategory category = selection.getCategory();
        if (!isConfiguredCategory(category) || !matchesCategory(category, key)
                || (category.getOptionMode() == PickCategoryOptionMode.FIXED
                    && fixedOptions(category.getId()).stream().noneMatch(option -> option.key().equals(key)))) {
            return new PreparedTargetValidation(key, false, null, false);
        }
        if (key.personTmdbId() != null) {
            boolean validIdentity = key.content() == null && isNumericIdentity(key.personTmdbId())
                    && isContextKey(key.contextContent());
            boolean exists = validIdentity && currentPersonExists(key.personTmdbId())
                    && (key.contextContent() == null || validateCurrentContent(key.contextContent()).exists());
            return new PreparedTargetValidation(key, exists, null);
        }
        CurrentContentValidation content = key.contextContent() == null
                ? validateCurrentContent(key.content()) : new CurrentContentValidation(false, null);
        return new PreparedTargetValidation(key, content.exists(), content.date());
    }

    @Override
    public boolean isValidPrepared(PicksTemplate template, PicksTemplateCategory category, PickSelection selection,
            PreparedTargetValidation prepared) {
        if (prepared == null || !isConfiguredCategory(category) || !prepared.key().equals(ResolvedPickTarget.keyOf(selection))
                || !matchesCategory(category, prepared.key())) return false;
        if (category.getOptionMode() == PickCategoryOptionMode.FIXED
                && !isCurrentFixedOption(category, prepared.key())) return false;
        if (!prepared.checked()) throw new StalePickPreparationException();
        return prepared.exists() && (prepared.key().personTmdbId() != null
                || isWithinEligibilityPeriod(template, prepared.releaseDate()));
    }

    private boolean isConfiguredCategory(PicksTemplateCategory category) {
        return category != null && category.getAllowedType() != null && category.getOptionMode() != null;
    }

    private boolean isCurrentFixedOption(PicksTemplateCategory category, ResolvedPickTarget.TargetKey key) {
        return optionRepository.findByCategoryId(category.getId()).stream().anyMatch(option -> optionKey(option).equals(key));
    }

    private List<FixedOptionReference> fixedOptions(UUID categoryId) {
        return transactions.runInNewTransaction(() -> optionRepository.findByCategoryId(categoryId).stream()
                .map(option -> new FixedOptionReference(optionKey(option),
                        option.getContent() == null ? null : option.getContent().getId(),
                        option.getContextContent() == null ? null : option.getContextContent().getId()))
                .toList());
    }

    private PreparedContentReference prepareContent(ResolvedPickTarget.ContentKey key) {
        return contentService.prepareReference(referenceDto(key), false);
    }

    private Content persistContent(PreparedContentReference prepared, UUID existingId) {
        if (prepared != null) {
            return contentRepository.getReferenceById(contentService.persistPreparedReference(prepared, false).id());
        }
        return existingId == null ? null : contentRepository.getReferenceById(existingId);
    }

    private ContentRefCreationDTO referenceDto(ResolvedPickTarget.ContentKey key) {
        return new ContentRefCreationDTO(key.tmdbId(), key.type(), key.seriesTmdbId(), key.seasonNumber(),
                key.episodeNumber(), null, null, null, null, null, null);
    }

    @Override
    public ResolvedPickTarget validateForCategory(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickTargetDTO target) {
        return resolvePreparedForCategory(template, category, prepareForCategory(viewerId, template, category, target));
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
        if (category.getOptionMode() == PickCategoryOptionMode.FIXED
                && optionRepository.findByCategoryId(category.getId()).stream()
                .noneMatch(option -> matches(option, target))) {
            return false;
        }
        if (!isCurrentTargetValid(template, target)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isStructurallyValid(PicksTemplateCategory category, PickSelection selection,
            Collection<PicksTemplateOption> fixedOptions) {
        if (category == null || selection == null || category.getAllowedType() == null || category.getOptionMode() == null) {
            return false;
        }
        ResolvedPickTarget target = fromSelection(selection);
        if (!matchesCategory(category, target.key())) {
            return false;
        }
        return category.getOptionMode() != PickCategoryOptionMode.FIXED
                || fixedOptions.stream().anyMatch(option -> matches(option, target));
    }

    private boolean isCurrentTargetValid(PicksTemplate template, ResolvedPickTarget target) {
        if (target.key().personTmdbId() != null) {
            if (!currentPersonExists(target.key().personTmdbId())) {
                return false;
            }
            return target.key().contextContent() == null
                    || validateCurrentContent(target.key().contextContent()).exists();
        }

        CurrentContentValidation currentContent = validateCurrentContent(target.key().content());
        if (!currentContent.exists()) {
            return false;
        }
        return isWithinEligibilityPeriod(template, currentContent.date());
    }

    private boolean currentPersonExists(String personTmdbId) {
        return lookupValue(tmdbClient.getPersonDetails(personTmdbId)) != null;
    }

    private CurrentContentValidation validateCurrentContent(ResolvedPickTarget.ContentKey key) {
        if (!hasValidContentIdentity(key)) {
            return new CurrentContentValidation(false, null);
        }
        return switch (key.type()) {
            case MOVIE -> currentMovie(key.tmdbId());
            case SERIES -> currentSeries(key.tmdbId());
            case EPISODE -> currentEpisode(key.seriesTmdbId(), key.seasonNumber(), key.episodeNumber());
            case SEASON -> new CurrentContentValidation(false, null);
        };
    }

    private CurrentContentValidation currentMovie(String tmdbId) {
        TmdbMovieFullDetails details = lookupValue(
                tmdbClient.getMovieFullDetails(tmdbId, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        return details == null ? new CurrentContentValidation(false, null)
                : new CurrentContentValidation(true, parseDate(details.releaseDate()));
    }

    private CurrentContentValidation currentSeries(String tmdbId) {
        TmdbTvFullDetails details = lookupValue(
                tmdbClient.getTvFullDetails(tmdbId, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        return details == null ? new CurrentContentValidation(false, null)
                : new CurrentContentValidation(true, parseDate(details.firstAirDate()));
    }

    private CurrentContentValidation currentEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        TmdbEpisodeFullDetails details = lookupValue(tmdbClient.getEpisodeFullDetails(
                seriesTmdbId, seasonNumber, episodeNumber, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        return details == null ? new CurrentContentValidation(false, null)
                : new CurrentContentValidation(true, parseDate(details.airDate()));
    }

    private boolean isWithinEligibilityPeriod(PicksTemplate template, LocalDate date) {
        if (template == null || template.getEligibilityStartDate() == null || template.getEligibilityEndDate() == null) {
            return true;
        }
        return date != null && !date.isBefore(template.getEligibilityStartDate())
                && !date.isAfter(template.getEligibilityEndDate());
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
            return key.content() == null && isNumericIdentity(key.personTmdbId()) && isContextKey(key.contextContent());
        }
        return key.personTmdbId() == null && key.contextContent() == null && hasValidContentIdentity(key.content())
                && key.content().type() == contentType(category.getAllowedType());
    }

    private boolean isContextKey(ResolvedPickTarget.ContentKey key) {
        return key == null || hasValidContentIdentity(key);
    }

    private boolean hasValidContentIdentity(ResolvedPickTarget.ContentKey key) {
        if (key == null || key.type() == null) {
            return false;
        }
        return switch (key.type()) {
            case MOVIE, SERIES -> isNumericIdentity(key.tmdbId()) && key.seriesTmdbId() == null
                    && key.seasonNumber() == null && key.episodeNumber() == null;
            case EPISODE -> key.tmdbId() == null && isNumericIdentity(key.seriesTmdbId())
                    && key.seasonNumber() != null && key.seasonNumber() >= 0
                    && key.episodeNumber() != null && key.episodeNumber() > 0;
            case SEASON -> false;
        };
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
        if (type == PickAllowedType.PERSON) {
            throw new BadRequestException("Person categories do not use a primary content target");
        }
        return type.toContentType();
    }

    private void requireCategory(PicksTemplateCategory category) {
        if (category == null || category.getAllowedType() == null || category.getOptionMode() == null) {
            throw new BadRequestException("Category configuration is incomplete");
        }
    }

    private String numericIdentity(String value, String message) {
        String normalized = trim(value);
        if (!isNumericIdentity(normalized)) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    private boolean isNumericIdentity(String value) {
        return value != null && value.matches("[0-9]+");
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

    private <T> T lookupValue(TmdbLookupResult<T> result) {
        if (result == null) {
            return null;
        }
        if (result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        return result.toOptional().orElse(null);
    }

    private record CurrentContentValidation(boolean exists, LocalDate date) {
    }

    private record FixedOptionReference(ResolvedPickTarget.TargetKey key, UUID contentId, UUID contextContentId) {
    }
}
