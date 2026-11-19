package com.watchwise.watchwise_api.platformimport.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public record ImportPlanVersion(long version, String digest) {
    public ImportPlanVersion {
        if (version < 0 || digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("Import plan version is invalid");
        }
    }

    public static ImportPlanVersion from(UUID jobId, long version, String selectionJson) {
        if (jobId == null || selectionJson == null || selectionJson.isBlank()) {
            throw new IllegalArgumentException("Import plan source is invalid");
        }
        String canonical = "{\"jobId\":\"" + jobId + "\",\"previewVersion\":" + version
                + ",\"selection\":" + selectionJson + "}";
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new ImportPlanVersion(version, digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
