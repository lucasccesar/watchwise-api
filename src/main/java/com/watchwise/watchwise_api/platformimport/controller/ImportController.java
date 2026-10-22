package com.watchwise.watchwise_api.platformimport.controller;

import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.platformimport.dto.ImportCommitDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportJobDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportResolutionDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportSelectionDTO;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.service.ImportJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/imports")
public class ImportController {
    private final ImportJobService importJobService;

    public ImportController(ImportJobService importJobService) {
        this.importJobService = importJobService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobDTO createImport(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "PLATFORM_ARCHIVE") ImportInputKind inputKind,
            @RequestParam ImportSourceFormat sourceFormat,
            @RequestParam(required = false) ImportPlatform platform,
            @RequestParam(required = false) String sourceAccountId,
            @RequestParam(required = false, defaultValue = "UTC") String timeZone) {
        return importJobService.create(ownerId(), file, inputKind, sourceFormat, platform, sourceAccountId, timeZone);
    }

    @GetMapping("/{importId}")
    public ImportJobDTO getImport(@PathVariable UUID importId) {
        return importJobService.get(ownerId(), importId);
    }

    @PutMapping("/{importId}/selection")
    public ImportJobDTO selectImport(@PathVariable UUID importId, @Valid @RequestBody ImportSelectionDTO selection) {
        return importJobService.select(ownerId(), importId, selection);
    }

    @PatchMapping("/{importId}/resolutions")
    public ImportJobDTO resolveImport(@PathVariable UUID importId, @Valid @RequestBody ImportResolutionDTO resolution) {
        return importJobService.resolve(ownerId(), importId, resolution);
    }

    @GetMapping("/{importId}/preview")
    public ImportPreviewDTO getImportPreview(@PathVariable UUID importId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return importJobService.preview(ownerId(), importId, page, size);
    }

    @PostMapping("/{importId}/commit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobDTO commitImport(@PathVariable UUID importId, @Valid @RequestBody ImportCommitDTO commit) {
        return importJobService.commit(ownerId(), importId, commit);
    }

    @PostMapping("/{importId}/cancel")
    public ImportJobDTO cancelImport(@PathVariable UUID importId) {
        return importJobService.cancel(ownerId(), importId);
    }

    @GetMapping("/{importId}/result")
    public ImportPreviewDTO getImportResult(@PathVariable UUID importId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return importJobService.result(ownerId(), importId, page, size);
    }

    @PostMapping("/{importId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobDTO retryImport(@PathVariable UUID importId) {
        return importJobService.retry(ownerId(), importId);
    }

    private UUID ownerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UUID userId) {
            return userId;
        }
        throw new UnauthorizedException("Authentication is required");
    }
}
