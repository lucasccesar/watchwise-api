package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;

import java.time.Instant;

public interface CompletionScopeLoader {

    VersionedCompletionScope load(MediaReference reference, String region, String language, Instant now);

    default VersionedCompletionScope loadPrepared(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarScheduleBatch preparedSeason) {
        return load(reference, region, language, now);
    }

    default VersionedCompletionScope loadPreparedSeries(
            MediaReference reference,
            String region,
            String language,
            Instant now,
            CalendarSeriesSchedule preparedSeries) {
        return load(reference, region, language, now);
    }
}
