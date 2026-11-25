package com.watchwise.watchwise_api.user.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRememberMeContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void omittedRememberMeDefaultsToFalse() {
        assertThat(mapper.readValue("""
                {"identifier":"viewer","password":"Example123"}
                """, LoginUserDTO.class).rememberMe()).isFalse();
        assertThat(mapper.readValue("""
                {"token":"synthetic"}
                """, OAuthLoginDTO.class).rememberMe()).isFalse();
    }

    @Test
    void explicitRememberMeIsPreserved() {
        assertThat(mapper.readValue("""
                {"identifier":"viewer","password":"Example123","rememberMe":true}
                """, LoginUserDTO.class).rememberMe()).isTrue();
        assertThat(mapper.readValue("""
                {"token":"synthetic","rememberMe":true}
                """, OAuthLoginDTO.class).rememberMe()).isTrue();
    }
}
