package com.watchwise.watchwise_api.platformimport.model;

import java.util.List;

public record ImportInventory(List<ImportCategoryInventory> categories, List<ImportListInventory> lists) {
    public ImportInventory {
        categories = categories == null ? List.of() : List.copyOf(categories);
        lists = lists == null ? List.of() : List.copyOf(lists);
    }
}
