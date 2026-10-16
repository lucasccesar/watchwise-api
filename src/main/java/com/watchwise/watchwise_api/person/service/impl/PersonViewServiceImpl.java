package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import com.watchwise.watchwise_api.person.dto.PersonCreditSort;
import com.watchwise.watchwise_api.person.dto.PersonCreditSortDirection;
import com.watchwise.watchwise_api.person.dto.PersonCreditViewDTO;
import com.watchwise.watchwise_api.person.dto.PersonDetailsDTO;
import com.watchwise.watchwise_api.person.dto.PersonHeroDTO;
import com.watchwise.watchwise_api.person.dto.PersonProgressDTO;
import com.watchwise.watchwise_api.person.dto.PersonViewDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.person.service.PersonViewService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PersonViewServiceImpl implements PersonViewService {

    private static final int MAX_PAGE_SIZE = 24;
    private static final PersonCreditSort DEFAULT_SORT = PersonCreditSort.RELEASE_DATE;
    private static final PersonCreditSortDirection DEFAULT_DIRECTION = PersonCreditSortDirection.DESC;

    private final UserRepository userRepository;
    private final FollowedPersonRepository followedPersonRepository;
    private final FollowedPersonService followedPersonService;
    private final PersonAggregateReader aggregateReader;
    private final PersonFilmographyNormalizer filmographyNormalizer;
    private final PersonCreditViewAssembler creditViewAssembler;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public PersonViewDTO getPersonView(UUID viewerId, String personTmdbId, PersonParticipation participation,
                                      WatchStatus watchStatus, Boolean inWatchlist, PersonCreditSort sortBy,
                                      PersonCreditSortDirection sortDirection, Integer page, Integer size) {
        aggregateReader.validatePersonTmdbId(personTmdbId);
        var viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String language = viewer.getPreferredLanguage() == null ? "en-US" : viewer.getPreferredLanguage();
        TmdbPersonAggregate aggregate = aggregateReader.read(personTmdbId, language);
        List<PersonFilmographyCredit> normalized = filmographyNormalizer.normalize(aggregate);
        List<PersonCreditViewDTO> assembled = creditViewAssembler.assemble(viewerId, normalized);

        List<PersonCreditViewDTO> filtered = assembled.stream()
                .filter(credit -> matchesParticipation(credit.participation(), participation))
                .filter(credit -> watchStatus == null || credit.viewerState().watchStatus() == watchStatus)
                .filter(credit -> inWatchlist == null || inWatchlist.equals(credit.viewerState().inWatchlist()))
                .sorted(comparator(sortBy == null ? DEFAULT_SORT : sortBy,
                        sortDirection == null ? DEFAULT_DIRECTION : sortDirection))
                .toList();

        PageRequest request = pageRequestFactory.build(page, size == null ? MAX_PAGE_SIZE : size, MAX_PAGE_SIZE);
        PageResponseDTO<PersonCreditViewDTO> response = page(filtered, request);
        PersonDetailsDTO person = new PersonDetailsDTO(aggregate.id(), aggregate.name(), aggregate.biography(),
                aggregate.birthday(), aggregate.deathday(), aggregate.placeOfBirth(), aggregate.gender(),
                TmdbImageUrlBuilder.profileUrl(aggregate.profilePath()), aggregate.knownForDepartment(),
                aggregate.alsoKnownAs() == null ? List.of() : aggregate.alsoKnownAs(),
                followedPersonService.isFollowing(viewerId, personTmdbId));
        PersonProgressDTO progress = new PersonProgressDTO(assembled.size(),
                (int) assembled.stream().filter(credit -> credit.viewerState().watchStatus() == WatchStatus.WATCHED).count(),
                assembled.isEmpty() ? 0.0 : assembled.stream()
                        .filter(credit -> credit.viewerState().watchStatus() == WatchStatus.WATCHED).count()
                        * 100.0 / assembled.size());
        return new PersonViewDTO(person, followedPersonRepository.countByPersonTmdbId(personTmdbId),
                hero(normalized), progress, response);
    }

    private static boolean matchesParticipation(PersonParticipation credit, PersonParticipation requested) {
        return requested == null || requested == PersonParticipation.ALL || credit == requested
                || credit == PersonParticipation.ALL;
    }

    private static Comparator<PersonCreditViewDTO> comparator(PersonCreditSort sort, PersonCreditSortDirection direction) {
        Comparator<PersonCreditViewDTO> primary = switch (sort) {
            case TITLE -> compare(PersonCreditViewDTO::title, direction, String.CASE_INSENSITIVE_ORDER);
            case POPULARITY -> compare(PersonCreditViewDTO::popularity, direction, Comparator.naturalOrder());
            case AVERAGE_SCORE -> compare(credit -> credit.stats().averageScore(), direction,
                    Comparator.<Double>naturalOrder());
            case CREDITED_EPISODE_COUNT -> compare(PersonCreditViewDTO::creditedEpisodeCount, direction, Comparator.naturalOrder());
            case RELEASE_DATE -> compare(PersonCreditViewDTO::releaseDate, direction, Comparator.naturalOrder());
        };
        return primary.thenComparing(PersonCreditViewDTO::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(credit -> credit.type().name())
                .thenComparing(PersonCreditViewDTO::tmdbId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static <T> Comparator<PersonCreditViewDTO> compare(
            java.util.function.Function<PersonCreditViewDTO, T> key, PersonCreditSortDirection direction,
            Comparator<T> natural) {
        Comparator<T> ordered = direction == PersonCreditSortDirection.DESC ? natural.reversed() : natural;
        return Comparator.comparing(key, Comparator.nullsLast(ordered));
    }

    private static PageResponseDTO<PersonCreditViewDTO> page(List<PersonCreditViewDTO> values, PageRequest request) {
        long offset = request.getOffset();
        int from = (int) Math.min(offset, values.size());
        int to = (int) Math.min((long) values.size(), offset + request.getPageSize());
        return PageResponseDTO.of(new PageImpl<>(values.subList(from, to), request, values.size()));
    }

    private static PersonHeroDTO hero(List<PersonFilmographyCredit> credits) {
        return credits.stream().filter(credit -> credit.backdropPath() != null && !credit.backdropPath().isBlank())
                .sorted(Comparator.comparing(PersonFilmographyCredit::popularity,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PersonFilmographyCredit::releaseDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PersonFilmographyCredit::title,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(credit -> credit.type().name())
                        .thenComparing(PersonFilmographyCredit::tmdbId))
                .findFirst()
                .map(credit -> new PersonHeroDTO(credit.type(), credit.tmdbId(), credit.title(),
                        credit.releaseDate() == null ? null : credit.releaseDate().getYear(),
                        TmdbImageUrlBuilder.backdropUrl(credit.backdropPath())))
                .orElse(null);
    }
}
