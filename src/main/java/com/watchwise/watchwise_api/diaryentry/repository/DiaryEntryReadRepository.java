package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface DiaryEntryReadRepository {

    Page<DiaryEntry> findPage(DiaryEntrySearchCriteria criteria, DiaryEntrySort sort, Pageable pageable);

    List<DiaryDaySummaryRow> findDailySummary(DiaryEntrySearchCriteria criteria);

    record DiaryDaySummaryRow(LocalDate date, long plays, long totalMinutes) {
    }
}
