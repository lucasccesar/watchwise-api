package com.watchwise.watchwise_api.platformimport.service.impl;

public class ListItemCountMismatchException extends RuntimeException {
    public ListItemCountMismatchException(long declaredItemCount, long stagedItemCount) {
        super("LIST_ITEM_COUNT_MISMATCH: The source declares " + declaredItemCount
                + " list items but only " + stagedItemCount + " are available. Upload a complete export or skip this list.");
    }
}
