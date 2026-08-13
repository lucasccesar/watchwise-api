package com.watchwise.watchwise_api.top5entry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryCreationDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.mapper.Top5EntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.top5entry.service.Top5EntryService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Top5EntryServiceImpl implements Top5EntryService {

    private final Top5EntryRepository top5EntryRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final Top5EntryMapper top5EntryMapper;

    static final int MAX_ENTRIES = 5;

    @Override
    public List<Top5EntryResponseDTO> getTop5(UUID viewerId, UUID userId, ContentType type) {
        validateType(type);

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewTop5(viewerId, userId, target);

        return top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type).stream()
                .map(top5EntryMapper::top5EntryToResponseDto)
                .toList();
    }

    private void assertCanViewTop5(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    @Override
    @Transactional
    public Top5EntryResponseDTO insertEntry(UUID userId, ContentType type, Top5EntryCreationDTO top5EntryCreationDTO) {
        validateType(type);

        if (top5EntryCreationDTO.content().type() != type) {
            throw new BadRequestException("Content type must match the requested top 5 type");
        }

        ContentRefDTO contentRef = contentService.getOrCreateReference(top5EntryCreationDTO.content());

        List<Top5Entry> existing = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type);
        int finalPosition = resolvePosition(top5EntryCreationDTO.position(), existing.size());

        shiftUpFrom(existing, finalPosition);

        User user = userRepository.getReferenceById(userId);
        Content content = contentRepository.getReferenceById(contentRef.id());
        LocalDateTime now = LocalDateTime.now();

        Top5Entry newEntry = Top5Entry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(finalPosition)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            Top5Entry saved = top5EntryRepository.save(newEntry);
            top5EntryRepository.flush();
            return top5EntryMapper.top5EntryToResponseDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public void removeEntry(UUID userId, ContentType type, UUID top5EntryId) {
        Top5Entry entry = top5EntryRepository.findById(top5EntryId)
                .orElseThrow(() -> new NotFoundException("Top 5 entry not found"));

        if (!entry.getUser().getId().equals(userId) || entry.getType() != type) {
            throw new NotFoundException("Top 5 entry not found");
        }

        int removedPosition = entry.getPosition();
        top5EntryRepository.delete(entry);
        top5EntryRepository.flush();

        List<Top5Entry> toShift = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type).stream()
                .filter(remaining -> remaining.getPosition() > removedPosition)
                .toList();

        for (Top5Entry remaining : toShift) {
            remaining.setPosition(remaining.getPosition() - 1);
            top5EntryRepository.save(remaining);
            top5EntryRepository.flush();
        }
    }

    private int resolvePosition(Integer requestedPosition, int currentCount) {
        if (requestedPosition != null) {
            return requestedPosition;
        }
        if (currentCount >= MAX_ENTRIES) {
            throw new BadRequestException("position is required when top 5 already has " + MAX_ENTRIES + " entries");
        }
        return currentCount + 1;
    }

    private void shiftUpFrom(List<Top5Entry> existing, int fromPosition) {
        List<Top5Entry> toShift = existing.stream()
                .filter(entry -> entry.getPosition() >= fromPosition)
                .sorted(Comparator.comparing(Top5Entry::getPosition).reversed())
                .toList();

        for (Top5Entry entry : toShift) {
            if (entry.getPosition() == MAX_ENTRIES) {
                top5EntryRepository.delete(entry);
            } else {
                entry.setPosition(entry.getPosition() + 1);
                top5EntryRepository.save(entry);
            }
            top5EntryRepository.flush();
        }
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);

        if ("uq_top5_entries_user_id_type_content_id".equals(constraintName)) {
            return new ConflictException("This content is already in your top 5");
        }
        if ("uq_top5_entries_user_id_type_position".equals(constraintName)) {
            return new ConflictException("This position is already taken");
        }
        return new ConflictException("Unable to insert this content into your top 5");
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }

    private void validateType(ContentType type) {
        if (type != ContentType.MOVIE && type != ContentType.SERIES) {
            throw new BadRequestException("type must be MOVIE or SERIES");
        }
    }
}