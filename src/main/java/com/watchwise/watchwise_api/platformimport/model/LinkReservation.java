package com.watchwise.watchwise_api.platformimport.model;
import java.util.UUID;
public record LinkReservation(UUID linkId, ImportLinkState state, UUID owningJobId) { }
