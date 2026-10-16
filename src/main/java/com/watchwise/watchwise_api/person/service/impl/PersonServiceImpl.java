package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import com.watchwise.watchwise_api.person.dto.PersonCreditDTO;
import com.watchwise.watchwise_api.person.dto.PersonDetailsDTO;
import com.watchwise.watchwise_api.person.dto.PersonProgressDTO;
import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.person.service.PersonService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class PersonServiceImpl implements PersonService {

    private static final int LOCAL_QUERY_BATCH_SIZE = 500;

    private final UserRepository userRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserListItemRepository userListItemRepository;
    private final FollowedPersonService followedPersonService;
    private final PageRequestFactory pageRequestFactory;
    private final PersonAggregateReader aggregateReader;
    private final PersonFilmographyNormalizer filmographyNormalizer;

    @Override
    public PersonResponseDTO getPerson(UUID viewerId, String personTmdbId, PersonParticipation participation,
                                       Integer pageNumber, Integer pageSize) {
        aggregateReader.validatePersonTmdbId(personTmdbId);
        User viewer = userRepository.findById(viewerId).orElseThrow(() -> new NotFoundException("User not found"));
        TmdbPersonAggregate aggregate = aggregateReader.read(personTmdbId, viewer.getPreferredLanguage());
        List<PersonFilmographyCredit> credits = filmographyNormalizer.normalize(aggregate);
        Set<CreditKey> watched = collectLocalMedia(credits, ids -> diaryEntryRepository
                .findWatchedMediaForPersonCredits(viewerId, ids), DiaryEntryRepository.PersonCreditMedia::getType,
                DiaryEntryRepository.PersonCreditMedia::getTmdbId, DiaryEntryRepository.PersonCreditMedia::getSeriesTmdbId);
        Set<CreditKey> inList = collectLocalMedia(credits, ids -> userListItemRepository
                .findViewerMediaForPersonCredits(viewerId, ids), UserListItemRepository.PersonCreditMedia::getType,
                UserListItemRepository.PersonCreditMedia::getTmdbId, UserListItemRepository.PersonCreditMedia::getSeriesTmdbId);

        PersonProgressDTO progress = new PersonProgressDTO(credits.size(), (int) credits.stream()
                .map(PersonServiceImpl::key).filter(watched::contains).count(), percentage(credits, watched));
        List<PersonCreditDTO> filtered = credits.stream()
                .filter(credit -> matchesParticipation(credit.participation(), participation))
                .sorted(Comparator.comparing(PersonFilmographyCredit::releaseDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PersonFilmographyCredit::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(credit -> credit.type().name())
                        .thenComparing(PersonFilmographyCredit::tmdbId))
                .map(credit -> toDto(credit, watched.contains(key(credit)), inList.contains(key(credit))))
                .toList();
        PageRequest request = pageRequestFactory.build(pageNumber, pageSize);
        PageResponseDTO<PersonCreditDTO> page = page(filtered, request);
        PersonDetailsDTO person = new PersonDetailsDTO(aggregate.id(), aggregate.name(), aggregate.biography(), aggregate.birthday(),
                aggregate.deathday(), aggregate.placeOfBirth(), aggregate.gender(), TmdbImageUrlBuilder.profileUrl(aggregate.profilePath()),
                aggregate.knownForDepartment(), safeList(aggregate.alsoKnownAs()), followedPersonService.isFollowing(viewerId, personTmdbId));
        return new PersonResponseDTO(person, progress, page);
    }

    private boolean matchesParticipation(PersonParticipation creditParticipation, PersonParticipation requested) {
        return requested == null || requested == PersonParticipation.ALL || creditParticipation == requested
                || creditParticipation == PersonParticipation.ALL;
    }

    private PersonCreditDTO toDto(PersonFilmographyCredit credit, boolean watched, boolean inList) {
        return new PersonCreditDTO(credit.tmdbId(), credit.type(), credit.title(), TmdbImageUrlBuilder.posterUrl(credit.posterPath()),
                credit.releaseDate() == null ? null : credit.releaseDate().toString(), credit.characters(), credit.jobs(),
                credit.participation(), watched, inList);
    }

    private PageResponseDTO<PersonCreditDTO> page(List<PersonCreditDTO> content, PageRequest request) {
        long offset = request.getOffset();
        if (offset >= content.size()) {
            return PageResponseDTO.of(new PageImpl<>(List.of(), request, content.size()));
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min((long) content.size(), offset + request.getPageSize());
        return PageResponseDTO.of(new PageImpl<>(content.subList(fromIndex, toIndex), request, content.size()));
    }

    private double percentage(List<PersonFilmographyCredit> credits, Set<CreditKey> watched) {
        return credits.isEmpty() ? 0.0d : watched.stream().filter(key -> credits.stream().map(PersonServiceImpl::key).anyMatch(key::equals)).count()
                * 100.0d / credits.size();
    }

    private <T> Set<CreditKey> collectLocalMedia(List<PersonFilmographyCredit> credits, Function<Collection<String>, List<T>> query,
                                                   Function<T, ContentType> type, Function<T, String> tmdbId,
                                                   Function<T, String> seriesTmdbId) {
        Set<String> ids = credits.stream().map(PersonFilmographyCredit::tmdbId).collect(java.util.stream.Collectors.toSet());
        Set<CreditKey> result = new LinkedHashSet<>();
        List<String> idList = new ArrayList<>(ids);
        for (int from = 0; from < idList.size(); from += LOCAL_QUERY_BATCH_SIZE) {
            for (T media : query.apply(idList.subList(from, Math.min(from + LOCAL_QUERY_BATCH_SIZE, idList.size())))) {
                CreditKey key = localKey(type.apply(media), tmdbId.apply(media), seriesTmdbId.apply(media));
                if (key != null) result.add(key);
            }
        }
        return result;
    }

    private CreditKey localKey(ContentType type, String tmdbId, String seriesTmdbId) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case MOVIE -> validKey(MovieOrSeriesType.MOVIE, tmdbId);
            case SERIES -> validKey(MovieOrSeriesType.SERIES, tmdbId);
            case SEASON, EPISODE -> validKey(MovieOrSeriesType.SERIES, seriesTmdbId);
            default -> null;
        };
    }

    private CreditKey validKey(MovieOrSeriesType type, String tmdbId) {
        return tmdbId == null || tmdbId.isBlank() ? null : new CreditKey(type, tmdbId);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record CreditKey(MovieOrSeriesType type, String tmdbId) { }
    private static CreditKey key(PersonFilmographyCredit credit) { return new CreditKey(credit.type(), credit.tmdbId()); }
}
