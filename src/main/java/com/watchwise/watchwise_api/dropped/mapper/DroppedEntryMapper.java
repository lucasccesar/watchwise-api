package com.watchwise.watchwise_api.dropped.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface DroppedEntryMapper {

    @Mapping(source = "likedByMe", target = "likedByMe")
    DroppedEntryResponseDTO droppedEntryToResponseDto(DroppedEntry entry, boolean likedByMe);

    @Mapping(target = "likedByMe", constant = "false")
    DroppedEntryResponseDTO droppedEntryToResponseDto(DroppedEntry entry);

}
