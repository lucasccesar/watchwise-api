package com.watchwise.watchwise_api.top5entry.service.impl;

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
        List<Top5EntryResponseDTO> entries = readEntries(ownerId, type);
        if (entries.isEmpty()) {
            return entries;
        }

        List<UUID> contentIds = entries.stream()
                .map(Top5EntryResponseDTO::content)
                .filter(java.util.Objects::nonNull)
                .map(content -> content.id())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, Integer> latestScores = profileSummaryQueryRepository
                .findLatestScoresByUserIdAndContentIdsAndContentType(ownerId, contentIds, type.name()).stream()
                .filter(row -> row.getScore() != null)
                .collect(Collectors.toMap(
                        ProfileSummaryQueryRepository.LatestContentScore::getContentId,
                        ProfileSummaryQueryRepository.LatestContentScore::getScore));

        return entries.stream()
                .map(entry -> entry.withScore(entry.content() == null
                        ? null
                        : latestScores.get(entry.content().id())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Top5EntryResponseDTO> readEntries(UUID ownerId, ContentType type) {
        List<Top5Entry> entries = top5EntryRepository
                .findByUserIdAndTypeWithContentOrderByPositionAsc(ownerId, type);
        return entries.stream().map(top5EntryMapper::top5EntryToResponseDto).toList();
    }
}
