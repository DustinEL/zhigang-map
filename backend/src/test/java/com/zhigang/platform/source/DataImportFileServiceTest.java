package com.zhigang.platform.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataImportFileServiceTest {
    private final DataImportFileService service = new DataImportFileService(new ObjectMapper());

    @Test
    void parsesCsvAndJsonDocumentCollections() {
        var csv = new MockMultipartFile("file", "jobs.csv", "text/csv",
                ("company,raw_text\n甲公司,负责企业知识库问答系统并要求掌握 Python 和 RAG\n"
                        + "乙公司,负责 Java 后端服务和 MySQL 数据库设计与性能优化\n").getBytes(StandardCharsets.UTF_8));
        var json = new MockMultipartFile("file", "jobs.json", "application/json",
                "{\"documents\":[{\"content\":\"负责智能体任务规划、工具调用和模型评测能力建设\"},\"负责云原生 Java 服务和容器化部署能力建设\"]}"
                        .getBytes(StandardCharsets.UTF_8));

        var csvRequest = service.parse(csv, "RECRUITMENT_JD", "授权 CSV", "AUTHORIZED");
        var jsonRequest = service.parse(json, "RECRUITMENT_JD", "授权 JSON", "AUTHORIZED");

        assertThat(csvRequest.documents()).hasSize(2);
        assertThat(jsonRequest.documents()).hasSize(2);
        assertThat(csvRequest.sourceName()).isEqualTo("授权 CSV");
    }

    @Test
    void rejectsUnsupportedAndStructurallyInvalidFiles() {
        var text = new MockMultipartFile("file", "jobs.txt", "text/plain", "岗位正文".getBytes(StandardCharsets.UTF_8));
        var missingField = new MockMultipartFile("file", "jobs.csv", "text/csv",
                "company,title\n甲公司,Java开发\n".getBytes(StandardCharsets.UTF_8));
        var invalidJson = new MockMultipartFile("file", "jobs.json", "application/json",
                "{\"items\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.parse(text, "JD", "来源", "AUTHORIZED"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("CSV 或 JSON");
        assertThatThrownBy(() -> service.parse(missingField, "JD", "来源", "AUTHORIZED"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须包含");
        assertThatThrownBy(() -> service.parse(invalidJson, "JD", "来源", "AUTHORIZED"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("documents 数组");
    }

    @Test
    void rejectsEmptyEffectiveDocumentCollection() {
        var file = new MockMultipartFile("file", "jobs.json", "application/json",
                "[\"太短\",{\"content\":\"仍然太短\"}]".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.parse(file, "JD", "来源", "AUTHORIZED"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有可导入");
    }
}
