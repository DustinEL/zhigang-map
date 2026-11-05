package com.zhigang.platform.api;

import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.repository.PlatformDataProvider.PublicDataStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class PublicOverviewIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlatformDataProvider dataProvider;

    @BeforeEach
    void setUp() {
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-01T12:00:00+08:00");
        List<RawDocument> publishedDocuments = IntStream.range(0, 317).mapToObj(index -> new RawDocument(
                index + 1L, "TIER_1", "来源" + index % 5, "OPEN", "企业" + index % 87,
                "城市", "软件", "初中级", "岗位" + index, "", "公开岗位资料", publishedAt,
                "hash-" + index, false)).toList();
        when(dataProvider.findPublishedDiscoveryDocuments()).thenReturn(publishedDocuments);
        when(dataProvider.publicDataStats()).thenReturn(new PublicDataStats(317, 0, publishedAt));
        when(dataProvider.findDocuments()).thenReturn(publishedDocuments.subList(0, 35));
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(dataProvider.findSkills()).thenReturn(List.of());
        when(dataProvider.findLearningResources()).thenReturn(List.of());
        when(dataProvider.dataMode()).thenReturn("REAL");
        when(dataProvider.operatingMode()).thenReturn("MYSQL_FACT_SOURCE");
    }

    @Test
    void publicOverviewReturnsAllPromotedCompetitionDocuments() throws Exception {
        mockMvc.perform(get("/api/public/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sampleCount").value(317))
                .andExpect(jsonPath("$.data.companyCount").value(87));
    }

    @Test
    void publicDataVersionIsAvailableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/public/data-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").isString())
                .andExpect(jsonPath("$.data.version").isNotEmpty());
    }
}
