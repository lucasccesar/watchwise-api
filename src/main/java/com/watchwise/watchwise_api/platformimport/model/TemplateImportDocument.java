package com.watchwise.watchwise_api.platformimport.model;

import java.time.LocalDate;
import java.util.List;

public record TemplateImportDocument(int schemaVersion, String title, String description, String coverImage,
                                     String instructions, LocalDate eligibilityStart, LocalDate eligibilityEnd,
                                     List<ImportedTemplateCategory> categories) { }
