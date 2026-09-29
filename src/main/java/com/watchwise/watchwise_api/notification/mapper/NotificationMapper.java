package com.watchwise.watchwise_api.notification.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", uses = {ContentMapper.class, UserMapper.class}, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationMapper {

    @Mapping(target = "latestActor", conditionExpression = "java(notification.getTargetType() != null)")
    @Mapping(target = "targetId", conditionExpression = "java(notification.getTargetType() != null)")
    @Mapping(target = "interactionCount", conditionExpression = "java(notification.getTargetType() != null)")
    NotificationResponseDTO notificationToNotificationResponseDto(Notification notification);

}
