package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportListWriter;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class StandaloneCollectionImportApplier {
    private final ImportJobRepository jobs;
    private final ExternalImportLinkRepository links;
    private final ImportSemanticFingerprint fingerprints;
    private final ImportListWriter lists;
    private final ImportLedger ledger;
    private final UserListRepository userLists;
    private final ObjectMapper mapper;

    public StandaloneCollectionImportApplier(ImportJobRepository jobs, ExternalImportLinkRepository links,
                                             ImportSemanticFingerprint fingerprints, ImportListWriter lists,
                                             ImportLedger ledger, UserListRepository userLists, ObjectMapper mapper) {
        this.jobs = jobs;
        this.links = links;
        this.fingerprints = fingerprints;
        this.lists = lists;
        this.ledger = ledger;
        this.userLists = userLists;
        this.mapper = mapper;
    }

    public boolean isStandaloneList(ImportExecutionContext context) {
        return jobs.findById(context.jobId())
                .filter(job -> context.ownerId().equals(job.getUserId()))
                .map(job -> job.getInputKind() == ImportInputKind.SINGLE_LIST)
                .orElse(false);
    }

    public ImportSourceKey operationKey(ImportExecutionContext context, ImportItem item) {
        PreparedListImport plan = prepared(context, item, null);
        return plan.operationKey();
    }

    public UUID apply(ImportExecutionContext context, PreparedListImport original) {
        PreparedListImport plan = prepared(context, null, original);
        String fingerprint = fingerprints.ofList(context, plan);
        ImportSourceKey alias = localIdentity(context, fingerprint);
        UUID explicitTarget = plan.linkedListId();
        UUID externalTarget = externalIdentityTarget(context, plan);
        if (explicitTarget != null && externalTarget != null && !explicitTarget.equals(externalTarget)) {
            throw new BadRequestException("This source list is already linked to another Watchwise list");
        }
        UUID preferredTarget = explicitTarget == null ? externalTarget : explicitTarget;
        LinkReservation aliasReservation = ledger.reserve(context.ownerId(), context.jobId(), alias,
                plan.operationKey().sourceId());
        if (aliasReservation.state() == ImportLinkState.IN_PROGRESS
                && aliasReservation.owningJobId() != null
                && !context.jobId().equals(aliasReservation.owningJobId())) {
            throw new ImportUnitTransaction.WaitForOtherJobException();
        }

        UUID aliasTarget = aliasReservation.state() == ImportLinkState.APPLIED
                ? aliasTarget(context, alias) : null;
        if (aliasTarget != null && preferredTarget != null && !aliasTarget.equals(preferredTarget)) {
            throw new BadRequestException("This list already has another Watchwise destination");
        }
        UUID linkedTarget = preferredTarget == null ? aliasTarget : preferredTarget;
        boolean aliasUnavailable = aliasReservation.state() == ImportLinkState.TOMBSTONE
                || aliasReservation.state() == ImportLinkState.SKIPPED;
        if (aliasUnavailable && linkedTarget == null) {
            skipExternalIdentity(context, plan);
            ledger.markSkipped(context.ownerId(), plan.operationKey(), null, null,
                    "SEMANTIC_LIST_TOMBSTONED");
            return null;
        }
        if (!java.util.Objects.equals(linkedTarget, plan.linkedListId())) {
            plan = new PreparedListImport(plan.sourceKey(), plan.operationKey(), plan.name(), plan.description(),
                    plan.parentItemId(), plan.itemCount(), plan.declaredItemCount(), linkedTarget);
        }

        UUID target = lists.apply(context, plan);
        if (target == null) {
            if (aliasReservation.state() == ImportLinkState.IN_PROGRESS) {
                ledger.markSkipped(context.ownerId(), alias, null, null, "SOURCE_LIST_NOT_APPLIED");
            }
            return null;
        }
        if (aliasUnavailable) {
            return target;
        }
        if (aliasReservation.state() == ImportLinkState.APPLIED) {
            if (!target.equals(aliasTarget)) {
                throw new BadRequestException("This list already has another Watchwise destination");
            }
        } else if (aliasReservation.state() == ImportLinkState.IN_PROGRESS) {
            ledger.markApplied(context.ownerId(), alias, "USER_LIST", target);
        }
        return target;
    }

    private UUID externalIdentityTarget(ImportExecutionContext context, PreparedListImport plan) {
        if (!externalIdentity(plan.sourceKey())) return null;
        ExternalImportLink existing = links.findByUserIdAndSourceNamespaceAndObjectKindAndSourceId(
                        context.ownerId(), plan.sourceKey().namespace(), "user_list", plan.sourceKey().sourceId())
                .filter(link -> link.getState() == ImportLinkState.APPLIED)
                .orElse(null);
        if (existing == null) return null;
        if (!"USER_LIST".equals(existing.getTargetType()) || existing.getTargetId() == null) {
            throw new IllegalStateException("Applied external list identity has an invalid target");
        }
        var destination = userLists.findById(existing.getTargetId())
                .orElseThrow(() -> new NotFoundException("User list not found"));
        if (destination.getUser() == null || !context.ownerId().equals(destination.getUser().getId())) {
            throw new NotFoundException("User list not found");
        }
        return existing.getTargetId();
    }

    private UUID aliasTarget(ImportExecutionContext context, ImportSourceKey alias) {
        ExternalImportLink existing = links.findByUserIdAndSourceNamespaceAndObjectKindAndSourceId(
                        context.ownerId(), alias.namespace(), alias.objectKind(), alias.sourceId())
                .orElseThrow(() -> new IllegalStateException("Applied semantic list alias is missing"));
        if (!"USER_LIST".equals(existing.getTargetType()) || existing.getTargetId() == null) {
            throw new IllegalStateException("Applied semantic list alias has an invalid target");
        }
        var destination = userLists.findById(existing.getTargetId())
                .orElseThrow(() -> new NotFoundException("User list not found"));
        if (destination.getUser() == null || !context.ownerId().equals(destination.getUser().getId())) {
            throw new NotFoundException("User list not found");
        }
        return existing.getTargetId();
    }

    private void skipExternalIdentity(ImportExecutionContext context, PreparedListImport plan) {
        if (!externalIdentity(plan.sourceKey())) return;
        ImportSourceKey identity = new ImportSourceKey(plan.sourceKey().namespace(), "user_list",
                plan.sourceKey().sourceId());
        LinkReservation reservation = ledger.reserve(context.ownerId(), context.jobId(), identity,
                plan.operationKey().sourceId());
        if (reservation.state() == ImportLinkState.IN_PROGRESS) {
            if (reservation.owningJobId() != null && !context.jobId().equals(reservation.owningJobId())) {
                throw new ImportUnitTransaction.WaitForOtherJobException();
            }
            ledger.markSkipped(context.ownerId(), identity, null, null, "SEMANTIC_LIST_TOMBSTONED");
        }
    }

    private PreparedListImport prepared(ImportExecutionContext context, ImportItem metadata,
                                        PreparedListImport original) {
        if (original == null) {
            try {
                var root = mapper.readTree(metadata.getPayloadJson());
                var source = root.path("source").isObject() ? root.path("source") : root;
                var fields = root.path("resolution").path("fields");
                String name = fields.path("TITLE").isTextual()
                        ? fields.path("TITLE").asString() : source.path("title").asString(null);
                String description = fields.path("DESCRIPTION").isTextual()
                        ? fields.path("DESCRIPTION").asString() : source.path("description").asString(null);
                var identity = root.path("resolution").path("listIdentity");
                ImportSourceKey sourceKey = identity.isObject()
                        ? new ImportSourceKey(identity.path("namespace").asString(),
                        identity.path("objectKind").asString(), identity.path("sourceId").asString())
                        : new ImportSourceKey(metadata.getSourceNamespace(), metadata.getObjectKind(), metadata.getSourceId());
                var target = root.path("resolution").path("linkImportedList").path("targetListId");
                UUID linked = target.isTextual() ? UUID.fromString(target.asString()) : null;
                original = new PreparedListImport(sourceKey, sourceKey, name, description, metadata.getId(),
                        0, null, linked);
            } catch (JacksonException exception) {
                throw new IllegalStateException("Standalone list metadata is invalid", exception);
            }
        }
        String semantic = fingerprints.ofList(context, original);
        ImportSourceKey sourceKey = externalIdentity(original.sourceKey())
                ? original.sourceKey() : localIdentity(context, semantic);
        ImportSourceKey operation = new ImportSourceKey(sourceKey.namespace(), "user_list_import",
                hash(sourceKey.sourceId() + ":" + semantic));
        UUID linked = original.linkedListId();
        if (linked != null) {
            var destination = userLists.findById(linked)
                    .orElseThrow(() -> new NotFoundException("User list not found"));
            if (destination.getUser() == null || !context.ownerId().equals(destination.getUser().getId())) {
                throw new NotFoundException("User list not found");
            }
            assertNoConflictingLink(context, sourceKey, linked);
            assertNoConflictingLink(context, localIdentity(context, semantic), linked);
        }
        return new PreparedListImport(sourceKey, operation, original.name(), original.description(),
                original.parentItemId(), original.itemCount(), original.declaredItemCount(), linked);
    }

    private void assertNoConflictingLink(ImportExecutionContext context, ImportSourceKey key, UUID target) {
        ImportSourceKey identity = new ImportSourceKey(key.namespace(), "user_list", key.sourceId());
        links.findByUserIdAndSourceNamespaceAndObjectKindAndSourceId(
                        context.ownerId(), identity.namespace(), identity.objectKind(), identity.sourceId())
                .filter(link -> link.getState() == ImportLinkState.APPLIED)
                .ifPresent(link -> {
                    if (!target.equals(link.getTargetId())) {
                        throw new BadRequestException("This source list is already linked to another Watchwise list");
                    }
                });
    }

    private ImportSourceKey localIdentity(ImportExecutionContext context, String semantic) {
        String platform = context.sourceNamespace().split(":", 2)[0].toUpperCase();
        return new ImportSourceKey(platform + ":OWNER:" + context.ownerId(), "user_list", semantic);
    }

    private boolean externalIdentity(ImportSourceKey key) {
        return key.namespace().toUpperCase().contains(":ACCOUNT:");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
