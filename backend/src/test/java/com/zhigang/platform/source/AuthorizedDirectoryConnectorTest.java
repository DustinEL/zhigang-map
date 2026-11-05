package com.zhigang.platform.source;

import com.zhigang.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizedDirectoryConnectorTest {

    @TempDir
    Path directory;

    @Test
    void scansAllowedFilesAndSkipsUnchangedContent() throws Exception {
        Files.writeString(directory.resolve("rag-job.txt"), "岗位名称：RAG 应用开发工程师\n负责知识库问答系统，要求 Python 和 RAG。\n");
        Files.writeString(directory.resolve("ignore.exe"), "not an authorized text document");
        AuthorizedDirectoryConnector connector = new AuthorizedDirectoryConnector(new InMemorySourceImportRepository(), directory.toString());

        var first = connector.scan("测试授权目录", "AUTHORIZED");
        var second = connector.scan("测试授权目录", "AUTHORIZED");

        assertThat(first.acceptedCount()).isEqualTo(1);
        assertThat(first.skippedCount()).isEqualTo(1);
        assertThat(second.acceptedCount()).isZero();
        assertThat(second.skipped()).anyMatch(value -> value.contains("内容未变化"));
    }

    @Test
    void rejectsScanWhenDirectoryIsNotConfigured() {
        AuthorizedDirectoryConnector connector = new AuthorizedDirectoryConnector(new InMemorySourceImportRepository(), "");

        assertThatThrownBy(() -> connector.scan("测试", "AUTHORIZED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ZHIGANG_IMPORT_DIRECTORY");
    }
}
