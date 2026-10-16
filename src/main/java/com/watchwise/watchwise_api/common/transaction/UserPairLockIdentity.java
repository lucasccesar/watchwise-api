package com.watchwise.watchwise_api.common.transaction;

import java.util.UUID;

public final class UserPairLockIdentity {

    private UserPairLockIdentity() {
    }

    public static String of(UUID firstUserId, UUID secondUserId) {
        String first = firstUserId.toString();
        String second = secondUserId.toString();
        return first.compareTo(second) <= 0
                ? "user-pair|" + first + "|" + second
                : "user-pair|" + second + "|" + first;
    }
}
