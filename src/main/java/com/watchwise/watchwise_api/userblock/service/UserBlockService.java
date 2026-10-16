package com.watchwise.watchwise_api.userblock.service;

import com.watchwise.watchwise_api.userblock.dto.BlockedUserDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserBlockService {
    void block(UUID blockerId, UUID blockedId);
    void unblock(UUID blockerId, UUID blockedId);
    Page<BlockedUserDTO> getBlockedUsers(UUID blockerId, Integer page, Integer size);
}
