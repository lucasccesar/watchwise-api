package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ListIdentityResolution;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class StandaloneListIdentityResolver {
    private static final int BATCH_SIZE = 200;
    private static final UUID FIRST_ID = new UUID(0, 0);
    private final ImportJobRepository jobRepository;
    private final ImportItemRepository itemRepository;
    private final UserListRepository userListRepository;
    private final ObjectMapper mapper;

    public StandaloneListIdentityResolver(ImportJobRepository jobRepository, ImportItemRepository itemRepository,
                                          UserListRepository userListRepository, ObjectMapper mapper) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.userListRepository = userListRepository;
        this.mapper = mapper;
    }

    public ListIdentityResolution resolve(UUID ownerId, UUID jobId, ImportSourceFormat sourceFormat,
                                          JsonNode metadata, UUID parentItemId) {
        var job = jobRepository.findOwnedById(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
        ImportPlatform expectedPlatform = switch (sourceFormat) {
            case TRAKT_JSON -> ImportPlatform.TRAKT;
            case LETTERBOXD_CSV -> ImportPlatform.LETTERBOXD;
            default -> throw new BadRequestException("Standalone list source format is invalid");
        };
        if (job.getSourceFormat() != sourceFormat || job.getPlatform() != expectedPlatform
                || job.getInputKind() != com.watchwise.watchwise_api.platformimport.model.ImportInputKind.SINGLE_LIST) {
            throw new BadRequestException("Import format and platform do not match the job");
        }
        ImportItem parent = itemRepository.findById(parentItemId)
                .filter(item -> jobId.equals(item.getJobId())
                        && item.getNormalizedKind() == ImportRecordKind.LIST_METADATA)
                .orElseThrow(() -> new NotFoundException("Imported list not found"));
        JsonNode source = payload(parent);
        String destinationId = text(metadata, "destinationListId");
        if (destinationId == null) {
            destinationId = text(source.path("resolution").path("linkImportedList"), "targetListId");
        }
        if (destinationId != null) {
            UUID targetId;
            try {
                targetId = UUID.fromString(destinationId);
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("Destination list id is invalid");
            }
            var destination = userListRepository.findById(targetId)
                    .orElseThrow(() -> new NotFoundException("User list not found"));
            if (destination.getUser() == null || !ownerId.equals(destination.getUser().getId())) {
                throw new NotFoundException("User list not found");
            }
        }
        String suppliedAccount = text(metadata, "sourceAccount");
        if (suppliedAccount != null && (suppliedAccount.length() > 220
                || suppliedAccount.chars().anyMatch(Character::isISOControl))) {
            throw new BadRequestException("Source account identifier is invalid");
        }
        if (job.getSourceAccountId() != null && suppliedAccount != null
                && !job.getSourceAccountId().equals(suppliedAccount)) {
            throw new BadRequestException("Source account does not match the uploaded account");
        }
        String account = job.getSourceAccountId() == null ? suppliedAccount : job.getSourceAccountId();
        String sourceListId = text(metadata, "sourceListId");
        if (sourceListId != null && account == null) {
            return new ListIdentityResolution(null, "NEEDS_SOURCE_ACCOUNT");
        }
        if (sourceListId == null && account != null) {
            sourceListId = text(source, "sourceUri");
        }
        String name = text(metadata, "name");
        if (name == null) name = text(source.path("resolution").path("fields"), "TITLE");
        if (name == null) name = text(source, "title");
        if (name == null && destinationId == null) {
            return new ListIdentityResolution(null, "NEEDS_LIST_NAME");
        }
        if (sourceListId != null) {
            String canonicalId = canonicalSourceListId(sourceFormat, account, sourceListId);
            return new ListIdentityResolution(new ImportSourceKey(expectedPlatform.name() + ":ACCOUNT:" + account,
                    "list_metadata", canonicalId), null);
        }
        String localNamespace = expectedPlatform.name() + ":OWNER:" + ownerId;
        return new ListIdentityResolution(new ImportSourceKey(localNamespace, "list_metadata",
                fingerprint(jobId, parent)), null);
    }

    private String canonicalSourceListId(ImportSourceFormat format, String account, String sourceListId) {
        if (format == ImportSourceFormat.TRAKT_JSON) {
            if (!sourceListId.matches("[0-9]+") || sourceListId.chars().allMatch(c -> c == '0')) {
                throw new BadRequestException("Trakt source list id is invalid");
            }
            return new BigInteger(sourceListId).toString();
        }
        try {
            URI uri = URI.create(sourceListId);
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"letterboxd.com".equalsIgnoreCase(uri.getHost())
                    || path == null || !path.startsWith("/" + account + "/list/")) {
                throw new BadRequestException("Letterboxd source list id is invalid");
            }
            return "https://letterboxd.com" + path;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Letterboxd source list id is invalid");
        }
    }

    private String fingerprint(UUID jobId, ImportItem parent) {
        MessageDigest digest = digest();
        add(digest, "standalone-list-v1");
        JsonNode parentPayload = payload(parent);
        add(digest, text(parentPayload, "title"));
        add(digest, text(parentPayload, "description"));
        long afterRank = -1;
        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            var page = itemRepository.findListItemsAfter(jobId, parent.getPlanGeneration(), parent.getSourceId(),
                    afterRank, afterOrdinal, afterId.toString(), BATCH_SIZE);
            if (page.isEmpty()) break;
            for (ImportItem item : page) {
                JsonNode value = payload(item);
                JsonNode content = value.path("source").isObject() ? value.path("source") : value;
                add(digest, text(content, "sourceContentUri"));
                add(digest, text(content, "title"));
                add(digest, content.path("releaseYear").toString());
                add(digest, text(content, "description"));
                add(digest, content.path("rank").toString());
                afterRank = content.path("rank").canConvertToLong()
                        ? content.path("rank").asLong() : item.getOrdinal() + 1;
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private JsonNode payload(ImportItem item) {
        try {
            return mapper.readTree(item.getPayloadJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Staged list metadata is invalid", exception);
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asString().isBlank() ? value.asString().trim() : null;
    }

    private void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
