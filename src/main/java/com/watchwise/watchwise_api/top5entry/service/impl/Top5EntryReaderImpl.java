package com.watchwise.watchwise_api.top5entry.service.impl;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.mapper.Top5EntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.top5entry.service.Top5EntryReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class Top5EntryReaderImpl implements Top5EntryReader {

    private final Top5EntryRepository top5EntryRepository;
    private final Top5EntryMapper top5EntryMapper;
    private final ProfileSummaryQueryRepository profileSummaryQueryRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Top5EntryResponseDTO> read(UUID ownerId, ContentType type) {
        List<Top5Entry> entries = top5EntryRepository
                .findByUserIdAndTypeWithContentOrderByPositionAsc(ownerId, type);
        if (entries.isEmpty()) {
            return List.of();
        }

        List<UUID> contentIds = entries.stream()
                .map(Top5Entry::getContent)
                .filter(java.util.Objects::nonNull)
                .map(Content::getId)
                .distinct()
                .toList();
        Map<UUID, Integer> latestScores = profileSummaryQueryRepository
                .findLatestScoresByUserIdAndContentIdsAndContentType(ownerId, contentIds, type.name()).stream()
                .filter(row -> row.getScore() != null)
                .collect(Collectors.toMap(
                        ProfileSummaryQueryRepository.LatestContentScore::getContentId,
                        ProfileSummaryQueryRepository.LatestContentScore::getScore));

        return entries.stream()
                .map(entry -> {
                    Content content = entry.getContent();
                    Integer score = content == null ? null : latestScores.get(content.getId());
                    return top5EntryMapper.top5EntryToResponseDto(entry).withScore(score);
                })
                .toList();
    }
}
