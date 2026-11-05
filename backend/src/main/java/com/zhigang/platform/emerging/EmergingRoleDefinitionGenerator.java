package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.ai.ModelGateway;
import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.FieldEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class EmergingRoleDefinitionGenerator {
    private static final Set<String> ROOT_FIELDS = Set.of("name", "responsibilities", "requiredSkills", "bonusSkills", "scenarios");
    private static final Set<String> ITEM_FIELDS = Set.of("value", "evidenceId");
    private static final Set<String> QUOTE_ITEM_FIELDS = Set.of("value", "documentId", "evidenceQuote");
    private static final Set<String> LEGACY_ITEM_FIELDS = Set.of("value", "documentId", "startOffset", "endOffset", "evidenceQuote");
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["name","responsibilities","requiredSkills","bonusSkills","scenarios"],
             "properties":{
               "name":{"$ref":"#/$defs/item"},
               "responsibilities":{"type":"array","items":{"$ref":"#/$defs/item"}},
               "requiredSkills":{"type":"array","items":{"$ref":"#/$defs/item"}},
               "bonusSkills":{"type":"array","items":{"$ref":"#/$defs/item"}},
               "scenarios":{"type":"array","items":{"$ref":"#/$defs/item"}}},
             "$defs":{"item":{"type":"object","additionalProperties":false,
               "required":["value","evidenceId"],
               "properties":{"value":{"type":"string"},"evidenceId":{"type":"string"}}}}}
            """;

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final SkillNormalizationService skillNormalizationService;

    public EmergingRoleDefinitionGenerator(ModelGateway modelGateway, ObjectMapper objectMapper) {
        this(modelGateway, objectMapper, null);
    }

    @Autowired
    public EmergingRoleDefinitionGenerator(ModelGateway modelGateway, ObjectMapper objectMapper,
                                           SkillNormalizationService skillNormalizationService) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.skillNormalizationService = skillNormalizationService;
    }

    public GenerationResult generate(List<RawDocument> documents) {
        List<EvidenceAnchor> anchors = evidenceAnchors(documents);
        String input = evidenceInput(documents, anchors);
        String inputHash = sha256(input);
        Optional<String> output = modelGateway.generateStructured(
                "Create one role definition from the supplied semantic-cluster evidence. Do not copy a single job title as the role name. "
                        + "Each item must contain exactly value and evidenceId. Select an evidenceId exactly as supplied; never output documentId, quotes, offsets, URLs, or information outside the evidence. "
                        + "Every non-empty field requires one supporting evidenceId.",
                input, SCHEMA);
        if (output.isEmpty()) return GenerationResult.waiting(inputHash);
        try {
            JsonNode root = parseModelJson(output.get());
            if (!validObject(root, ROOT_FIELDS)) return GenerationResult.invalid(inputHash, "INVALID_MODEL_ROOT_SCHEMA");
            Map<Long, RawDocument> byId = new LinkedHashMap<>();
            documents.forEach(document -> byId.put(document.id(), document));
            Map<String, EvidenceAnchor> anchorsById = new LinkedHashMap<>();
            anchors.forEach(anchor -> anchorsById.put(anchor.id(), anchor));
            EvidenceContext context = new EvidenceContext(byId, anchorsById);
            ParseOutcome nameOutcome = parseItem(root.path("name"), "NAME", context);
            if (!nameOutcome.accepted()) return GenerationResult.invalid(inputHash, nameOutcome.reason());
            ParseItemsOutcome responsibilitiesOutcome = parseItems(root.path("responsibilities"), "RESPONSIBILITY", context);
            if (!responsibilitiesOutcome.accepted()) return GenerationResult.invalid(inputHash, responsibilitiesOutcome.reason());
            ParseItemsOutcome requiredOutcome = parseItems(root.path("requiredSkills"), "REQUIRED_SKILL", context);
            if (!requiredOutcome.accepted()) return GenerationResult.invalid(inputHash, requiredOutcome.reason());
            ParseItemsOutcome bonusOutcome = parseItems(root.path("bonusSkills"), "BONUS_SKILL", context);
            if (!bonusOutcome.accepted()) return GenerationResult.invalid(inputHash, bonusOutcome.reason());
            ParseItemsOutcome scenariosOutcome = parseItems(root.path("scenarios"), "SCENARIO", context);
            if (!scenariosOutcome.accepted()) return GenerationResult.invalid(inputHash, scenariosOutcome.reason());
            List<ParsedItem> responsibilities = responsibilitiesOutcome.items();
            List<ParsedItem> required = normalizeSkills(requiredOutcome.items());
            List<ParsedItem> bonus = normalizeSkills(bonusOutcome.items()).stream()
                    .filter(item -> required.stream().noneMatch(requiredItem -> requiredItem.value().equals(item.value())))
                    .toList();
            List<ParsedItem> scenarios = scenariosOutcome.items();
            if (responsibilities.isEmpty() || required.isEmpty() || scenarios.isEmpty()) {
                return GenerationResult.invalid(inputHash, "INVALID_REQUIRED_FIELDS");
            }
            List<ParsedItem> all = new ArrayList<>();
            ParsedItem name = nameOutcome.item();
            all.add(name); all.addAll(responsibilities); all.addAll(required); all.addAll(bonus); all.addAll(scenarios);
            return new GenerationResult("RAG_SCHEMA_EVIDENCE", inputHash, name.value(), values(responsibilities),
                    values(required), values(bonus), values(scenarios), all.stream().map(ParsedItem::evidence).toList());
        } catch (Exception ignored) {
            return GenerationResult.invalid(inputHash, "INVALID_MODEL_JSON");
        }
    }

    private JsonNode parseModelJson(String output) throws Exception {
        try {
            JsonNode direct = objectMapper.readTree(output);
            if (direct != null) return direct;
        } catch (Exception ignored) {
            // Some compatible endpoints wrap JSON in a markdown fence or a short preface.
        }
        String object = firstJsonObject(output);
        if (object.isBlank()) throw new IllegalArgumentException("model response does not contain a JSON object");
        return objectMapper.readTree(object);
    }

    private String firstJsonObject(String value) {
        int start = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                if (depth++ == 0) start = index;
            } else if (current == '}' && depth > 0 && --depth == 0 && start >= 0) {
                return value.substring(start, index + 1);
            }
        }
        return "";
    }

    private ParseItemsOutcome parseItems(JsonNode array, String field, EvidenceContext context) {
        if (!array.isArray()) return ParseItemsOutcome.invalid("INVALID_MODEL_ITEM_SCHEMA");
        List<ParsedItem> result = new ArrayList<>();
        for (JsonNode item : array) {
            ParseOutcome parsed = parseItem(item, field, context);
            if (!parsed.accepted()) return ParseItemsOutcome.invalid(parsed.reason());
            result.add(parsed.item());
        }
        return ParseItemsOutcome.accepted(List.copyOf(result));
    }

    private ParseOutcome parseItem(JsonNode item, String field, EvidenceContext context) {
        if (!validItemObject(item)) return ParseOutcome.invalid("INVALID_MODEL_ITEM_SCHEMA");
        String value = item.path("value").asText().trim();
        if (value.isBlank()) return ParseOutcome.invalid("INVALID_REQUIRED_FIELDS");
        if (item.has("evidenceId")) {
            EvidenceAnchor anchor = context.anchors().get(item.path("evidenceId").asText().trim());
            if (anchor == null) return ParseOutcome.invalid("INVALID_EVIDENCE_QUOTE_MISSING");
            return ParseOutcome.accepted(new ParsedItem(value, new FieldEvidence(field, value, anchor.documentId(),
                    anchor.startOffset(), anchor.endOffset(), anchor.quote(), "SUPPORT", true)));
        }
        long documentId = item.path("documentId").asLong(-1);
        String quote = item.path("evidenceQuote").asText().trim();
        RawDocument document = context.documents().get(documentId);
        if (document == null) return ParseOutcome.invalid("INVALID_REQUIRED_FIELDS");
        if (quote.length() < 12 && !hasLegacyOffsets(item)) return ParseOutcome.invalid("INVALID_EVIDENCE_QUOTE_MISSING");
        int start = document.content().indexOf(quote);
        if (start < 0) return ParseOutcome.invalid("INVALID_EVIDENCE_QUOTE_MISSING");
        if (document.content().lastIndexOf(quote) != start) return ParseOutcome.invalid("INVALID_EVIDENCE_QUOTE_AMBIGUOUS");
        int end = start + quote.length();
        return ParseOutcome.accepted(new ParsedItem(value, new FieldEvidence(field, value, documentId, start, end, quote, "SUPPORT", true)));
    }

    private boolean validObject(JsonNode node, Set<String> expected) {
        if (!node.isObject()) return false;
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private boolean validItemObject(JsonNode node) {
        return validObject(node, ITEM_FIELDS) || validObject(node, QUOTE_ITEM_FIELDS) || validObject(node, LEGACY_ITEM_FIELDS);
    }

    private boolean hasLegacyOffsets(JsonNode node) {
        return node.has("startOffset") && node.has("endOffset");
    }

    private List<EvidenceAnchor> evidenceAnchors(List<RawDocument> documents) {
        List<EvidenceAnchor> result = new ArrayList<>();
        int sequence = 1;
        for (RawDocument document : documents.stream().limit(12).toList()) {
            String content = document.content();
            int firstStart = firstNonWhitespace(content, 0);
            if (firstStart < 0) continue;
            int firstEnd = boundedEnd(content, firstStart, 300);
            result.add(new EvidenceAnchor("E" + sequence++, document.id(), firstStart, firstEnd,
                    content.substring(firstStart, firstEnd)));
            int lastStart = firstNonWhitespace(content, Math.max(firstEnd + 80, content.length() - 300));
            if (lastStart >= 0 && lastStart > firstEnd) {
                int lastEnd = boundedEnd(content, lastStart, 300);
                result.add(new EvidenceAnchor("E" + sequence++, document.id(), lastStart, lastEnd,
                        content.substring(lastStart, lastEnd)));
            }
        }
        return List.copyOf(result);
    }

    private int firstNonWhitespace(String content, int start) {
        for (int index = Math.max(0, start); index < content.length(); index++) {
            if (!Character.isWhitespace(content.charAt(index))) return index;
        }
        return -1;
    }

    private int boundedEnd(String content, int start, int maximumLength) {
        int maximum = Math.min(content.length(), start + maximumLength);
        for (int index = maximum - 1; index > start + 120; index--) {
            char current = content.charAt(index);
            if (current == '\n' || current == '。' || current == '！' || current == '？' || current == ';') return index + 1;
        }
        return maximum;
    }

    private String evidenceInput(List<RawDocument> documents, List<EvidenceAnchor> anchors) {
        StringBuilder result = new StringBuilder();
        for (RawDocument document : documents.stream().limit(12).toList()) {
            result.append("documentId=").append(document.id()).append("\n标题：").append(document.jobTitle())
                    .append("\n企业：").append(document.company()).append("\n来源：").append(document.sourceName()).append("\n");
            anchors.stream().filter(anchor -> anchor.documentId() == document.id()).forEach(anchor -> result
                    .append("evidenceId=").append(anchor.id()).append("\n原文：\n").append(anchor.quote()).append("\n---\n"));
        }
        return result.toString();
    }

    private List<String> values(List<ParsedItem> items) {
        return items.stream().map(ParsedItem::value).distinct().toList();
    }

    private List<ParsedItem> normalizeSkills(List<ParsedItem> items) {
        if (skillNormalizationService == null) return items;
        Map<String, ParsedItem> normalized = new LinkedHashMap<>();
        for (ParsedItem item : items) {
            skillNormalizationService.canonicalizeSkillName(item.value()).ifPresent(canonical ->
                    normalized.putIfAbsent(canonical, new ParsedItem(canonical, withValue(item.evidence(), canonical))));
        }
        return List.copyOf(normalized.values());
    }

    private FieldEvidence withValue(FieldEvidence evidence, String value) {
        return new FieldEvidence(evidence.fieldName(), value, evidence.documentId(), evidence.startOffset(), evidence.endOffset(),
                evidence.quote(), evidence.direction(), evidence.anchorValid());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record ParsedItem(String value, FieldEvidence evidence) {
    }

    private record EvidenceAnchor(String id, long documentId, int startOffset, int endOffset, String quote) {
    }

    private record EvidenceContext(Map<Long, RawDocument> documents, Map<String, EvidenceAnchor> anchors) {
    }

    public record GenerationResult(String mode, String inputHash, String name, List<String> responsibilities,
                                   List<String> requiredSkills, List<String> bonusSkills, List<String> scenarios,
                                   List<FieldEvidence> evidence) {
        static GenerationResult waiting(String inputHash) {
            return new GenerationResult("WAITING_MODEL", inputHash, "", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        static GenerationResult invalid(String inputHash, String reason) {
            return new GenerationResult(reason, inputHash, "", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean accepted() {
            return "RAG_SCHEMA_EVIDENCE".equals(mode);
        }
    }

    private record ParseOutcome(ParsedItem item, String reason) {
        static ParseOutcome accepted(ParsedItem item) {
            return new ParseOutcome(item, "");
        }

        static ParseOutcome invalid(String reason) {
            return new ParseOutcome(null, reason);
        }

        boolean accepted() {
            return item != null;
        }
    }

    private record ParseItemsOutcome(List<ParsedItem> items, String reason) {
        static ParseItemsOutcome accepted(List<ParsedItem> items) {
            return new ParseItemsOutcome(items, "");
        }

        static ParseItemsOutcome invalid(String reason) {
            return new ParseItemsOutcome(List.of(), reason);
        }

        boolean accepted() {
            return reason.isBlank();
        }
    }
}
