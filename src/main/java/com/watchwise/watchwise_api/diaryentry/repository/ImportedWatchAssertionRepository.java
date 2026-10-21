package com.watchwise.watchwise_api.diaryentry.repository;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ImportedWatchAssertionRepository extends JpaRepository<ImportedWatchAssertion, UUID> { }
