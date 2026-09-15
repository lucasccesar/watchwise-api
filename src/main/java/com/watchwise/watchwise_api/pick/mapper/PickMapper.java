package com.watchwise.watchwise_api.pick.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface PickMapper {
    @Mapping(source = "id", target = "id")
    @Mapping(source = "content", target = "content")
    @Mapping(source = "personTmdbId", target = "personTmdbId")
    @Mapping(source = "contextContent", target = "contextContent")
    PickOptionSearchDTO pickSelectionToSearchDto(PickSelection selection);
}
