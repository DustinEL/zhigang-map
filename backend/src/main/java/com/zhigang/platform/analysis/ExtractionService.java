package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.ai.ModelGateway;
import com.zhigang.platform.domain.PlatformModels.ExtractedEvidence;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.domain.PlatformModels.ExtractionMetrics;
import com.zhigang.platform.domain.PlatformModels.ExtractionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ExtractionService {
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?:岗位名称|职位名称|岗位)[:：]\\s*([^\\n]+)");
    private static final Pattern SALARY_PATTERN = Pattern.compile("(\\d+)\\s*[kK]\\s*[-~至]\\s*(\\d+)\\s*[kK]");
    private static final Pattern LEVEL_PATTERN = Pattern.compile("(实习|初级|中级|高级|专家)");
    private static final Pattern RESPONSIBILITY_PATTERN = Pattern.compile("(?:^|\\n)\\s*(?:[\\d一二三四五六七八九十]+[.、．]|（[\\d一二三四五六七八九十]+）|[-•●])?\\s*(.{2,500}?)(?=(?:\\n\\s*(?:[\\d一二三四五六七八九十]+[.、．]|（[\\d一二三四五六七八九十]+）|[-•●])\\s*)|$)", Pattern.DOTALL);
    private static final Pattern RESPONSIBILITY_VERB_PATTERN = Pattern.compile("负责|参与|承担|建设|设计|开发|优化|维护|研究|评测|测试|管理|支持|搭建|实现|推动|探索|跟进|制定|完成|协作|驱动|构建|使用|对接|配合|落地|规划|主导|协调|关注|分析|保障|确保|保证|提升|持续|合作|统筹|领导|带领|解决|沉淀");
    private static final Pattern RESPONSIBILITY_HEADING_PATTERN = Pattern.compile("岗位职责|职位职责|工作职责|主要职责|工作内容|岗位描述", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIREMENT_HEADING_PATTERN = Pattern.compile("任职要求|岗位要求|任职资格|职位要求|任职条件|教育背景|专业技能|招聘要求|我们希望", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALIFICATION_PREFIX_PATTERN = Pattern.compile("^(?:学历|本科|硕士|博士|具有?\\d*年|\\d+年以上|熟悉|精通|掌握|具备|了解|要求|优先|有.{0,12}经验)");
    private static final Pattern SCENARIO_PATTERN = Pattern.compile("(?:行业场景|应用场景|业务场景)[:：]\\s*([^\\n。；;]{2,160})");
    private static final Pattern SCENARIO_ITEM_PATTERN = Pattern.compile("[^、，,/；;]{2,60}");
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile("^[\\d０-９一二三四五六七八九十、.．()（）\\s]+");
    private static final Pattern PROMPT_INJECTION_PATTERN = Pattern.compile(
            "忽略(?:前述|以上|之前)|系统提示|泄露.*提示|ignore\\s+previous|system\\s+prompt|developer\\s+message",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> MODEL_ROOT_FIELDS = Set.of(
            "title", "salary", "level", "facts", "responsibilities", "scenarios");
    private static final Set<String> MODEL_ITEM_FIELDS = Set.of(
            "rawValue", "normalizedValue", "requirementType", "startOffset", "endOffset", "evidenceQuote", "confidence");
    private static final Set<String> REQUIREMENT_TYPES = Set.of("REQUIRED", "BONUS", "MENTIONED", "NEGATED");
    private static final Set<String> GENERIC_MODEL_SKILLS = Set.of(
            "AI", "Agent", "大模型", "LLM", "智能体", "工程能力", "逻辑思维", "系统设计", "软件开发", "算法工程");
    private static final Set<String> LOW_PRECISION_JD_SKILLS = Set.of(
            "Android", "C", "DeepSpeed", "MPI", "Megatron", "Objective-C", "Swift", "TCP/IP", "Triton",
            "ZeRO", "iOS", "vLLM", "平台开发", "数据开发", "状态机", "算法工程", "网络安全", "面向对象");
    private static final String SKILL_MODEL_JSON_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["skills"],"properties":{"skills":{"type":"array","items":{"type":"string"}}}}
            """;
    private static final String MODEL_JSON_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["title","salary","level","facts","responsibilities","scenarios"],
              "properties":{
                "title":{"type":["string","null"]},
                "salary":{"type":["string","null"]},
                "level":{"type":["string","null"]},
                "facts":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["rawValue","normalizedValue","requirementType","startOffset","endOffset","evidenceQuote","confidence"],
                  "properties":{"rawValue":{"type":"string"},"normalizedValue":{"type":"string"},
                    "requirementType":{"enum":["REQUIRED","BONUS","MENTIONED","NEGATED"]},
                    "startOffset":{"type":"integer","minimum":0},"endOffset":{"type":"integer","minimum":1},
                    "evidenceQuote":{"type":"string"},"confidence":{"type":"number","minimum":0,"maximum":1}}}},
                "responsibilities":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["rawValue","normalizedValue","startOffset","endOffset","evidenceQuote","confidence"],
                  "properties":{"rawValue":{"type":"string"},"normalizedValue":{"type":"string"},
                    "startOffset":{"type":"integer","minimum":0},"endOffset":{"type":"integer","minimum":1},
                    "evidenceQuote":{"type":"string"},"confidence":{"type":"number","minimum":0,"maximum":1}}}},
                "scenarios":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["rawValue","normalizedValue","startOffset","endOffset","evidenceQuote","confidence"],
                  "properties":{"rawValue":{"type":"string"},"normalizedValue":{"type":"string"},
                    "startOffset":{"type":"integer","minimum":0},"endOffset":{"type":"integer","minimum":1},
                    "evidenceQuote":{"type":"string"},"confidence":{"type":"number","minimum":0,"maximum":1}}}}
              }
            }
            """;

    private final JdSkillExtractor jdSkillExtractor;
    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final ExtractionExceptionQueue exceptionQueue;
    private final ExtractionGuardMetrics guardMetrics;
    private final String policyVersion;

    @Autowired
    public ExtractionService(
            JdSkillExtractor jdSkillExtractor,
            ModelGateway modelGateway,
            ObjectMapper objectMapper,
            ExtractionExceptionQueue exceptionQueue,
            ExtractionGuardMetrics guardMetrics,
            @Value("${zhigang.analysis.policy-version:v1.0.0}") String policyVersion
    ) {
        this.jdSkillExtractor = jdSkillExtractor;
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.exceptionQueue = exceptionQueue;
        this.guardMetrics = guardMetrics;
        this.policyVersion = policyVersion;
    }

    public ExtractionService(SkillNormalizationService normalizationService, String policyVersion) {
        this(new JdSkillExtractor(new JdSkillPolicy(), new JdCandidateVocabulary()),
                new UnavailableModelGateway(), new ObjectMapper(),
                new ExtractionExceptionQueue(), new ExtractionGuardMetrics(), policyVersion);
    }

    public ExtractionService(SkillNormalizationService normalizationService, ModelGateway modelGateway,
                             ObjectMapper objectMapper, ExtractionExceptionQueue exceptionQueue,
                             ExtractionGuardMetrics guardMetrics, String policyVersion) {
        this(new JdSkillExtractor(new JdSkillPolicy(), new JdCandidateVocabulary()), modelGateway, objectMapper,
                exceptionQueue, guardMetrics, policyVersion);
    }

    public ExtractionResult extractJobDescription(String text) {
        return extractJobDescription(stableDocumentId(text), text);
    }

    public ExtractionResult extractJobDescription(long documentId, String text) {
        String sourceText = text == null ? "" : text;
        List<String> warnings = new ArrayList<>();
        boolean injectionAttempt = containsPromptInjection(sourceText);
        if (injectionAttempt) {
            warnings.add("检测到提示注入式指令，已拦截模型调用并仅执行确定性规则抽取。");
        }

        String title = extractTitle(sourceText);
        String salary = salary(sourceText);
        String level = firstMatch(LEVEL_PATTERN, sourceText, "未明确");
        List<ExtractedFact> facts = new ArrayList<>(jdSkillExtractor.extract(documentId, sourceText).stream()
                .filter(fact -> !"NEGATED".equals(fact.requirementType()))
                .toList());
        List<AnchoredValue> responsibilities = new ArrayList<>(extractResponsibilities(sourceText));
        List<AnchoredValue> scenarios = new ArrayList<>(extractScenarios(sourceText));

        int quarantined = 0;
        boolean modelAttempted = false;
        boolean modelAccepted = false;
        if (!injectionAttempt && needsModel(title, salary, level, facts, responsibilities, scenarios)) {
            modelAttempted = true;
            Optional<String> output = modelGateway.generateStructured(
                    modelTask(title, salary, level, facts, responsibilities, scenarios), sourceText, MODEL_JSON_SCHEMA);
            if (output.isPresent()) {
                ModelMergeResult merged = mergeModelOutput(documentId, sourceText, output.get(), title, salary, level,
                        facts, responsibilities, scenarios, warnings);
                title = merged.title();
                salary = merged.salary();
                level = merged.level();
                quarantined = merged.quarantined();
                modelAccepted = modelAccepted || merged.accepted();
            }
        }

        List<ExtractedEvidence> evidence = evidence(documentId, facts, responsibilities, scenarios);
        int conclusions = facts.size() + responsibilities.size() + scenarios.size();
        boolean unsupported = conclusions == 0;
        if (facts.isEmpty()) warnings.add("未识别到技能事实，请补充任职要求或进入人工复核。");
        if (unsupported) warnings.add("证据不足：没有可校验的职责、技能或行业场景，系统拒绝生成岗位结论。");
        if (quarantined > 0) warnings.add("有 " + quarantined + " 个模型字段因证据偏移不一致进入异常队列，未发布到结果。");

        String modelMode = modelAttempted
                ? (modelAccepted ? "RULE_FIRST_SCHEMA_MODEL" : "RULE_EVIDENCE_FALLBACK")
                : "RULE_FIRST_OFFLINE";
        ExtractionMetrics metrics = guardMetrics.record(new ExtractionGuardMetrics.Observation(
                conclusions, evidence.size(), evidence.size() + quarantined, evidence.size(), unsupported,
                unsupported, injectionAttempt, injectionAttempt));
        return new ExtractionResult(
                title,
                salary,
                level,
                List.copyOf(facts),
                responsibilities.stream().map(AnchoredValue::normalizedValue).toList(),
                scenarios.stream().map(AnchoredValue::normalizedValue).toList(),
                evidence,
                List.copyOf(warnings),
                policyVersion,
                modelMode,
                unsupported ? "证据不足" : "仅可发布证据化字段",
                metrics
        );
    }

    public ExtractionMetrics metrics() {
        return guardMetrics.snapshot();
    }

    public List<ExtractionExceptionQueue.ExtractionException> recentExceptions(int limit) {
        return exceptionQueue.recent(limit);
    }

    private ModelMergeResult mergeModelOutput(long documentId, String sourceText, String json,
                                              String currentTitle, String currentSalary, String currentLevel,
                                              List<ExtractedFact> facts, List<AnchoredValue> responsibilities,
                                              List<AnchoredValue> scenarios, List<String> warnings) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception exception) {
            warnings.add("模型输出未通过 JSON Schema：返回内容不是合法 JSON，已降级为规则结果。");
            return new ModelMergeResult(currentTitle, currentSalary, currentLevel, 0, false);
        }
        if (!validRoot(root)) {
            warnings.add("模型输出未通过 JSON Schema：字段缺失、类型错误或包含未声明字段，已降级为规则结果。");
            return new ModelMergeResult(currentTitle, currentSalary, currentLevel, 0, false);
        }

        String title = "待确认岗位".equals(currentTitle) ? nullableText(root.get("title"), currentTitle) : currentTitle;
        String salary = "未明确".equals(currentSalary) ? nullableText(root.get("salary"), currentSalary) : currentSalary;
        String level = "未明确".equals(currentLevel) ? nullableText(root.get("level"), currentLevel) : currentLevel;
        int quarantined = 0;

        Set<String> knownSkills = new HashSet<>();
        facts.forEach(fact -> knownSkills.add(fact.normalizedValue().toLowerCase(Locale.ROOT)));
        for (JsonNode item : root.get("facts")) {
            ModelItem parsed = parseItem(item, "SKILL");
            if (parsed == null || !validEvidence(sourceText, parsed)) {
                quarantined += quarantine(documentId, "SKILL", rawValue(item), "模型字段未通过 Schema 或原文偏移校验");
                continue;
            }
            Optional<String> canonical = jdSkillExtractor.reviewedCanonical(
                    parsed.rawValue(), parsed.normalizedValue());
            if (canonical.isEmpty()) {
                quarantined += quarantine(documentId, "SKILL", parsed.rawValue(), "模型技能未通过技能白名单校验");
                continue;
            }
            if ("NEGATED".equals(parsed.requirementType())) continue;
            String normalized = canonical.get();
            if (knownSkills.add(normalized.toLowerCase(Locale.ROOT))) {
                facts.add(new ExtractedFact("SKILL", parsed.rawValue(), normalized,
                        parsed.requirementType(), documentId, parsed.startOffset(), parsed.endOffset(),
                        parsed.evidenceQuote(), parsed.confidence(), "EVIDENCE_VALID"));
            }
        }
        quarantined += mergeAnchoredItems(documentId, sourceText, root.get("responsibilities"),
                "RESPONSIBILITY", responsibilities);
        quarantined += mergeAnchoredItems(documentId, sourceText, root.get("scenarios"), "SCENARIO", scenarios);
        return new ModelMergeResult(title, salary, level, quarantined, true);
    }

    private int mergeModelSkills(long documentId, String sourceText, String json,
                                 List<ExtractedFact> facts, List<String> warnings) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception exception) {
            warnings.add("技能补充模型返回了非法 JSON，已保留规则结果。");
            return 0;
        }
        if (!root.isObject() || root.size() != 1 || !root.path("skills").isArray()) return 0;
        Set<String> known = new HashSet<>();
        facts.forEach(fact -> known.add(fact.normalizedValue().toLowerCase(Locale.ROOT)));
        int quarantined = 0;
        for (JsonNode item : root.path("skills")) {
            String raw;
            String normalized;
            String requirementType;
            if (item.isTextual()) {
                raw = item.asText("").trim();
                Optional<String> canonical = jdSkillExtractor.canonicalize(raw);
                if (canonical.isEmpty() && GENERIC_MODEL_SKILLS.contains(raw)) {
                    quarantined++;
                    continue;
                }
                normalized = canonical.orElse(raw);
                requirementType = "MENTIONED";
            } else if (item.isObject()) {
                raw = item.path("rawValue").asText("").trim();
                normalized = item.path("normalizedValue").asText(raw).trim();
                requirementType = item.path("requirementType").asText("MENTIONED");
            } else {
                quarantined++;
                continue;
            }
            int start = sourceText.indexOf(raw);
            if (raw.isBlank() || normalized.isBlank() || start < 0 || !REQUIREMENT_TYPES.contains(requirementType)) {
                quarantined++;
                continue;
            }
            if ("NEGATED".equals(requirementType)) continue;
            if (known.add(normalized.toLowerCase(Locale.ROOT))) {
                facts.add(new ExtractedFact("SKILL", raw, normalized, requirementType, documentId,
                        start, start + raw.length(), raw, 0.92, "EVIDENCE_VALID"));
            }
        }
        return quarantined;
    }

    private String skillModelSource(String sourceText) {
        Matcher heading = REQUIREMENT_HEADING_PATTERN.matcher(sourceText);
        if (!heading.find()) return sourceText;
        Matcher title = TITLE_PATTERN.matcher(sourceText);
        String titleLine = title.find() ? title.group() + "\n" : "";
        return titleLine + sourceText.substring(heading.start());
    }

    private int mergeAnchoredItems(long documentId, String sourceText, JsonNode items, String type,
                                   List<AnchoredValue> target) {
        Set<String> known = new HashSet<>();
        target.forEach(value -> known.add(value.normalizedValue().toLowerCase(Locale.ROOT)));
        int quarantined = 0;
        for (JsonNode item : items) {
            ModelItem parsed = parseItem(item, type);
            if (parsed == null || !validEvidence(sourceText, parsed)) {
                quarantined += quarantine(documentId, type, rawValue(item), "模型字段未通过 Schema 或原文偏移校验");
                continue;
            }
            if ("RESPONSIBILITY".equals(type) && !isResponsibilityCandidate(parsed.normalizedValue(), sourceText)) {
                quarantined += quarantine(documentId, type, parsed.rawValue(), "模型职责位于任职要求或不符合职责候选规则");
                continue;
            }
            if (known.add(parsed.normalizedValue().toLowerCase(Locale.ROOT))) {
                target.add(new AnchoredValue(parsed.normalizedValue(), parsed.rawValue(), parsed.startOffset(),
                        parsed.endOffset(), parsed.evidenceQuote(), parsed.confidence(), "MODEL_SCHEMA"));
            }
        }
        return quarantined;
    }

    private boolean validRoot(JsonNode root) {
        if (root == null || !root.isObject()) return false;
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        if (!MODEL_ROOT_FIELDS.equals(fields)) return false;
        return nullableString(root.get("title")) && nullableString(root.get("salary"))
                && nullableString(root.get("level")) && root.get("facts").isArray()
                && root.get("responsibilities").isArray() && root.get("scenarios").isArray();
    }

    private ModelItem parseItem(JsonNode item, String type) {
        if (item == null || !item.isObject()) return null;
        Set<String> fields = new HashSet<>();
        item.fieldNames().forEachRemaining(fields::add);
        Set<String> expected = "SKILL".equals(type) ? MODEL_ITEM_FIELDS
                : Set.of("rawValue", "normalizedValue", "startOffset", "endOffset", "evidenceQuote", "confidence");
        if (!expected.equals(fields)) return null;
        JsonNode rawValue = item.get("rawValue");
        JsonNode normalizedValue = item.get("normalizedValue");
        JsonNode startOffset = item.get("startOffset");
        JsonNode endOffset = item.get("endOffset");
        JsonNode evidenceQuote = item.get("evidenceQuote");
        JsonNode confidence = item.get("confidence");
        if (!nonBlankText(rawValue) || !nonBlankText(normalizedValue) || !startOffset.canConvertToInt()
                || !endOffset.canConvertToInt() || !nonBlankText(evidenceQuote) || !confidence.isNumber()
                || confidence.asDouble() < 0 || confidence.asDouble() > 1) return null;
        String requirementType = "SKILL".equals(type) && nonBlankText(item.get("requirementType"))
                ? item.get("requirementType").asText() : "MENTIONED";
        if (!REQUIREMENT_TYPES.contains(requirementType)) return null;
        return new ModelItem(rawValue.asText(), normalizedValue.asText(), requirementType,
                startOffset.asInt(), endOffset.asInt(), evidenceQuote.asText(), confidence.asDouble());
    }

    private boolean validEvidence(String sourceText, ModelItem item) {
        return item.startOffset() >= 0 && item.endOffset() > item.startOffset()
                && item.endOffset() <= sourceText.length()
                && sourceText.substring(item.startOffset(), item.endOffset()).equals(item.rawValue())
                && item.evidenceQuote().equals(item.rawValue());
    }

    private int quarantine(long documentId, String type, String rawValue, String reason) {
        exceptionQueue.enqueue(documentId, type, rawValue, reason);
        return 1;
    }

    private String rawValue(JsonNode item) {
        return item != null && item.isObject() && item.get("rawValue") != null && item.get("rawValue").isTextual()
                ? item.get("rawValue").asText() : "";
    }

    private boolean nullableString(JsonNode value) {
        return value != null && (value.isNull() || value.isTextual());
    }

    private boolean nonBlankText(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank();
    }

    private String nullableText(JsonNode value, String fallback) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : fallback;
    }

    private List<ExtractedEvidence> evidence(long documentId, List<ExtractedFact> facts,
                                             List<AnchoredValue> responsibilities, List<AnchoredValue> scenarios) {
        List<ExtractedEvidence> result = new ArrayList<>();
        facts.forEach(fact -> result.add(new ExtractedEvidence("SKILL", fact.normalizedValue(), documentId,
                fact.startOffset(), fact.endOffset(), fact.evidenceQuote(), fact.confidence(),
                fact.gateStatus().equals("EVIDENCE_VALID") ? "RULE_OR_SCHEMA" : "UNKNOWN", fact.gateStatus())));
        responsibilities.forEach(value -> result.add(value.toEvidence(documentId, "RESPONSIBILITY")));
        scenarios.forEach(value -> result.add(value.toEvidence(documentId, "SCENARIO")));
        return List.copyOf(result);
    }

    private boolean needsModel(String title, String salary, String level, List<ExtractedFact> facts,
                               List<AnchoredValue> responsibilities, List<AnchoredValue> scenarios) {
        if (!facts.isEmpty() && !responsibilities.isEmpty()) return false;
        return "待确认岗位".equals(title) || "未明确".equals(salary) || "未明确".equals(level)
                || facts.isEmpty() || responsibilities.isEmpty();
    }

    private String modelTask(String title, String salary, String level, List<ExtractedFact> facts,
                             List<AnchoredValue> responsibilities, List<AnchoredValue> scenarios) {
        List<String> missing = new ArrayList<>();
        if ("待确认岗位".equals(title)) missing.add("岗位名称");
        if ("未明确".equals(salary)) missing.add("薪资");
        if ("未明确".equals(level)) missing.add("职级");
        if (facts.isEmpty()) missing.add("技能");
        if (responsibilities.isEmpty()) missing.add("职责");
        if (scenarios.isEmpty()) missing.add("行业场景");
        return "仅补充规则未覆盖的字段：" + String.join("、", missing)
                + "。所有数组项必须逐字引用原文，并给出 UTF-16 startOffset/endOffset；无法引用时返回空数组或 null。";
    }

    private String salary(String text) {
        Matcher matcher = SALARY_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) + "k-" + matcher.group(2) + "k" : "未明确";
    }

    private String firstMatch(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private String extractTitle(String text) {
        Matcher labeled = TITLE_PATTERN.matcher(text);
        if (labeled.find()) {
            return cleanTitle(labeled.group(1));
        }
        for (String line : text.replace("\r", "").split("\n")) {
            String candidate = cleanTitle(line);
            if (candidate.isBlank()) continue;
            if (RESPONSIBILITY_HEADING_PATTERN.matcher(candidate).find()
                    || REQUIREMENT_HEADING_PATTERN.matcher(candidate).find()) continue;
            return candidate;
        }
        return "待确认岗位";
    }

    private String cleanTitle(String value) {
        return value == null ? "" : value.trim()
                .replaceFirst("^[：:、，,\\s]+", "")
                .replaceFirst("[（(](?:急聘|校招|社招|实习|Agent)[^）)]*[）)]", "")
                .replaceFirst("(?:[-－—]\\s*(?:Developer|阿里巴巴))$", "")
                .replaceFirst("^腾讯视频[-－—]", "")
                .trim();
    }

    private List<AnchoredValue> extractResponsibilities(String text) {
        List<AnchoredValue> responsibilities = new ArrayList<>();
        String section = responsibilitySection(text);
        List<String> segments = responsibilitySegments(section);
        boolean shortLabelMode = usesShortResponsibilityLabels(segments);
        boolean fullLabelMode = usesCompactResponsibilityLabels(segments);
        int searchFrom = 0;
        for (String rawSegment : segments) {
            if (responsibilities.size() >= 12) break;
            String cleanedSegment = rawSegment.trim().replaceFirst("^[\\uFEFF\"'“”]+\\s*", "");
            boolean numberedDuty = cleanedSegment.matches(
                    "^(?:[\\d０-９一二三四五六七八九十]+[.、．]|[（(][\\d０-９一二三四五六七八九十]+[)）]).*");
            String normalized = LEADING_NUMBER_PATTERN.matcher(cleanedSegment).replaceFirst("")
                    .replaceFirst("^[-•●▪]+\\s*", "")
                    .replaceAll("[。；;\"'“”\\s]+$", "").trim();
            int separator = labelSeparator(normalized);
            if (separator >= 0 && !fullLabelMode && (numberedDuty || shortLabelMode)) {
                normalized = normalized.substring(0, separator).trim();
            } else if (shortLabelMode && !fullLabelMode) {
                continue;
            }
            normalized = preferredResponsibilityTranslation(normalized);
            if (!isResponsibilityCandidate(normalized, text)) continue;
            if (!numberedDuty && !RESPONSIBILITY_VERB_PATTERN.matcher(normalized).find()) continue;
            int offset = text.indexOf(normalized, searchFrom);
            if (offset < 0) offset = text.indexOf(normalized);
            searchFrom = Math.max(searchFrom, offset + normalized.length());
            if (!numberedDuty && (normalized.contains("；") || normalized.contains(";"))) {
                for (String item : normalized.split("[；;]")) {
                    String part = item.trim();
                    if (!part.isBlank() && RESPONSIBILITY_VERB_PATTERN.matcher(part).find()) {
                        responsibilities.add(new AnchoredValue(part, part, offset, offset + part.length(), part, 0.98, "RULE"));
                    }
                }
                continue;
            }
            if (!normalized.isBlank()) {
                responsibilities.add(new AnchoredValue(normalized, normalized,
                        offset, offset + normalized.length(), normalized, 0.98, "RULE"));
            }
        }
        return List.copyOf(responsibilities);
    }

    private boolean usesShortResponsibilityLabels(List<String> segments) {
        if (segments.size() < 4) return false;
        int labeled = 0;
        int unlabeled = 0;
        int longBodies = 0;
        int numbered = 0;
        for (String segment : segments) {
            if (segment.trim().matches("^(?:[\\d０-９一二三四五六七八九十]+[.、．]|[（(][\\d０-９一二三四五六七八九十]+[)）]).*")) {
                numbered++;
            }
            String value = LEADING_NUMBER_PATTERN.matcher(segment.trim()).replaceFirst("").trim();
            int separator = labelSeparator(value);
            if (separator < 0) {
                unlabeled++;
                continue;
            }
            String label = value.substring(0, separator).trim();
            String body = value.substring(separator + 1).trim();
            if (label.length() <= 30 && body.length() >= Math.max(12, label.length())) {
                labeled++;
                longBodies++;
            }
        }
        return numbered == segments.size() && numbered >= 4 && labeled >= 3
                && labeled * 2 >= segments.size() && unlabeled <= 1 && longBodies >= 3;
    }

    private boolean usesCompactResponsibilityLabels(List<String> segments) {
        if (segments.size() < 4) return false;
        int labeled = 0;
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.matches("^(?:[\\d０-９一二三四五六七八九十]+[.、．]|[（(][\\d０-９一二三四五六七八九十]+[)）]).*")) return false;
            String value = LEADING_NUMBER_PATTERN.matcher(trimmed).replaceFirst("").trim();
            int separator = labelSeparator(value);
            if (separator < 0) return false;
            String label = value.substring(0, separator).trim();
            String body = value.substring(separator + 1).trim();
            if (label.isBlank() || label.length() > 10 || body.length() < 12) return false;
            labeled++;
        }
        return labeled == segments.size();
    }

    private int labelSeparator(String value) {
        int chinese = value.indexOf('：');
        int ascii = value.indexOf(':');
        if (chinese < 0) return ascii;
        if (ascii < 0) return chinese;
        return Math.min(chinese, ascii);
    }

    private String preferredResponsibilityTranslation(String value) {
        int separator = value.lastIndexOf(". ");
        if (separator > 0 && value.substring(separator + 2).matches(".*[\\p{IsHan}].*")) {
            return value.substring(separator + 2).trim();
        }
        return value;
    }

    private List<String> responsibilitySegments(String section) {
        String segmented = section.replace("\r", "")
                .replaceAll("(?<=[。；;\\s])(?=(?:[（(][\\d０-９]+[)）]|[\\d０-９]+[)）.、．]))", "\n")
                .replaceAll("(?m)(^|\\s+)(?=[-·][\\p{IsHan}])", "$1\n");
        String[] lines = segmented.split("\n", -1);
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasBullet = false;
        boolean groupBullets = false;
        boolean currentIsNumbered = false;
        Pattern numbered = Pattern.compile("^\\s*(?:[\\d０-９一二三四五六七八九十]+[.、．]|[（(][\\d０-９一二三四五六七八九十]+[)）])\\s*.*");
        Pattern bullet = Pattern.compile("^\\s*[-·•●▪]+\\s*.*");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                if (!hasBullet && !currentIsNumbered && current.length() > 0) {
                    segments.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            if (numbered.matcher(trimmed).matches()) {
                if (current.length() > 0) segments.add(current.toString().trim());
                current.setLength(0); current.append(trimmed); hasBullet = false; groupBullets = false;
                currentIsNumbered = true;
            } else if (bullet.matcher(trimmed).matches()) {
                if (trimmed.startsWith("·")) {
                    if (current.length() > 0) segments.add(current.toString().trim());
                    current.setLength(0);
                    current.append(trimmed);
                    hasBullet = true;
                    groupBullets = false;
                    currentIsNumbered = false;
                    continue;
                }
                if (!hasBullet) groupBullets = current.length() > 0;
                if (current.length() > 0 && hasBullet && !groupBullets) segments.add(current.toString().trim());
                else if (current.length() > 0) current.append(" ");
                if (hasBullet && !groupBullets) current.setLength(0);
                current.append(trimmed); hasBullet = true;
            } else {
                if (current.length() > 0) current.append(" ");
                current.append(trimmed);
                currentIsNumbered = false;
            }
        }
        if (current.length() > 0) segments.add(current.toString().trim());
        List<String> sentenceSegments = new ArrayList<>();
        for (String segment : segments) {
            if (numbered.matcher(segment).matches() || segment.matches(".*(?:^|\\s)[-·•●▪]+.*")) {
                sentenceSegments.add(segment);
                continue;
            }
            for (String sentence : segment.split("(?<=。)(?=[\\p{IsHan}])")) {
                if (!sentence.isBlank()) sentenceSegments.add(sentence.trim());
            }
        }
        return sentenceSegments;
    }

    private String responsibilitySection(String text) {
        Matcher heading = RESPONSIBILITY_HEADING_PATTERN.matcher(text);
        if (!heading.find()) {
            Matcher requirement = REQUIREMENT_HEADING_PATTERN.matcher(text);
            if (requirement.find()) {
                String before = text.substring(0, requirement.start()).trim();
                String candidate = before.contains("\n") ? before.substring(before.indexOf('\n') + 1).trim() : "";
                if (countNumberedItems(candidate) >= 2) return candidate;
                return "";
            }
            if (looksLikeRequirementsOnly(text)) return "";
            String body = text.replaceFirst("(?m)^\\s*岗位名称[:：].*\\n", "")
                    .replaceFirst("(?m)^\\s*[^\\n]{2,80}\\n", "");
            if (countNumberedItems(body) < 2 && !body.matches("(?s).*；.*；.*")) return "";
            return body;
        }
        int start = heading.end();
        while (heading.find()) start = heading.end();
        Matcher end = REQUIREMENT_HEADING_PATTERN.matcher(text);
        String section = end.find(start) ? text.substring(start, end.start()) : text.substring(start);
        return section.replaceFirst("^\\s*[】]?[：:]?\\s*", "")
                .replaceFirst("\\s*[【]\\s*$", "");
    }

    private int countNumberedItems(String value) {
        Matcher matcher = Pattern.compile("(?:^|\\n)\\s*[\"'“”]?\\d+[.、．]").matcher(value);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private boolean looksLikeRequirementsOnly(String text) {
        Matcher inline = Pattern.compile(
                "(?:^|[；;\\s])\\d+[.、．]?\\s*(?:\\d+年(?:及)?以上|具有|具备|熟悉|熟练|精通|掌握|了解|本科|硕士|博士)")
                .matcher(text);
        int inlineQualifications = 0;
        while (inline.find()) inlineQualifications++;
        if (inlineQualifications >= 2) return true;
        String[] lines = text.replace("\r", "").split("\n");
        int qualificationLines = 0;
        int inspected = 0;
        Pattern qualification = Pattern.compile(
                "^\\s*\\d+[.、．]?\\s*(?:\\d+年以上|具有|具备|熟悉|精通|掌握|了解|有.+经验|本科|硕士|博士)");
        for (int index = 1; index < lines.length && inspected < 5; index++) {
            if (lines[index].isBlank()) continue;
            inspected++;
            if (qualification.matcher(lines[index]).find()) qualificationLines++;
        }
        return qualificationLines >= 2;
    }

    private boolean isResponsibilityCandidate(String value, String sourceText) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || QUALIFICATION_PREFIX_PATTERN.matcher(normalized).find()) return false;
        if (isNonCoreResponsibility(normalized)) return false;
        if (normalized.startsWith("岗位名称") || normalized.startsWith("职位名称")) return false;
        Matcher dutyHeading = RESPONSIBILITY_HEADING_PATTERN.matcher(sourceText);
        int dutyStart = dutyHeading.find() ? dutyHeading.end() : 0;
        Matcher heading = REQUIREMENT_HEADING_PATTERN.matcher(sourceText);
        return !heading.find() || heading.start() < dutyStart || sourceText.indexOf(normalized) < heading.start();
    }

    private boolean isNonCoreResponsibility(String value) {
        return value.contains("技术文档与规范")
                || value.contains("其他工作")
                || value.contains("带领、培养")
                || value.contains("编写相关技术文档，参与代码评审与团队知识共享")
                || value.contains("跟踪全球AI技术发展趋势")
                || value.contains("推动研究成果在顶级会议或期刊发表")
                || value.contains("参与科研项目的全过程管理")
                || value.contains("关注业界前沿发展")
                || value.contains("业界")
                || value.contains("学术影响力")
                || value.contains("市场趋势")
                || value.contains("运营策略")
                || value.contains("项目的策划、组织、实施与监控")
                || value.contains("PPT评估标准建设与迭代")
                || value.contains("Bad Case");
    }

    private List<AnchoredValue> extractScenarios(String text) {
        Map<String, AnchoredValue> scenarios = new LinkedHashMap<>();
        Matcher group = SCENARIO_PATTERN.matcher(text);
        while (group.find() && scenarios.size() < 8) {
            Matcher item = SCENARIO_ITEM_PATTERN.matcher(group.group(1));
            while (item.find() && scenarios.size() < 8) {
                String matched = item.group();
                int leading = leadingWhitespace(matched);
                int trailing = trailingWhitespace(matched);
                String raw = matched.substring(leading, matched.length() - trailing);
                if (raw.isBlank()) continue;
                int start = group.start(1) + item.start() + leading;
                scenarios.putIfAbsent(raw, new AnchoredValue(raw, raw, start, start + raw.length(), raw, 0.98, "RULE"));
            }
        }
        return List.copyOf(scenarios.values());
    }

    private int leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private int trailingWhitespace(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(value.length() - count - 1))) count++;
        return count;
    }

    private boolean containsPromptInjection(String text) {
        return PROMPT_INJECTION_PATTERN.matcher(text).find();
    }

    private long stableDocumentId(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record AnchoredValue(String normalizedValue, String rawValue, int startOffset, int endOffset,
                                 String evidenceQuote, double confidence, String extractionMethod) {
        private ExtractedEvidence toEvidence(long documentId, String type) {
            return new ExtractedEvidence(type, normalizedValue, documentId, startOffset, endOffset,
                    evidenceQuote, confidence, extractionMethod, "EVIDENCE_VALID");
        }
    }

    private record ModelItem(String rawValue, String normalizedValue, String requirementType,
                             int startOffset, int endOffset, String evidenceQuote, double confidence) {
    }

    private record ModelMergeResult(String title, String salary, String level, int quarantined, boolean accepted) {
    }

    private static final class UnavailableModelGateway implements ModelGateway {
        @Override
        public Optional<String> generateStructured(String task, String sourceText, String jsonSchema) {
            return Optional.empty();
        }

        @Override
        public String explain(String question, String evidenceText) {
            return "证据不足";
        }

        @Override
        public String mode() {
            return "RULE_EVIDENCE_FALLBACK";
        }
    }
}
