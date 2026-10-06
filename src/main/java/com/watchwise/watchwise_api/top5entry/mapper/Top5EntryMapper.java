package com.watchwise.watchwise_api.top5entry.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface Top5EntryMapper {

    @Mapping(target = "customPosterUrl", ignore = true)
    @Mapping(target = "withCustomPosterUrl", ignore = true)
    @Mapping(target = "score", ignore = true)
    @Mapping(target = "withScore", ignore = true)
    Top5EntryResponseDTO top5EntryToResponseDto(Top5Entry entry);

}
