package com.watchwise.watchwise_api.watchlist.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface WatchlistEntryMapper {

    WatchlistEntryResponseDTO watchlistEntryToResponseDto(WatchlistEntry entry);

}