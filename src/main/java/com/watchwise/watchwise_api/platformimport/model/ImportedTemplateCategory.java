package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record ImportedTemplateCategory(String name, String description, PickAllowedType type,
                                       PickCategoryGroup group, int displayOrder,
                                       List<JsonNode> nominees, boolean nomineesPresent) { }
