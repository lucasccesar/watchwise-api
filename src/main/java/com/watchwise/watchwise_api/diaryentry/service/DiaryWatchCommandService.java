package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.dto.WatchMutationResult;

import java.util.UUID;

public interface DiaryWatchCommandService {

    WatchMutationResult recordPrepared(UUID owner, WatchEventCommand command);
}
