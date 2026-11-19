package com.watchwise.watchwise_api.platformimport.dto;

import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;

public record ImportEditableFieldDTO(
        ImportResolutionOperation.EditableField field,
        Integer maxLength
) { }
