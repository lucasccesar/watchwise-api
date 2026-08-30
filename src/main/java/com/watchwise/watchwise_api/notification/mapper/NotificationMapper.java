package com.watchwise.watchwise_api.notification.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", uses = ContentMapper.class, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationMapper {

    NotificationResponseDTO notificationToNotificationResponseDto(Notification notification);

}
