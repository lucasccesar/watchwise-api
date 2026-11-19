package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ImportReviewQueue {
    private final ImportReviewPolicy policy;

    public ImportReviewQueue(ImportReviewPolicy policy) {
        this.policy = policy;
    }

    public void synchronize(ImportJob job, List<ImportItem> changedItems, boolean refreshPendingItems) {
        if (job == null || changedItems == null || changedItems.isEmpty()) return;
        Set<java.util.UUID> seenIds = new HashSet<>();
        List<ReviewStateChange> changes = new ArrayList<>(changedItems.size());
        long reviewCountDelta = 0;

        for (ImportItem item : changedItems) {
            if (item == null || !seenIds.add(item.getId())) continue;
            if (!job.getId().equals(item.getJobId())) {
                throw new IllegalArgumentException("Review item belongs to another import job");
            }
            boolean wasPending = item.isReviewPending();
            ImportReviewPolicy.ReviewState reviewState = policy.classify(item);
            boolean isPending = reviewState.pending();
            if (wasPending != isPending) reviewCountDelta += isPending ? 1 : -1;
            String fingerprint = isPending ? fingerprint(item, reviewState) : null;
            boolean visibleChanged = isPending && !Objects.equals(item.getReviewFingerprint(), fingerprint);
            if (wasPending != isPending || (refreshPendingItems && visibleChanged)) {
                changes.add(new ReviewStateChange(item, isPending, fingerprint));
            }
        }

        if (changes.isEmpty()) return;
        long revision = job.nextReviewRevision(reviewCountDelta);
        for (ReviewStateChange change : changes) {
            change.item().setReviewPending(change.pending());
            change.item().setReviewRevision(revision);
            change.item().setReviewFingerprint(change.fingerprint());
        }
    }

    private String fingerprint(ImportItem item, ImportReviewPolicy.ReviewState state) {
        String visibleState = String.join("\u001f",
                Objects.toString(item.getNormalizedKind(), ""),
                Objects.toString(item.getOutcome(), ""),
                Objects.toString(item.getDecision(), ""),
                Objects.toString(item.getIssue(), ""),
                Objects.toString(item.getPayloadJson(), ""),
                Objects.toString(item.getResolvedTargetJson(), ""),
                state.allowedOperations().stream().map(Enum::name).collect(Collectors.joining(",")),
                state.editableFields().stream()
                        .map(field -> field.field().name() + ":" + field.maxLength())
                        .collect(Collectors.joining(",")));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(visibleState.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ReviewStateChange(ImportItem item, boolean pending, String fingerprint) { }
}
