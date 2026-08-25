package com.watchwise.watchwise_api.comment.mapper;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = UserMapper.class)
public interface CommentMapper {

    @Mapping(source = "comment.content.id", target = "contentId")
    @Mapping(source = "comment.list.id", target = "listId")
    @Mapping(source = "comment.diaryEntry.id", target = "diaryEntryId")
    @Mapping(source = "comment.parentComment.id", target = "parentCommentId")
    CommentResponseDTO commentToResponseDto(Comment comment, boolean likedByMe);

}
