package com.watchwise.watchwise_api.diaryentry.dto;

public sealed interface DiaryTimelineResponseItemDTO
        permits DiaryTimelineEntryItemDTO, DiaryTimelineCompletionItemDTO {

    String kind();
}
