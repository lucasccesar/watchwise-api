package com.watchwise.watchwise_api.contentreview.mapper;

import com.watchwise.watchwise_api.contentreview.dto.ContentReviewViewDTO;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ContentReviewMapper {

    ContentReviewViewDTO toView(ContentReview review);
}
