package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreationService;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class PicksTemplateImportApplier implements ImportUnitApplier {
    private static final String SOURCE_NAMESPACE = "WATCHWISE_TEMPLATE_V1";
    private static final String SOURCE_KIND = "PICKS_TEMPLATE";
    private final ImportItemRepository items;
    private final PreparedTemplateCreationService preparedCreations;
    private final ImportLedger ledger;
    private final ObjectMapper mapper;

    public PicksTemplateImportApplier(ImportItemRepository items,
                                     PreparedTemplateCreationService preparedCreations,
                                     ImportLedger ledger, ObjectMapper mapper) {
        this.items = items;
        this.preparedCreations = preparedCreations;
        this.ledger = ledger;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.TEMPLATE;
    }

    @Override
    public ImportSourceKey sourceKey(ImportExecutionContext context, ImportItem item) {
        return new ImportSourceKey(SOURCE_NAMESPACE, SOURCE_KIND, semanticFingerprint(item));
    }

    @Override
    public String payloadHash(ImportExecutionContext context, ImportItem item) {
        return semanticFingerprint(item);
    }

    @Override
    public void apply(ImportExecutionContext context, UUID unitId) {
        ImportItem item = items.findById(unitId)
                .filter(candidate -> context.jobId().equals(candidate.getJobId())
                        && context.planGeneration() == candidate.getPlanGeneration()
                        && candidate.getNormalizedKind() == ImportRecordKind.TEMPLATE)
                .orElseThrow(() -> new BadRequestException("Picks template import item not found"));
        JsonNode plan = read(item).path("resolution").path("preparedTemplate");
        PreparedTemplateCreation prepared = preparedCreation(plan);
        var created = preparedCreations.createPrepared(context.ownerId(), prepared);
        ledger.markApplied(context.ownerId(), sourceKey(context, item), "PICKS_TEMPLATE", created.id());
    }

    private PreparedTemplateCreation preparedCreation(JsonNode plan) {
        if (plan == null || !plan.path("preparedCreation").isObject()) {
            throw new BadRequestException("Picks template plan was not prepared");
        }
        try {
            return mapper.treeToValue(plan.path("preparedCreation"), PreparedTemplateCreation.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BadRequestException("Picks template plan is invalid");
        }
    }

    private String semanticFingerprint(ImportItem item) {
        JsonNode plan = read(item).path("resolution").path("preparedTemplate");
        JsonNode fingerprint = plan.path("semanticFingerprint");
        if (!fingerprint.isTextual() || !fingerprint.asString().matches("[a-f0-9]{64}")) {
            throw new BadRequestException("Picks template semantic identity is unavailable");
        }
        return fingerprint.asString();
    }

    private JsonNode read(ImportItem item) {
        try {
            return mapper.readTree(item.getPayloadJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Prepared Picks template payload is invalid", exception);
        }
    }
}
