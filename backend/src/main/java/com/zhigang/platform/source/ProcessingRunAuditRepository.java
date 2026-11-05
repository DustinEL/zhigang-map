package com.zhigang.platform.source;

import com.zhigang.platform.source.ProcessingService.ProcessingRun;

public interface ProcessingRunAuditRepository {
    void save(ProcessingRun run);
}
