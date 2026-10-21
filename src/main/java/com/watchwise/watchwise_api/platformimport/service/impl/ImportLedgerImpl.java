package com.watchwise.watchwise_api.platformimport.service.impl;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ImportLedgerImpl implements ImportLedger {
 private final JdbcTemplate jdbc;
 public ImportLedgerImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

 @Override @Transactional
 public LinkReservation reserve(UUID ownerId, UUID jobId, ImportSourceKey key, String payloadHash) {
  jdbc.update("""
   INSERT INTO external_import_links(id,user_id,source_namespace,object_kind,source_id,state,owning_job_id,payload_hash,created_at,updated_at)
   SELECT ?, ?, ?, ?, ?, 'IN_PROGRESS', j.id, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
   FROM import_jobs j WHERE j.id = ? AND j.user_id = ?
   ON CONFLICT (user_id,source_namespace,object_kind,source_id) DO NOTHING
   """, UUID.randomUUID(), ownerId, key.namespace(), key.objectKind(), key.sourceId(), payloadHash, jobId, ownerId);
  return jdbc.queryForObject("""
   SELECT id,state,owning_job_id FROM external_import_links
   WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=?
   """, (rs, row) -> new LinkReservation(rs.getObject("id", UUID.class),
    ImportLinkState.valueOf(rs.getString("state")), rs.getObject("owning_job_id", UUID.class)),
    ownerId, key.namespace(), key.objectKind(), key.sourceId());
 }

 @Override @Transactional
 public void markApplied(UUID ownerId, ImportSourceKey key, String targetType, UUID targetId) {
  updateState(ownerId, key, ImportLinkState.APPLIED, targetType, targetId, null);
 }

 @Override @Transactional(readOnly=true)
 public boolean isConsumed(UUID ownerId, ImportSourceKey key) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   SELECT COALESCE((SELECT state <> 'IN_PROGRESS' FROM external_import_links
   WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=?), FALSE)
   """, Boolean.class, ownerId, key.namespace(), key.objectKind(), key.sourceId()));
 }

 @Override @Transactional
 public void markSkipped(UUID ownerId, ImportSourceKey key, String targetType, UUID targetId, String reason) {
  updateState(ownerId, key, ImportLinkState.SKIPPED, targetType, targetId, reason);
 }

 @Override @Transactional
 public void markDeletedByTarget(UUID ownerId, String targetType, UUID targetId) {
  jdbc.update("""
   UPDATE external_import_links SET state='TOMBSTONE', updated_at=CURRENT_TIMESTAMP
   WHERE user_id=? AND target_type=? AND target_id=?
   """, ownerId, targetType, targetId);
 }

 private void updateState(UUID ownerId, ImportSourceKey key, ImportLinkState state, String targetType, UUID targetId, String reason) {
  jdbc.update("""
   UPDATE external_import_links SET state=?,target_type=?,target_id=?,outcome_reason=?,updated_at=CURRENT_TIMESTAMP
   WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=? AND state <> 'TOMBSTONE'
   """, state.name(), targetType, targetId, reason, ownerId, key.namespace(), key.objectKind(), key.sourceId());
 }
}
