package com.watchwise.watchwise_api.settings.service;

import com.watchwise.watchwise_api.settings.dto.SettingsViewDTO;

import java.util.UUID;

public interface SettingsViewService {
    SettingsViewDTO getSettings(UUID currentUserId);
}
