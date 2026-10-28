package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.user.service.EmailConfirmationSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.email.resend", name = "enabled", havingValue = "true")
public class ResendEmailConfirmationSender implements EmailConfirmationSender {

    private final RestClient client;
    private final String apiKey;
    private final String from;

    public ResendEmailConfirmationSender(
            @Value("${app.email.resend.api-key:}") String apiKey,
            @Value("${app.email.resend.from:}") String from) {
        this.apiKey = apiKey;
        this.from = from;
        var requestFactory = ClientHttpRequestFactoryBuilder.simple()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(Duration.ofSeconds(10)));
        this.client = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && from != null && !from.isBlank();
    }

    @Override
    public void sendConfirmation(String email, String rawToken) throws IOException {
        send(email, "Confirme seu e-mail no Watchwise",
                "Seu código de confirmação do Watchwise é:\n\n" + rawToken
                        + "\n\nUse este código uma única vez. Se você não solicitou esta mensagem, ignore-a.");
    }

    @Override
    public void sendPasswordReset(String email, String rawToken) throws IOException {
        send(email, "Redefina sua senha do Watchwise",
                "Seu código para redefinir a senha do Watchwise é:\n\n" + rawToken
                        + "\n\nUse este código uma única vez. Se você não solicitou esta mensagem, ignore-a.");
    }

    private void send(String email, String subject, String text) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Resend email delivery is not configured");
        }
        try {
            client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", from,
                            "to", List.of(email),
                            "subject", subject,
                            "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new IOException("Resend email delivery failed", exception);
        }
    }
}
