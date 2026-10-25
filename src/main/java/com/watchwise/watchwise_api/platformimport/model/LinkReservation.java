package com.watchwise.watchwise_api.platformimport.model;
import java.util.UUID;
public record LinkReservation(UUID linkId, ImportLinkState state, UUID owningJobId, boolean newlyCreated) {
 public LinkReservation(UUID linkId, ImportLinkState state, UUID owningJobId) {
  this(linkId, state, owningJobId, false);
 }
}
