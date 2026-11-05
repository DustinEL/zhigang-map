package com.zhigang.platform.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.api.Requests.DataImportRequest;
import com.zhigang.platform.common.BusinessException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DataImportFileService {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final List<String> CONTENT_FIELDS = List.of("raw_text", "content", "text", "jd_text", "description");
    private final ObjectMapper objectMapper;

    public DataImportFileService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DataImportRequest parse(MultipartFile file, String sourceType, String sourceName, String licenseStatus) {
        validate(file);
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<String> documents = fileName.endsWith(".csv") ? parseCsv(content) : parseJson(content);
            if (documents.isEmpty()) throw new BusinessException("EMPTY_IMPORT_FILE", "文件中没有可导入的岗位正文");
            return new DataImportRequest(sourceType, sourceName, licenseStatus, documents);
        } catch (IOException exception) {
            throw new BusinessException("IMPORT_FILE_READ_FAILED", "数据文件读取失败");
        }
    }

    private List<String> parseCsv(String content) throws IOException {
        List<String> documents = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setTrim(true).build().parse(new StringReader(content))) {
            Map<String, Integer> headers = parser.getHeaderMap();
            String contentField = CONTENT_FIELDS.stream().filter(headers::containsKey).findFirst()
                    .orElseThrow(() -> new BusinessException("IMPORT_CONTENT_FIELD_MISSING", "CSV 必须包含 raw_text、content、text、jd_text 或 description 字段"));
            for (CSVRecord record : parser) addDocument(documents, record.get(contentField));
        }
        return List.copyOf(documents);
    }

    private List<String> parseJson(String content) throws IOException {
        JsonNode root = objectMapper.readTree(content);
        JsonNode items = root.isArray() ? root : root.path("documents");
        if (!items.isArray()) throw new BusinessException("INVALID_IMPORT_JSON", "JSON 必须是数组，或包含 documents 数组");
        List<String> documents = new ArrayList<>();
        Iterator<JsonNode> iterator = items.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            if (item.isTextual()) {
                addDocument(documents, item.asText());
                continue;
            }
            String text = CONTENT_FIELDS.stream().map(item::path).filter(JsonNode::isTextual).map(JsonNode::asText).findFirst().orElse("");
            addDocument(documents, text);
        }
        return List.copyOf(documents);
    }

    private void addDocument(List<String> documents, String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() >= 20 && normalized.length() <= 100_000) documents.add(normalized);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("EMPTY_IMPORT_FILE", "请选择 CSV 或 JSON 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("IMPORT_FILE_TOO_LARGE", "数据文件不能超过 5MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv") && !name.endsWith(".json")) {
            throw new BusinessException("UNSUPPORTED_IMPORT_FILE", "仅支持 CSV 或 JSON 文件");
        }
    }
}
