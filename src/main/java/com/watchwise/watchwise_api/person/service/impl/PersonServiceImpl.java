package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class PersonServiceImpl implements PersonService {

    private static final int LOCAL_QUERY_BATCH_SIZE = 500;

    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserListItemRepository userListItemRepository;
    private final FollowedPersonService followedPersonService;

    @Override
    public PersonResponseDTO getPerson(UUID viewerId, String personTmdbId, PersonParticipation participation,
                                       Integer pageNumber, Integer pageSize) {
        validatePersonTmdbId(personTmdbId);
        User viewer = userRepository.findById(viewerId).orElseThrow(() -> new NotFoundException("User not found"));
        TmdbPersonAggregate aggregate = requireAggregate(tmdbClient.getPersonAggregate(personTmdbId, viewer.getPreferredLanguage()));
        List<NormalizedCredit> credits = normalizeCredits(aggregate);
        Set<CreditKey> watched = collectLocalMedia(credits, ids -> diaryEntryRepository
                .findWatchedMediaForPersonCredits(viewerId, ids), DiaryEntryRepository.PersonCreditMedia::getType,
                DiaryEntryRepository.PersonCreditMedia::getTmdbId, DiaryEntryRepository.PersonCreditMedia::getSeriesTmdbId);
        Set<CreditKey> inList = collectLocalMedia(credits, ids -> userListItemRepository
                .findViewerMediaForPersonCredits(viewerId, ids), UserListItemRepository.PersonCreditMedia::getType,
                UserListItemRepository.PersonCreditMedia::getTmdbId, UserListItemRepository.PersonCreditMedia::getSeriesTmdbId);

        PersonProgressDTO progress = new PersonProgressDTO(credits.size(), (int) credits.stream()
                .map(NormalizedCredit::key).filter(watched::contains).count(), percentage(credits, watched));
        List<PersonCreditDTO> filtered = credits.stream()
                .filter(credit -> matchesParticipation(credit.participation(), participation))
                .sorted(Comparator.comparing(NormalizedCredit::releaseDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(credit -> credit.key().tmdbId()))
                .map(credit -> toDto(credit, watched.contains(credit.key()), inList.contains(credit.key())))
                .toList();
        PageRequest request = pageRequest(pageNumber, pageSize);
        PageResponseDTO<PersonCreditDTO> page = page(filtered, request);
        PersonDetailsDTO person = new PersonDetailsDTO(aggregate.id(), aggregate.name(), aggregate.biography(), aggregate.birthday(),
                aggregate.deathday(), aggregate.placeOfBirth(), aggregate.gender(), TmdbImageUrlBuilder.profileUrl(aggregate.profilePath()),
                aggregate.knownForDepartment(), safeList(aggregate.alsoKnownAs()), followedPersonService.isFollowing(viewerId, personTmdbId));
        return new PersonResponseDTO(person, progress, page);
    }

    private TmdbPersonAggregate requireAggregate(TmdbLookupResult<TmdbPersonAggregate> lookup) {
        if (lookup instanceof TmdbLookupResult.Found<TmdbPersonAggregate> found) {
            return found.value();
        }
        if (lookup.isNotFound()) {
            throw new NotFoundException("No person found on TMDB for the given id");
        }
        throw new TmdbUnavailableException("TMDB is currently unavailable");
    }

    private List<NormalizedCredit> normalizeCredits(TmdbPersonAggregate aggregate) {
        Map<CreditKey, MutableCredit> merged = new LinkedHashMap<>();
        mergeCredits(aggregate.combinedCredits() == null ? List.of() : aggregate.combinedCredits().cast(), true, merged);
        mergeCredits(aggregate.combinedCredits() == null ? List.of() : aggregate.combinedCredits().crew(), false, merged);
        return merged.values().stream().map(MutableCredit::toNormalized).toList();
    }

    private void mergeCredits(List<TmdbPersonAggregateCredit> source, boolean cast, Map<CreditKey, MutableCredit> merged) {
        for (TmdbPersonAggregateCredit credit : safeList(source)) {
            MovieOrSeriesType type = supportedType(credit.mediaType());
            if (type == null || credit.id() == null || credit.id().isBlank()) {
                continue;
            }
            CreditKey key = new CreditKey(type, credit.id());
            MutableCredit target = merged.computeIfAbsent(key, ignored -> new MutableCredit(key));
            target.add(credit, cast);
        }
    }

    private MovieOrSeriesType supportedType(String mediaType) {
        if ("movie".equals(mediaType)) return MovieOrSeriesType.MOVIE;
        if ("tv".equals(mediaType)) return MovieOrSeriesType.SERIES;
        return null;
    }

    private boolean matchesParticipation(PersonParticipation creditParticipation, PersonParticipation requested) {
        return requested == null || requested == PersonParticipation.ALL || creditParticipation == requested
                || creditParticipation == PersonParticipation.ALL;
    }

    private PersonCreditDTO toDto(NormalizedCredit credit, boolean watched, boolean inList) {
        return new PersonCreditDTO(credit.key().tmdbId(), credit.key().type(), credit.title(), TmdbImageUrlBuilder.posterUrl(credit.posterPath()),
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

    private double percentage(List<NormalizedCredit> credits, Set<CreditKey> watched) {
        return credits.isEmpty() ? 0.0d : watched.stream().filter(key -> credits.stream().map(NormalizedCredit::key).anyMatch(key::equals)).count()
                * 100.0d / credits.size();
    }

    private <T> Set<CreditKey> collectLocalMedia(List<NormalizedCredit> credits, Function<Collection<String>, List<T>> query,
                                                   Function<T, ContentType> type, Function<T, String> tmdbId,
                                                   Function<T, String> seriesTmdbId) {
        Set<String> ids = credits.stream().map(credit -> credit.key().tmdbId()).collect(java.util.stream.Collectors.toSet());
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

    private PageRequest pageRequest(Integer pageNumber, Integer pageSize) {
        if (pageNumber != null && pageNumber < 0) {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }
        int page = pageNumber == null || pageNumber == 0 ? 1 : pageNumber;
        int size = pageSize == null ? 20 : pageSize;
        if (size <= 0) throw new BadRequestException("Page size must be greater than 0");
        return PageRequest.of(page - 1, Math.min(size, 1000));
    }

    private void validatePersonTmdbId(String personTmdbId) {
        if (personTmdbId == null || !personTmdbId.matches("\\d{1,20}")) {
            throw new BadRequestException("personTmdbId must be a numeric TMDB id up to 20 digits");
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record CreditKey(MovieOrSeriesType type, String tmdbId) {
    }

    private record NormalizedCredit(CreditKey key, String title, String posterPath, LocalDate releaseDate, List<String> characters,
                                    List<String> jobs, PersonParticipation participation) {
    }

    private static final class MutableCredit {
        private final CreditKey key;
        private String title;
        private String posterPath;
        private LocalDate releaseDate;
        private final Set<String> characters = new LinkedHashSet<>();
        private final Set<String> jobs = new LinkedHashSet<>();
        private boolean hasCast;
        private boolean hasCrew;

        private MutableCredit(CreditKey key) {
            this.key = key;
        }

        private void add(TmdbPersonAggregateCredit credit, boolean cast) {
            if (cast) {
                hasCast = true;
                if (credit.character() != null && !credit.character().isBlank()) characters.add(credit.character());
            } else {
                hasCrew = true;
                if (credit.job() != null && !credit.job().isBlank()) jobs.add(credit.job());
            }
            String candidateTitle = key.type() == MovieOrSeriesType.MOVIE ? credit.title() : credit.name();
            if (isBlank(title) && !isBlank(candidateTitle)) title = candidateTitle;
            if (isBlank(posterPath) && !isBlank(credit.posterPath())) posterPath = credit.posterPath();
            LocalDate candidateDate = parseDate(key.type() == MovieOrSeriesType.MOVIE
                    ? credit.releaseDate() : credit.firstAirDate());
            if (releaseDate == null && candidateDate != null) releaseDate = candidateDate;
        }

        private NormalizedCredit toNormalized() {
            PersonParticipation participation = hasCast && hasCrew ? PersonParticipation.ALL
                    : hasCast ? PersonParticipation.CAST : PersonParticipation.CREW;
            return new NormalizedCredit(key, title, posterPath, releaseDate, List.copyOf(characters), List.copyOf(jobs), participation);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
