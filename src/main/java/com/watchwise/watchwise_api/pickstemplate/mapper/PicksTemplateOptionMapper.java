package com.watchwise.watchwise_api.pickstemplate.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = ContentMapper.class)
public interface PicksTemplateOptionMapper {
    @Mapping(source = "id", target = "id")
    @Mapping(source = "content", target = "content")
    @Mapping(source = "personTmdbId", target = "personTmdbId")
    @Mapping(source = "contextContent", target = "contextContent")
    PickOptionSearchDTO picksTemplateOptionToSearchDto(PicksTemplateOption option);
}
