package com.watchwise.watchwise_api.diaryentry.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface DiaryEntryMapper {

    @Mapping(source = "entry.user.id", target = "userId")
    @Mapping(target = "watchedWith", expression = "java(java.util.List.of())")
    DiaryEntryResponseDTO diaryEntryToResponseDto(DiaryEntry entry, boolean likedByMe);

    @Mapping(source = "entry.user.id", target = "userId")
    DiaryEntryResponseDTO diaryEntryToResponseDto(DiaryEntry entry, boolean likedByMe, List<UserPreviewDTO> watchedWith);

}
