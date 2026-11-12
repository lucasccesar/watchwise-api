package com.watchwise.watchwise_api.top5entry.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.mapper.Top5EntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Top5EntryReaderImplTest {

    @Mock
    private Top5EntryRepository repository;
    @Mock
    private Top5EntryMapper mapper;
    @Mock
    private ProfileSummaryQueryRepository summaryQueryRepository;

    private Top5EntryReaderImpl reader;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        reader = new Top5EntryReaderImpl(repository, mapper, summaryQueryRepository);
        ownerId = UUID.randomUUID();
    }

    @Test
    void shouldReturnEntriesInPositionOrderWithLatestScore() {
        User owner = User.builder().id(ownerId).build();
        Content first = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        Content second = Content.builder().id(UUID.randomUUID()).tmdbId("680").type(ContentType.MOVIE).build();
        Top5Entry firstEntry = entry(owner, first, 1);
        Top5Entry secondEntry = entry(owner, second, 2);
        Top5EntryResponseDTO firstDto = dto(first, 1);
        Top5EntryResponseDTO secondDto = dto(second, 2);
        ProfileSummaryQueryRepository.LatestContentScore score = new ProfileSummaryQueryRepository.LatestContentScore() {
            @Override
            public UUID getContentId() {
                return first.getId();
            }

            @Override
            public Integer getScore() {
                return 9;
            }
        };
        when(repository.findByUserIdAndTypeWithContentOrderByPositionAsc(ownerId, ContentType.MOVIE))
                .thenReturn(List.of(firstEntry, secondEntry));
        when(mapper.top5EntryToResponseDto(firstEntry)).thenReturn(firstDto);
        when(mapper.top5EntryToResponseDto(secondEntry)).thenReturn(secondDto);
        when(summaryQueryRepository.findLatestScoresByUserIdAndContentIdsAndContentType(
                ownerId, List.of(first.getId(), second.getId()), ContentType.MOVIE.name()))
                .thenReturn(List.of(score));

        List<Top5EntryResponseDTO> result = reader.read(ownerId, ContentType.MOVIE);

        assertThat(result).containsExactly(firstDto.withScore(9), secondDto.withScore(null));
    }

    @Test
    void shouldSkipScoreQueryWhenTopFiveIsEmpty() {
        when(repository.findByUserIdAndTypeWithContentOrderByPositionAsc(ownerId, ContentType.SERIES))
                .thenReturn(List.of());

        assertThat(reader.read(ownerId, ContentType.SERIES)).isEmpty();

        verify(summaryQueryRepository, never()).findLatestScoresByUserIdAndContentIdsAndContentType(
                any(), any(), any());
    }

    private Top5Entry entry(User owner, Content content, int position) {
        return Top5Entry.builder().id(UUID.randomUUID()).user(owner).content(content)
                .type(ContentType.MOVIE).position(position)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private Top5EntryResponseDTO dto(Content content, int position) {
        ContentRefDTO contentRef = new ContentRefDTO(content.getId(), content.getTmdbId(), ContentType.MOVIE,
                null, null, null, null, null, null, null);
        return new Top5EntryResponseDTO(UUID.randomUUID(), ContentType.MOVIE, contentRef,
                position, LocalDateTime.now(), LocalDateTime.now());
    }
}
