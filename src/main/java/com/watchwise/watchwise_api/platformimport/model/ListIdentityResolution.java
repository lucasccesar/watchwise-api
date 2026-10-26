package com.watchwise.watchwise_api.platformimport.model;

public record ListIdentityResolution(ImportSourceKey key, String issueCode) {
    public ListIdentityResolution {
        if ((key == null) == (issueCode == null)) {
            throw new IllegalArgumentException("Exactly one list identity result is required");
        }
        if (issueCode != null && !issueCode.equals("NEEDS_SOURCE_ACCOUNT")
                && !issueCode.equals("NEEDS_LIST_NAME")) {
            throw new IllegalArgumentException("Unsupported list identity decision");
        }
    }
}
