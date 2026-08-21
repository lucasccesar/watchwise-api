package com.watchwise.watchwise_api.like.service;

import java.util.UUID;

public interface LikeService {

    void likeComment(UUID userId, UUID commentId);

    void unlikeComment(UUID userId, UUID commentId);

    void likeDiaryEntry(UUID userId, UUID diaryEntryId);

}
