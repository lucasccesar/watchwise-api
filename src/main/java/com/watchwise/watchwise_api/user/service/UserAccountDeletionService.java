package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import java.util.UUID;

public interface UserAccountDeletionService {
    void deleteAccount(UUID id, DeleteAccountDTO request);
}
