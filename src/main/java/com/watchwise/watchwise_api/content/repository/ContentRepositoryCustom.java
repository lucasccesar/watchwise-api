package com.watchwise.watchwise_api.content.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;

import java.util.Collection;
import java.util.List;

public interface ContentRepositoryCustom {

    List<Content> findAllByCoordinates(Collection<ContentCoordinate> coordinates);
}
