package com.watchwise.watchwise_api.platformimport.mapper;

import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportProgressDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ImportJobMapper {
    @Mapping(source = "job.id", target = "id")
    @Mapping(source = "job.inputKind", target = "inputKind")
    @Mapping(source = "job.sourceFormat", target = "sourceFormat")
    @Mapping(source = "job.platform", target = "platform")
    @Mapping(source = "job.state", target = "state")
    @Mapping(source = "job.version", target = "version")
    @Mapping(source = "job.previewVersion", target = "previewVersion")
    @Mapping(source = "digest", target = "previewDigest")
    @Mapping(source = "job.createdAt", target = "createdAt")
    @Mapping(source = "job.updatedAt", target = "updatedAt")
    @Mapping(source = "job.expiresAt", target = "expiresAt")
    @Mapping(target = "inventory", ignore = true)
    @Mapping(target = "progress", expression = "java(toProgress(job))")
    ImportJobDTO toDto(ImportJob job, String digest);

    default ImportProgressDTO toProgress(ImportJob job) {
        if (job.getProgressTotalItems() == null) return null;
        long applied = job.getProgressAppliedItems();
        long skipped = job.getProgressSkippedItems();
        return new ImportProgressDTO(job.getProgressTotalItems(), applied + skipped, applied, skipped);
    }
}
