package com.zhigang.platform.career;

import com.zhigang.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.zhigang.platform.domain.PlatformModels.ResumeFact;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeFileServiceTest {
    private final ResumeOcrClient ocrClient = mock(ResumeOcrClient.class);
    private final ResumeFileService service = new ResumeFileService(ocrClient);

    @Test
    void parsesFrozenBlindDocx() throws Exception {
        Path path = Path.of("..", "test-data", "matching", "final-blind-intake-v3",
                "resumes", "FMB-001.docx");
        byte[] content = Files.readAllBytes(path);
        var file = new MockMultipartFile("file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        assertThat(service.extractText(file)).isNotBlank();
    }

    @Test
    void preservesWorkEvidenceInRealBlindDocx() throws Exception {
        Path path = Path.of("..", "测试简历_下一轮独立评测", "09_匿名候选人",
                "09_匿名候选人_已脱敏简历.docx");
        if (!Files.isRegularFile(path)) return;
        byte[] content = Files.readAllBytes(path);
        var file = new MockMultipartFile("file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);
        ResumeFileService.ExtractedResumeDocument document = service.extract(file);
        int offset = document.text().indexOf("Spring Boot");
        assertThat(offset).isGreaterThanOrEqualTo(0);
        ResumeFact fact = new ResumeFact("SKILL", "Spring Boot", "Spring Boot", offset,
                offset + "Spring Boot".length(), "using Spring Boot and Kotlin", 0,
                "SELF_REPORT", "PENDING_VERIFICATION", 1.0);
        var evidence = new ResumeEvidenceExtractor().extract(document.text(), java.util.List.of(fact)).evidence();
        assertThat(evidence).anySatisfy(item -> {
            assertThat(item.skill()).isEqualTo("Spring Boot");
            assertThat(item.contextType()).isEqualTo("PRODUCTION");
            assertThat(item.evidenceLevel()).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void rejectsUnsupportedOversizedAndForgedFiles() {
        var unsupported = new MockMultipartFile("file", "resume.txt", "text/plain", "resume".getBytes(StandardCharsets.UTF_8));
        var oversized = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1]);
        var forgedPdf = new MockMultipartFile("file", "resume.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));
        var forgedDocx = new MockMultipartFile("file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "not a docx".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.extractText(unsupported)).isInstanceOf(BusinessException.class).hasMessageContaining("PDF 或 DOCX");
        assertThatThrownBy(() -> service.extractText(oversized)).isInstanceOf(BusinessException.class).hasMessageContaining("10MB");
        assertThatThrownBy(() -> service.extractText(forgedPdf)).isInstanceOf(BusinessException.class).hasMessageContaining("PDF 内容不一致");
        assertThatThrownBy(() -> service.extractText(forgedDocx)).isInstanceOf(BusinessException.class).hasMessageContaining("DOCX 内容不一致");
    }

    @Test
    void rejectsDocxMacrosAndEmbeddedObjects() throws Exception {
        var macro = new MockMultipartFile("file", "resume.docx", "application/octet-stream",
                docxWith("word/document.xml", "word/vbaProject.bin"));
        var embedded = new MockMultipartFile("file", "resume.docx", "application/octet-stream",
                docxWith("word/document.xml", "word/embeddings/object1.bin"));

        assertThatThrownBy(() -> service.extractText(macro)).isInstanceOf(BusinessException.class).hasMessageContaining("宏或嵌入对象");
        assertThatThrownBy(() -> service.extractText(embedded)).isInstanceOf(BusinessException.class).hasMessageContaining("宏或嵌入对象");
    }

    @Test
    void preservesExplicitLineBreaksInDocxBody() throws Exception {
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>测试分类</w:t><w:br/><w:t>iOS / 音视频开发负责人</w:t>"
                + "</w:r></w:p><w:p><w:r><w:t>专业技能与项目经验说明</w:t></w:r></w:p></w:body></w:document>";
        var file = new MockMultipartFile("file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxWithContent(documentXml));

        assertThat(service.extractText(file)).isEqualTo("测试分类\niOS / 音视频开发负责人\n专业技能与项目经验说明");
    }

    @Test
    void preservesTableCellBoundariesInDocxBody() throws Exception {
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>测试分类</w:t></w:r></w:p></w:tc>"
                + "<w:tc><w:p><w:r><w:t>iOS / 音视频开发负责人技能经验说明</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>";
        var file = new MockMultipartFile("file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxWithContent(documentXml));

        assertThat(service.extractText(file)).isEqualTo("测试分类\niOS / 音视频开发负责人技能经验说明");
    }

    @Test
    void fallsBackToPaddleOcrForScannedPdf() {
        byte[] content = "%PDF-1.4\nscanned-image-only".getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "scan.pdf", "application/pdf", content);
        when(ocrClient.extractPdf(content, "scan.pdf")).thenReturn(new ResumeOcrClient.OcrResult(
                "学历：本科\n专业：软件工程\n项目：使用 Java 完成接口测试，测试通过 18/18。", 1, "PaddleOCR-2.9.1"));

        ResumeFileService.ExtractedResumeDocument document = service.extract(file);

        assertThat(document.parsingMode()).isEqualTo("PADDLE_OCR");
        assertThat(document.text()).contains("Java", "18/18");
        assertThat(document.contentHash()).hasSize(64);
    }

    private byte[] docxWith(String... names) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("content".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private byte[] docxWithContent(String documentXml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
