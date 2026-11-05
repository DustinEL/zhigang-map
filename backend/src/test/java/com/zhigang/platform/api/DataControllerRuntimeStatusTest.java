package com.zhigang.platform.api;

import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.source.AuthorizedDirectoryConnector;
import com.zhigang.platform.source.AuthorizedFeedConnector;
import com.zhigang.platform.source.DataImportFileService;
import com.zhigang.platform.source.ProcessingService;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordPage;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import com.zhigang.platform.source.StructuredJdRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataControllerRuntimeStatusTest {

    @Test
    void reportsRealPendingWhenDynamicRecordsExistBeforePublication() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        StructuredJdRepository repository = mock(StructuredJdRepository.class);
        when(provider.dataMode()).thenReturn("NO_DATA");
        when(provider.operatingMode()).thenReturn("MYSQL_FACT_SOURCE");
        when(repository.findDynamicRecords(any())).thenReturn(new DynamicRecordPage(List.of(), 1, 1, 10));
        when(repository.findCurrentRealSnapshot()).thenReturn(
                new RealSnapshotView("", "REAL", "CURRENT_SNAPSHOT_ONLY", 0, 0, false, List.of()));
        when(repository.findCandidateSkills()).thenReturn(List.of());
        DataController controller = new DataController(mock(ProcessingService.class), mock(DataQualityService.class),
                mock(AuthorizedDirectoryConnector.class), mock(DataImportFileService.class),
                mock(AuthorizedFeedConnector.class), provider, repository);

        var status = controller.runtimeStatus().data();

        assertThat(status.dataMode()).isEqualTo("REAL_PENDING");
        assertThat(status.dynamicRecordCount()).isEqualTo(10);
        assertThat(status.publishedRealDocumentCount()).isZero();
    }
}
