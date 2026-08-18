package com.watchwise.watchwise_api.dropped.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface DroppedEntryMapper {

    DroppedEntryResponseDTO droppedEntryToResponseDto(DroppedEntry entry);

}