package com.watchwise.watchwise_api.like.service;

import java.util.UUID;

public interface LikeService {

    void likeComment(UUID userId, UUID commentId);

    void unlikeComment(UUID userId, UUID commentId);

    void likeDiaryEntry(UUID userId, UUID diaryEntryId);

    void unlikeDiaryEntry(UUID userId, UUID diaryEntryId);

    void likeList(UUID userId, UUID listId);

    void unlikeList(UUID userId, UUID listId);

}
