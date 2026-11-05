package com.zhigang.platform.source;

import com.zhigang.platform.source.ProcessingService.ProcessingRun;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Profile("!mysql")
@Repository
public class InMemoryProcessingRunAuditRepository implements ProcessingRunAuditRepository {
    @Override
    public void save(ProcessingRun run) {
    }
}
