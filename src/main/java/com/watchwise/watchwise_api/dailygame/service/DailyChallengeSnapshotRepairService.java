package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;

public interface DailyChallengeSnapshotRepairService {

    boolean repairIfIncomplete(DailyChallenge challenge);
}
