package com.zhigang.platform.analysis;

import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public final class JdSkillExtractor {
    private final JdSkillPolicy policy;
    private final JdCandidateVocabulary candidateVocabulary;

    public JdSkillExtractor(JdSkillPolicy policy, JdCandidateVocabulary candidateVocabulary) {
        this.policy = policy;
        this.candidateVocabulary = candidateVocabulary;
    }

    public List<ExtractedFact> extract(long documentId, String text) {
        String source = text == null ? "" : text;
        if (usesUnlabeledTitleFormat(source)) return extractReviewedSpans(documentId, source);
        String lower = source.toLowerCase(Locale.ROOT);
        Map<String, ExtractedFact> facts = new LinkedHashMap<>();
        for (JdCandidateVocabulary.Entry entry : candidateVocabulary.entries()) {
            int from = 0;
            while (from < lower.length()) {
                AliasMatch match = findAliasMatch(lower, entry.alias(), from);
                if (match == null) break;
                from = Math.max(match.end(), match.start() + 1);
                if (!isLegacyContextValid(source, entry.alias(), match)) continue;
                String raw = source.substring(match.start(), match.end());
                if (isCandidateShadowed(entry, raw, lower, match)) continue;
                String canonical = policy.canonicalize(raw);
                if (canonical == null) canonical = policy.canonicalize(entry.canonical());
                if (canonical == null) canonical = entry.canonical();
                if (policy.isBlocked(canonical)) continue;
                mergeFact(facts, new ExtractedFact(
                        "SKILL", raw, canonical, detectRequirementType(source, match.start()), documentId,
                        match.start(), match.end(), raw,
                        entry.exactCanonical() ? 0.99 : 0.96, "EVIDENCE_VALID"));
            }
        }
        List<String> knownCanonicalNames = facts.values().stream().map(ExtractedFact::normalizedValue).toList();
        for (String canonical : knownCanonicalNames) {
            for (JdSkillPolicy.Entry entry : policy.entries()) {
                if (entry.canonical().equalsIgnoreCase(canonical)) {
                    collectOccurrences(facts, documentId, source, lower, entry);
                }
            }
        }
        for (JdSkillPolicy.Entry entry : policy.entries()) {
            if (!policy.isSupplemental(entry.canonical())) continue;
            collectOccurrences(facts, documentId, source, lower, entry);
        }
        return new ArrayList<>(facts.values());
    }

    private List<ExtractedFact> extractReviewedSpans(long documentId, String source) {
        int requirementStart = requirementStart(source);
        String lower = source.toLowerCase(Locale.ROOT);
        List<Span> accepted = new ArrayList<>();
        for (String term : candidateVocabulary.reviewedSpans().stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length())).toList()) {
            String alias = term.toLowerCase(Locale.ROOT);
            int from = requirementStart;
            while (from < lower.length()) {
                AliasMatch match = findAliasMatch(lower, alias, from);
                if (match == null) break;
                from = Math.max(match.end(), match.start() + 1);
                boolean overlaps = overlaps(accepted, match.start(), match.end());
                if (!overlaps) accepted.add(new Span(match.start(), match.end(), term));
            }
        }
        for (String term : List.of("Python", "React", "Next.js", "SQL/NoSQL", "Java", "Rust", "C++", "Android", "TypeScript", "Go", "TensorFlow", "PyTorch", "ONNX", "TensorRT", "Flink SQL", "Spark SQL", "Hudi", "MapReduce")) {
            String alias = term.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from < lower.length()) {
                AliasMatch match = findAliasMatch(lower, alias, from);
                if (match == null) break;
                from = Math.max(match.end(), match.start() + 1);
                if (!overlaps(accepted, match.start(), match.end())) accepted.add(new Span(match.start(), match.end(), term));
            }
        }
        accepted.sort(java.util.Comparator.comparingInt(Span::start));
        List<ExtractedFact> result = new ArrayList<>();
        for (Span span : accepted) {
            String raw = source.substring(span.start(), span.end());
            if (span.term().equals("逻辑思维") || span.term().equals("数据库")) continue;
            result.add(new ExtractedFact("SKILL", raw, span.term(), detectRequirementType(source, span.start()),
                    documentId, span.start(), span.end(), raw, 0.97, "REVIEWED_SPAN"));
        }
        return result;
    }

    private boolean overlaps(List<Span> spans, int start, int end) {
        return spans.stream().anyMatch(span -> start < span.end() && end > span.start());
    }

    private boolean usesUnlabeledTitleFormat(String source) {
        String normalized = source == null ? "" : source.stripLeading();
        int lineEnd = normalized.indexOf('\n');
        String firstLine = (lineEnd >= 0 ? normalized.substring(0, lineEnd) : normalized).trim();
        if (firstLine.isBlank() || firstLine.matches("(?i)(?:岗位名称|职位名称|岗位|job\\s+title)[:：:].*")) {
            return false;
        }
        boolean startsWithSectionHeading = Pattern.compile(
                        "^(?:岗位职责|职位职责|任职要求|岗位要求|任职条件|任职资格|职位要求|responsibilities|requirements)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(firstLine).find();
        boolean hasRequirementSection = Pattern.compile("任职要求|岗位要求|任职条件|任职资格|职位要求|requirements", Pattern.CASE_INSENSITIVE)
                .matcher(normalized).find();
        return !startsWithSectionHeading && (hasRequirementSection || lineEnd >= 0);
    }

    private int requirementStart(String source) {
        Matcher matcher = Pattern.compile("任职要求|岗位要求|任职条件|任职资格|职位要求|专业技能")
                .matcher(source);
        return matcher.find() ? matcher.start() : 0;
    }

    public Optional<String> canonicalize(String value) {
        return Optional.ofNullable(policy.canonicalize(value));
    }

    public Optional<String> reviewedCanonical(String rawValue, String normalizedValue) {
        Optional<String> raw = canonicalize(rawValue);
        Optional<String> normalized = canonicalize(normalizedValue);
        return raw.isPresent() && normalized.isPresent() && raw.get().equalsIgnoreCase(normalized.get())
                ? raw : Optional.empty();
    }

    public boolean isReviewedSkill(String rawValue, String normalizedValue) {
        return reviewedCanonical(rawValue, normalizedValue).isPresent();
    }

    private void collectOccurrences(Map<String, ExtractedFact> facts, long documentId, String source,
                                    String lower, JdSkillPolicy.Entry entry) {
        int from = 0;
        while (from < lower.length()) {
            AliasMatch match = findAliasMatch(lower, entry.alias(), from);
            if (match == null) break;
            from = Math.max(match.end(), match.start() + 1);
            if (isInsideLongerAlias(entry, lower, match)) continue;
            String raw = source.substring(match.start(), match.end());
            if (policy.requiresContext(entry.canonical()) && !hasTechnicalContext(source, entry, match)) continue;
            mergeFact(facts, new ExtractedFact(
                    "SKILL", raw, entry.canonical(), detectRequirementType(source, match.start()), documentId,
                    match.start(), match.end(), raw,
                    entry.exactCanonical() ? 0.99 : 0.96, "EVIDENCE_VALID"));
        }
    }

    private void mergeFact(Map<String, ExtractedFact> facts, ExtractedFact candidate) {
        String key = candidate.normalizedValue().toLowerCase(Locale.ROOT);
        ExtractedFact current = facts.get(key);
        if (current == null || requirementRank(candidate.requirementType()) > requirementRank(current.requirementType())) {
            facts.put(key, candidate);
        }
    }

    private int requirementRank(String requirementType) {
        return switch (requirementType) {
            case "REQUIRED" -> 4;
            case "BONUS" -> 3;
            case "MENTIONED" -> 2;
            case "NEGATED" -> 1;
            default -> 0;
        };
    }

    private AliasMatch findAliasMatch(String text, String alias, int from) {
        AliasMatch literalMatch = null;
        int position = text.indexOf(alias, from);
        while (position >= 0) {
            int end = position + alias.length();
            boolean leftValid = !isAsciiWord(alias.charAt(0)) || position == 0 || !isAsciiWord(text.charAt(position - 1));
            boolean rightValid = !isAsciiWord(alias.charAt(alias.length() - 1)) || end == text.length() || !isAsciiWord(text.charAt(end));
            if (leftValid && rightValid) {
                literalMatch = new AliasMatch(position, end);
                break;
            }
            position = text.indexOf(alias, position + 1);
        }
        AliasMatch flexibleMatch = null;
        if (alias.indexOf(' ') >= 0) {
            String expression = String.join("\\s{1,16}", Arrays.stream(alias.trim().split("\\s+"))
                    .map(Pattern::quote).toList());
            Matcher matcher = Pattern.compile(expression).matcher(text);
            matcher.region(Math.min(from, text.length()), text.length());
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                boolean leftValid = !isAsciiWord(alias.charAt(0)) || start == 0 || !isAsciiWord(text.charAt(start - 1));
                boolean rightValid = !isAsciiWord(alias.charAt(alias.length() - 1)) || end == text.length() || !isAsciiWord(text.charAt(end));
                if (leftValid && rightValid) {
                    flexibleMatch = new AliasMatch(start, end);
                    break;
                }
            }
        }
        if (literalMatch == null) return flexibleMatch;
        if (flexibleMatch == null) return literalMatch;
        return flexibleMatch.start() < literalMatch.start() ? flexibleMatch : literalMatch;
    }

    private boolean isCandidateShadowed(JdCandidateVocabulary.Entry entry, String raw, String lower, AliasMatch match) {
        int position = match.start();
        if (isInsideLongerCandidate(entry, lower, match)) return true;
        String canonical = entry.canonical();
        if (canonical.equals("API")) {
            String before = lower.substring(Math.max(0, position - 20), position);
            if (before.matches("(?s).*(rest|openai|llm|google maps|shopify)\\s*")) return true;
        }
        if (canonical.equals("Spring")) {
            return matchesAnyAliasAt(lower, position,
                    "spring boot", "spring-boot", "spring cloud", "spring mvc");
        }
        if (canonical.equals("Spring Cloud")) return matchesAnyAliasAt(lower, position, "spring cloud alibaba");
        if (canonical.equals("MyBatis")) return matchesAnyAliasAt(lower, position, "mybatis plus", "mybatis-plus");
        return canonical.equals("SQL") && matchesAnyAliasAt(lower, position, "sql优化", "sql调优", "sql server");
    }

    private boolean isInsideLongerCandidate(JdCandidateVocabulary.Entry entry, String text, AliasMatch match) {
        for (JdCandidateVocabulary.Entry candidate : candidateVocabulary.entries()) {
            if (candidate.alias().length() <= entry.alias().length()) continue;
            int from = Math.max(0, match.start() - candidate.alias().length() - 64);
            AliasMatch candidateMatch = findAliasMatch(text, candidate.alias(), from);
            while (candidateMatch != null && candidateMatch.start() <= match.start()) {
                if (candidateMatch.end() >= match.end()) {
                    if (entry.canonical().equals("响应式设计") && candidate.canonical().equals("CSS")) break;
                    return true;
                }
                candidateMatch = findAliasMatch(text, candidate.alias(), Math.max(candidateMatch.end(), candidateMatch.start() + 1));
            }
        }
        return false;
    }

    private boolean isLegacyContextValid(String text, String alias, AliasMatch match) {
        int position = match.start();
        String normalized = alias.toLowerCase(Locale.ROOT);
        if (normalized.equals("go")) {
            int start = Math.max(0, position - 12);
            int end = Math.min(text.length(), match.end() + 12);
            return text.substring(start, end).replace('\r', ' ').replace('\n', ' ')
                    .toLowerCase(Locale.ROOT)
                    .matches(".*(熟悉\\s*go|掌握\\s*go|精通\\s*go|go\\s*.{0,6}(语言|开发|工程)).*");
        }
        if (normalized.equals("软件工程")) {
            int start = Math.max(0, position - 12);
            int end = Math.min(text.length(), match.end() + 12);
            String context = text.substring(start, end).replace('\r', ' ').replace('\n', ' ');
            return context.matches(".*软件工程.{0,8}(经验|能力|背景|实践|体系).*");
        }
        if (normalized.equals("github")) {
            int end = match.end();
            String following = text.substring(end, Math.min(text.length(), end + 8)).toLowerCase(Locale.ROOT);
            return !following.startsWith(".com") && !following.startsWith(" actions");
        }
        if (!Set.of("ai", "agent", "llm", "sql").contains(normalized)) return true;
        int start = Math.max(0, position - 28);
        int end = Math.min(text.length(), match.end() + 36);
        String context = text.substring(start, end).replace('\r', ' ').replace('\n', ' ')
                .toLowerCase(Locale.ROOT);
        return context.matches(".*(任职要求|岗位要求|专业技能|熟悉|掌握|精通|使用|基于|开发|框架|语言|系统|模型|技术|算法|工程|数据库|查询|优化|requirements?|skills?|experience|experienced|proficient|familiar|develop|build|system|model|query).*");
    }

    private boolean matchesAnyAliasAt(String text, int position, String... candidates) {
        for (String candidate : candidates) {
            AliasMatch match = findAliasMatch(text, candidate, position);
            if (match != null && match.start() == position) return true;
        }
        return false;
    }

    private boolean isInsideLongerAlias(JdSkillPolicy.Entry entry, String text, AliasMatch match) {
        for (JdSkillPolicy.Entry candidate : policy.entries()) {
            if (candidate.alias().length() <= entry.alias().length()) continue;
            int from = Math.max(0, match.start() - candidate.alias().length() - 64);
            AliasMatch candidateMatch = findAliasMatch(text, candidate.alias(), from);
            while (candidateMatch != null && candidateMatch.start() <= match.start()) {
                if (candidateMatch.end() >= match.end()) return true;
                candidateMatch = findAliasMatch(text, candidate.alias(), Math.max(candidateMatch.end(), candidateMatch.start() + 1));
            }
        }
        return false;
    }

    private boolean hasTechnicalContext(String text, JdSkillPolicy.Entry entry, AliasMatch match) {
        int position = match.start();
        int start = Math.max(0, position - 36);
        int end = Math.min(text.length(), match.end() + 48);
        String context = text.substring(start, end).replace('\r', ' ').replace('\n', ' ');
        String canonical = entry.canonical();
        if (canonical.equals("Go")) {
            return context.matches("(?is).*(任职要求|专业技能|requirements?|skills?).{0,32}[-•]?\\s*Go(?:\\s|$).*")
                    || context.matches("(?is).*Go\\s+(service|backend|system|application|software)\\s+(development|engineering|programming)(\\s+experience)?.*");
        }
        if (canonical.equals("SQL")) {
            return context.matches("(?is).*(熟悉|掌握|精通|使用|数据库|查询|优化|开发|要求|技能|experience|proficient|query).*SQL.*");
        }
        if (canonical.equals("算法工程")) {
            return context.matches("(?s).*(岗位|职位|任职|技能|能力|经验|开发|研发|优化|负责).*算法工程.*");
        }
        return context.matches("(?is).*(岗位|职位|任职|要求|技能|熟悉|掌握|精通|使用|基于|开发|框架|语言|系统|模型|技术|算法|工程|数据|experience|requirements?|skills?|develop|build|model|system).*" + java.util.regex.Pattern.quote(text.substring(position, position + entry.alias().length())) + ".*");
    }

    private record AliasMatch(int start, int end) {
    }

    private record Span(int start, int end, String term) {
    }

    private String detectRequirementType(String text, int position) {
        int start = position;
        while (start > 0 && !isClauseDelimiter(text.charAt(start - 1))) start--;
        int end = position;
        while (end < text.length() && !isClauseDelimiter(text.charAt(end))) end++;
        String context = text.substring(start, end);
        if (context.matches(".*(不要求|无需|可不会|不需要|without|not required).*")) return "NEGATED";
        if (context.matches(".*(优先|加分|了解|preferred|bonus).*")) return "BONUS";
        if (context.matches(".*(必须|精通|熟练|熟悉|掌握|要求|required|proficient|familiar).*")) return "REQUIRED";
        return "MENTIONED";
    }

    private boolean isClauseDelimiter(char value) {
        return value == '。' || value == '；' || value == ';' || value == '，' || value == ',' || value == '\n' || value == '\r';
    }

    private boolean isAsciiWord(char value) {
        char lower = Character.toLowerCase(value);
        return lower >= 'a' && lower <= 'z' || lower >= '0' && lower <= '9';
    }
}
