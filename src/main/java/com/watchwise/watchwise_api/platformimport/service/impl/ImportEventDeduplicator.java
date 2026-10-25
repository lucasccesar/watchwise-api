package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.SourceApplicationDecision;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ImportEventDeduplicator {

    private final ImportLedger ledger;

    public ImportEventDeduplicator(ImportLedger ledger) {
        this.ledger = ledger;
    }

    public SourceApplicationDecision decide(UUID ownerId, UUID jobId, ImportSourceKey sourceKey,
                                            String payloadHash) {
        LinkReservation reservation = ledger.reserve(ownerId, jobId, sourceKey, payloadHash);
        if (reservation.state() != ImportLinkState.IN_PROGRESS) {
            return SourceApplicationDecision.SKIP_CONSUMED;
        }
        if (!jobId.equals(reservation.owningJobId())) {
            return SourceApplicationDecision.WAIT_OTHER_JOB;
        }
        return reservation.newlyCreated()
                ? SourceApplicationDecision.APPLY_NEW
                : SourceApplicationDecision.RESUME_OWN_RESERVATION;
    }

    public static String payloadHash(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
