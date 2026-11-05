package com.zhigang.platform.career;

import com.zhigang.platform.common.BusinessException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ResumeFileService {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> PDF_TYPES = Set.of("application/pdf", "application/octet-stream");
    private static final Set<String> DOCX_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream"
    );
    private final ResumeOcrClient ocrClient;

    public ResumeFileService(ResumeOcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public String extractText(MultipartFile file) {
        return extract(file).text();
    }

    public ExtractedResumeDocument extract(MultipartFile file) {
        validateBasic(file);
        try {
            byte[] content = file.getBytes();
            String extension = extension(file.getOriginalFilename());
            if ("pdf".equals(extension)) {
                validatePdf(file.getContentType(), content);
            } else {
                validateDocx(file.getContentType(), content);
            }
            String fileName = safeName(file.getOriginalFilename());
            String text;
            String parsingMode;
            if ("docx".equals(extension)) {
                // Preserve DOCX paragraph boundaries so downstream evidence rules can
                // distinguish skills, projects, and work experience reliably.
                try {
                    text = parseDocxBody(content);
                    parsingMode = "DOCX_XML";
                } catch (TikaException exception) {
                    try {
                        text = parse(content, fileName);
                    } catch (SAXException fallbackException) {
                        throw new TikaException("DOCX fallback parsing failed", fallbackException);
                    }
                    parsingMode = "APACHE_TIKA";
                }
            } else {
                try {
                    text = parse(content, fileName);
                } catch (TikaException | SAXException exception) {
                    text = "";
                }
                parsingMode = "APACHE_TIKA";
            }
            if (meaningful(text)) {
                return new ExtractedResumeDocument(text.trim(), parsingMode, fileName, sha256(content));
            }
            if ("pdf".equals(extension)) {
                ResumeOcrClient.OcrResult result = ocrClient.extractPdf(content, fileName);
                return new ExtractedResumeDocument(result.text(), "PADDLE_OCR", fileName, sha256(content));
            }
            throw new BusinessException("EMPTY_RESUME_TEXT", "未提取到 DOCX 正文，请确认文件未损坏");
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | TikaException exception) {
            throw new BusinessException("RESUME_PARSE_FAILED",
                    "文件解析失败，请确认 PDF/DOCX 为可复制文字且未损坏", exception);
        }
    }

    private void validateBasic(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_RESUME_FILE", "请选择需要解析的简历文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("RESUME_FILE_TOO_LARGE", "简历文件不能超过 10MB");
        }
        String extension = extension(file.getOriginalFilename());
        if (!"pdf".equals(extension) && !"docx".equals(extension)) {
            throw new BusinessException("UNSUPPORTED_RESUME_FILE", "仅支持 PDF 或 DOCX 简历");
        }
    }

    private void validatePdf(String contentType, byte[] content) {
        if (!PDF_TYPES.contains(normalizeType(contentType)) || content.length < 5
                || content[0] != '%' || content[1] != 'P' || content[2] != 'D' || content[3] != 'F' || content[4] != '-') {
            throw new BusinessException("INVALID_PDF_FILE", "文件扩展名与 PDF 内容不一致");
        }
    }

    private void validateDocx(String contentType, byte[] content) throws IOException {
        if (!DOCX_TYPES.contains(normalizeType(contentType)) || content.length < 4
                || content[0] != 'P' || content[1] != 'K') {
            throw new BusinessException("INVALID_DOCX_FILE", "文件扩展名与 DOCX 内容不一致");
        }
        boolean hasDocument = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if ("word/document.xml".equals(name)) hasDocument = true;
                if (name.contains("vbaproject.bin") || name.contains("/embeddings/") || name.contains("oleobject")) {
                    throw new BusinessException("UNSAFE_DOCX_FILE", "DOCX 包含宏或嵌入对象，已拒绝解析");
                }
            }
        }
        if (!hasDocument) {
            throw new BusinessException("INVALID_DOCX_FILE", "DOCX 缺少正文内容");
        }
    }

    private String parse(byte[] content, String fileName) throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(100_000);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            parser.parse(input, handler, metadata, new ParseContext());
        }
        return handler.toString().trim();
    }

    private String parseDocxBody(byte[] content) throws IOException, TikaException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equalsIgnoreCase(entry.getName())) {
                    return readWordDocumentXml(zip);
                }
            }
        }
        throw new TikaException("DOCX body is missing");
    }

    private String readWordDocumentXml(ZipInputStream input) throws TikaException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            boolean inText = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "t".equals(reader.getLocalName())) {
                    inText = true;
                } else if (event == XMLStreamConstants.START_ELEMENT
                        && "br".equals(reader.getLocalName())) {
                    // Word line breaks are inline elements, not paragraphs.
                    appendBoundary(text);
                } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "t".equals(reader.getLocalName())) {
                    inText = false;
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "p".equals(reader.getLocalName())) {
                    appendBoundary(text);
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "tc".equals(reader.getLocalName())) {
                    appendBoundary(text);
                }
            }
            reader.close();
            return text.toString().trim();
        } catch (XMLStreamException exception) {
            throw new TikaException("Cannot parse DOCX body", exception);
        }
    }

    private void appendBoundary(StringBuilder text) {
        if (text.length() == 0 || text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
    }

    private boolean meaningful(String text) {
        if (text == null || text.isBlank()) return false;
        long meaningfulCharacters = text.codePoints().filter(value -> Character.isLetterOrDigit(value)
                || Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN).count();
        return meaningfulCharacters >= 20;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String extension(String fileName) {
        String safe = safeName(fileName);
        int dot = safe.lastIndexOf('.');
        return dot < 0 ? "" : safe.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeName(String fileName) {
        if (fileName == null) return "resume";
        return fileName.replace('\\', '/').substring(fileName.replace('\\', '/').lastIndexOf('/') + 1);
    }

    private String normalizeType(String contentType) {
        return contentType == null ? "application/octet-stream" : contentType.toLowerCase(Locale.ROOT);
    }

    public record ExtractedResumeDocument(String text, String parsingMode, String fileName, String contentHash) {
    }
}
