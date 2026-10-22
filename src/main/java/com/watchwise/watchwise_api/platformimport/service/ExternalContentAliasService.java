package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;

import java.util.List;
import java.util.UUID;

public interface ExternalContentAliasService {
    AliasResolution resolve(UUID owner, AliasQuery query);
    List<AliasResolution> resolveBatch(UUID owner, List<AliasQuery> queries);
    void remember(UUID owner, AliasQuery query, ImportTarget target, AliasTrust trust);
}
