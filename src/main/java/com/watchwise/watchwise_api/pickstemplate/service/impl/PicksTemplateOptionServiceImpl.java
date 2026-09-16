package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PicksTemplateOptionServiceImpl implements PicksTemplateOptionService {

    private final PicksTemplateRepository templateRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PicksTemplateOptionRepository optionRepository;
    private final PickSelectionRepository selectionRepository;
    private final UserRepository userRepository;
    private final PickTargetService pickTargetService;
    private final PicksTemplateOptionMapper optionMapper;
    private final PageRequestFactory pageRequestFactory;
    private final TmdbClient tmdbClient;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.search.max-requests}")
    private int searchMaxRequests;

    @Value("${app.rate-limit.search.window-minutes}")
    private long searchWindowMinutes;

    @Override
    @Transactional
    public PicksTemplateOptionDTO addOption(UUID actorId, UUID templateId, UUID categoryId, PicksTemplateOptionCreationDTO dto) {
        PicksTemplate template = lockTemplate(templateId);
        assertCanManage(actorId, template);
        PicksTemplateCategory category = lockCategory(templateId, categoryId);
        if (category.getOptionMode() != PickCategoryOptionMode.FIXED) {
            throw new BadRequestException("Open categories cannot receive fixed options");
        }
        ResolvedPickTarget target = pickTargetService.validateFixedOption(actorId, template, category, dto.target());
        PicksTemplateOption saved = FixedOptionPersistence.saveAndFlush(optionRepository, PicksTemplateOption.builder().category(category).content(target.content())
                .personTmdbId(target.personTmdbId()).contextContent(target.contextContent()).createdAt(LocalDateTime.now()).build());
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickOptionSearchDTO> searchOptions(UUID viewerId, UUID templateId, UUID categoryId, String query,
                                                    String seriesTmdbId, Integer seasonNumber, Integer page, Integer size) {
        PicksTemplateCategory category = categoryRepository.findByIdAndPicksTemplateId(categoryId, templateId)
                .orElseThrow(() -> new NotFoundException("Picks template category not found"));
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        if (category.getOptionMode() == PickCategoryOptionMode.FIXED) {
            return optionRepository.findByCategoryId(categoryId, pageRequest).map(optionMapper::picksTemplateOptionToSearchDto);
        }
        requestThrottler.checkAllowed(
                "search|" + viewerId,
                searchMaxRequests,
                Duration.ofMinutes(searchWindowMinutes));
        if (category.getAllowedType() != PickAllowedType.EPISODE && (query == null || query.isBlank())) {
            throw new BadRequestException("q must be provided for open movie, series and person searches");
        }
        return openOptions(viewerId, category, query, seriesTmdbId, seasonNumber, pageRequest);
    }

    @Override
    @Transactional
    public void deleteOption(UUID actorId, UUID templateId, UUID categoryId, UUID optionId) {
        PicksTemplate template = lockTemplate(templateId);
        assertCanManage(actorId, template);
        PicksTemplateCategory category = lockCategory(templateId, categoryId);
        PicksTemplateOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new NotFoundException("Picks template option not found"));
        if (!option.getCategory().getId().equals(category.getId())) {
            throw new NotFoundException("Picks template option not found");
        }
        if (selectionRepository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(categoryId,
                option.getContent() == null ? null : option.getContent().getId(), option.getPersonTmdbId(),
                option.getContextContent() == null ? null : option.getContextContent().getId())) {
            throw new ConflictException("A fixed option with selections cannot be deleted");
        }
        optionRepository.delete(option);
    }

    private Page<PickOptionSearchDTO> openOptions(UUID viewerId, PicksTemplateCategory category, String query,
                                                   String seriesTmdbId, Integer seasonNumber, PageRequest pageRequest) {
        int requestedPage = pageRequest.getPageNumber() + 1;
        return switch (category.getAllowedType()) {
            case MOVIE -> movieOptions(query.trim(), requestedPage, pageRequest);
            case SERIES -> seriesOptions(query.trim(), requestedPage, pageRequest);
            case PERSON -> personOptions(query.trim(), requestedPage, pageRequest);
            case EPISODE -> openEpisodes(seriesTmdbId, seasonNumber, pageRequest);
        };
    }

    private Page<PickOptionSearchDTO> movieOptions(String query, int page, PageRequest pageRequest) {
        TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>> lookup = tmdbClient.searchMovies(query,
                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, page);
        TmdbSearchPage<TmdbMovieSearchResult> searchPage = searchPageOrEmpty(lookup);
        List<PickOptionSearchDTO> options = searchPage.results().stream().map(movie ->
                new PickOptionSearchDTO(null, content(movie.id(), ContentType.MOVIE), null, null)).toList();
        return externalPage(searchPage, pageRequest, options);
    }

    private Page<PickOptionSearchDTO> seriesOptions(String query, int page, PageRequest pageRequest) {
        TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>> lookup = tmdbClient.searchTv(query,
                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, page);
        TmdbSearchPage<TmdbTvSearchResult> searchPage = searchPageOrEmpty(lookup);
        List<PickOptionSearchDTO> options = searchPage.results().stream().map(series ->
                new PickOptionSearchDTO(null, content(series.id(), ContentType.SERIES), null, null)).toList();
        return externalPage(searchPage, pageRequest, options);
    }

    private Page<PickOptionSearchDTO> personOptions(String query, int page, PageRequest pageRequest) {
        TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>> lookup = tmdbClient.searchPeople(query,
                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, page);
        TmdbSearchPage<TmdbPersonSearchResult> searchPage = searchPageOrEmpty(lookup);
        List<PickOptionSearchDTO> options = searchPage.results().stream()
                .map(person -> new PickOptionSearchDTO(null, null, person.id(), null)).toList();
        return externalPage(searchPage, pageRequest, options);
    }

    private Page<PickOptionSearchDTO> openEpisodes(String seriesTmdbId, Integer seasonNumber, PageRequest pageRequest) {
        if (seriesTmdbId == null || !seriesTmdbId.matches("[0-9]+") || seasonNumber == null || seasonNumber < 0) {
            throw new BadRequestException("Episode search requires seriesTmdbId and a non-negative seasonNumber");
        }
        TmdbLookupResult<TmdbSeasonFullDetails> lookup = tmdbClient.getSeasonFullDetails(seriesTmdbId, seasonNumber,
                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
        if (lookup.isUnavailable()) throw new TmdbUnavailableException("TMDB is currently unavailable");
        TmdbSeasonFullDetails season = lookup.toOptional().orElse(null);
        if (season == null) return new PageImpl<>(List.of(), pageRequest, 0);
        List<PickOptionSearchDTO> all = season.episodes() == null ? List.of() : season.episodes().stream()
                .filter(episode -> episode.episodeNumber() != null)
                .map(episode -> toEpisodeTarget(seriesTmdbId, seasonNumber, episode)).toList();
        int from = Math.min((int) pageRequest.getOffset(), all.size());
        int to = Math.min(from + pageRequest.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageRequest, all.size());
    }

    private <T> TmdbSearchPage<T> searchPageOrEmpty(TmdbLookupResult<TmdbSearchPage<T>> lookup) {
        if (lookup.isUnavailable()) throw new TmdbUnavailableException("TMDB is currently unavailable");
        return lookup.toOptional().orElseGet(() -> new TmdbSearchPage<>(1, List.of(), 0, 0));
    }

    private Page<PickOptionSearchDTO> externalPage(TmdbSearchPage<?> searchPage, PageRequest pageRequest,
                                                    List<PickOptionSearchDTO> options) {
        return new PageImpl<>(options, pageRequest, searchPage.totalResults()) {
            @Override
            public long getTotalElements() {
                return searchPage.totalResults();
            }

            @Override
            public int getNumber() {
                return Math.max(0, searchPage.page() - 1);
            }

            @Override
            public int getTotalPages() {
                return searchPage.totalPages();
            }

            @Override
            public boolean hasNext() {
                return searchPage.page() < searchPage.totalPages();
            }

            @Override
            public boolean isLast() {
                return !hasNext();
            }
        };
    }

    private PickOptionSearchDTO toEpisodeTarget(String seriesTmdbId, Integer seasonNumber, TmdbEpisodeSummary episode) {
        return new PickOptionSearchDTO(null, new ContentRefDTO(null, null, ContentType.EPISODE, seriesTmdbId,
                seasonNumber, episode.episodeNumber(), null, null, null, null), null, null);
    }

    private ContentRefDTO content(String tmdbId, ContentType type) {
        return new ContentRefDTO(null, tmdbId, type, null, null, null, null, null, null, null);
    }

    private PicksTemplateOptionDTO toDto(PicksTemplateOption option) {
        return new PicksTemplateOptionDTO(option.getId(), optionMapper.picksTemplateOptionToSearchDto(option), option.getCreatedAt());
    }

    private PicksTemplate lockTemplate(UUID templateId) {
        return templateRepository.findByIdForUpdate(templateId).orElseThrow(() -> new NotFoundException("Picks template not found"));
    }

    private PicksTemplateCategory lockCategory(UUID templateId, UUID categoryId) {
        PicksTemplateCategory category = categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new NotFoundException("Picks template category not found"));
        if (!category.getPicksTemplate().getId().equals(templateId)) throw new NotFoundException("Picks template category not found");
        return category;
    }

    private void assertCanManage(UUID actorId, PicksTemplate template) {
        User actor = userRepository.findById(actorId).orElseThrow(() -> new NotFoundException("User not found"));
        if (actor.getRole() == UserRole.ADMIN || (template.getCreator() != null && actorId.equals(template.getCreator().getId()))) return;
        throw new ForbiddenException("You cannot manage this picks template");
    }
}
