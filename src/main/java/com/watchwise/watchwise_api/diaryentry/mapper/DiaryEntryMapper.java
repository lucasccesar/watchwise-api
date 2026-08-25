package com.watchwise.watchwise_api.diaryentry.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface DiaryEntryMapper {

    @Mapping(source = "entry.user.id", target = "userId")
    DiaryEntryResponseDTO diaryEntryToResponseDto(DiaryEntry entry, boolean likedByMe);

}
