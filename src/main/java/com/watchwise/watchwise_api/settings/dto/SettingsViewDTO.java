package com.watchwise.watchwise_api.settings.dto;

public record SettingsViewDTO(SettingsProfileDTO profile, SettingsPrivacyDTO privacy,
        SettingsContentPreferencesDTO contentPreferences, SettingsAccountDTO account) {}
