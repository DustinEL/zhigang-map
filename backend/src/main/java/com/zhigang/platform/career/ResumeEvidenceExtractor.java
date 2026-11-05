package com.zhigang.platform.career;

import com.zhigang.platform.domain.PlatformModels.ResumeFact;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResumeEvidenceExtractor {
    public static final String VERSION = "resume-evidence-v2.0";
    private static final Pattern COMPANY_ROLE_DATE = Pattern.compile(
            "^\\s*[^|\\n]{2,80}\\s*\\|\\s*[^|\\n]{2,80}\\s*\\|.*(?:19|20)\\d{2}.*$",
            Pattern.CASE_INSENSITIVE);

    public ResumeEvidenceBundle extract(String text, List<ResumeFact> facts) {
        String source = text == null ? "" : text;
        List<LineContext> lines = lineContexts(source);
        List<ResumeEvidenceBundle.SkillEvidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResumeFact fact : facts == null ? List.<ResumeFact>of() : facts) {
            if (!"SKILL".equals(fact.factType()) || blank(fact.value())) continue;
            for (int position : occurrences(source, fact.value())) {
                String key = fact.normalizedValue() + "@" + position;
                if (!seen.add(key)) continue;
                LineContext line = lineAt(lines, position);
                boolean lineNegated = isNegated(line.text(), Math.max(0, position - line.start()));
                LineContext contextual = lineNegated ? line : withAdjacentEvidence(lines, line);
                boolean negated = isNegated(contextual.text(), Math.max(0, position - contextual.start()));
                String contextType = contextType(contextual, negated);
                int level = evidenceLevel(contextual, contextType, negated);
                if (!negated && ResumeEvidenceSignals.hasAction(fact.evidenceQuote())
                        && ResumeEvidenceSignals.hasOutcome(fact.evidenceQuote())
                        && ("WORK".equals(contextual.section()) || "INTERNSHIP".equals(contextual.section())
                        || containsAny(fact.evidenceQuote(), "上线", "生产环境", "线上服务", "deployed", "production"))) {
                    contextType = "PRODUCTION";
                    level = 4;
                }
                evidence.add(new ResumeEvidenceBundle.SkillEvidence(
                        fact.normalizedValue(), contextual.text(), position, position + fact.value().length(),
                        contextual.section(), contextual.projectId(), contextType,
                        action(fact.evidenceQuote()).isBlank() ? action(contextual.text()) : action(fact.evidenceQuote()),
                        ResumeEvidenceSignals.hasOutcome(fact.evidenceQuote()) ? fact.evidenceQuote() : outcome(contextual.text()),
                        level, negated, fact.confidence()));
            }
            if (evidence.stream().noneMatch(item -> item.skill().equals(fact.normalizedValue()))) {
                LineContext factLine = lineAt(lines, fact.startOffset());
                boolean lineNegated = isNegated(factLine.text(), Math.max(0, fact.startOffset() - factLine.start()));
                LineContext contextual = lineNegated ? factLine : withAdjacentEvidence(lines, factLine);
                boolean factNegated = isNegated(contextual.text(), Math.max(0, fact.startOffset() - contextual.start()));
                String factContext = contextType(contextual, factNegated);
                String factQuote = blank(fact.evidenceQuote()) ? contextual.text() : fact.evidenceQuote();
                if (!factNegated && ResumeEvidenceSignals.hasAction(factQuote)
                        && ResumeEvidenceSignals.hasOutcome(factQuote)
                        && ("WORK".equals(contextual.section()) || "INTERNSHIP".equals(contextual.section())
                        || factQuote.contains("上线") || factQuote.contains("生产环境"))) {
                    factContext = "PRODUCTION";
                }
                int factLevel = factNegated ? 0 : evidenceLevel(contextual, factContext, false);
                if (!factNegated && factQuote.contains("上线") && factQuote.contains("生产环境")) {
                    factContext = "PRODUCTION";
                    factLevel = 4;
                }
                if (!factNegated && "PRODUCTION".equals(factContext)
                        && ResumeEvidenceSignals.hasAction(factQuote)
                        && ResumeEvidenceSignals.hasOutcome(factQuote)) factLevel = 4;
                evidence.add(new ResumeEvidenceBundle.SkillEvidence(
                        fact.normalizedValue(), fact.evidenceQuote(), fact.startOffset(), fact.endOffset(),
                        contextual.section(), contextual.projectId(), factContext, action(factQuote),
                        ResumeEvidenceSignals.hasOutcome(factQuote) ? factQuote : outcome(contextual.text()),
                        factLevel, factNegated, fact.confidence()));
            }
        }
        return new ResumeEvidenceBundle(VERSION, sha256(source), evidence);
    }

    /**
     * Returns project/work evidence using the same logical boundaries as skill evidence.
     * This keeps flattened exports and web-resume records from silently losing project context.
     */
    public List<String> extractProjectTexts(String text) {
        String source = text == null ? "" : text;
        return lineContexts(source).stream()
                .filter(line -> "PROJECT".equals(line.section())
                        || (("WORK".equals(line.section()) || "INTERNSHIP".equals(line.section()))
                        && ResumeEvidenceSignals.hasAction(line.text())))
                .map(LineContext::text)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private List<Integer> occurrences(String text, String rawSkill) {
        List<Integer> result = new ArrayList<>();
        String lowerText = compatibilityNormalized(text).toLowerCase(Locale.ROOT);
        String lowerSkill = compatibilityNormalized(rawSkill).toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lowerText.length()) {
            int position = lowerText.indexOf(lowerSkill, from);
            if (position < 0) break;
            if (hasTokenBoundaries(text, position, position + rawSkill.length(), rawSkill)) {
                result.add(position);
            }
            from = position + Math.max(1, lowerSkill.length());
        }
        return result;
    }

    /** Normalize compatibility glyphs without changing UTF-16 offsets for one-to-one mappings. */
    private String compatibilityNormalized(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String source = new String(Character.toChars(codePoint));
            String mapped = Normalizer.normalize(source, Normalizer.Form.NFKC);
            normalized.append(mapped.codePointCount(0, mapped.length()) == 1 ? mapped : source);
        });
        return normalized.toString();
    }

    private boolean hasTokenBoundaries(String text, int start, int end, String skill) {
        if (skill.codePoints().noneMatch(this::isAsciiWord)) return true;
        boolean left = start == 0 || !isAsciiWord(text.charAt(start - 1));
        boolean right = end >= text.length() || !isAsciiWord(text.charAt(end));
        return left && right;
    }

    private boolean isAsciiWord(int value) {
        return value < 128 && (Character.isLetterOrDigit(value) || value == '_');
    }

    private List<LineContext> lineContexts(String text) {
        List<LineContext> lines = new ArrayList<>();
        String section = "PROFILE";
        String projectId = "";
        int projectSequence = 0;
        int navigationStreak = 0;
        int start = 0;
        for (int[] boundary : logicalBoundaries(text)) {
            start = boundary[0];
            int end = boundary[1];
            String line = text.substring(start, end);
            String trimmed = line.trim();
            String heading = heading(trimmed);
            if (ResumeSectionHeadings.isNavigationHeading(trimmed)) {
                navigationStreak++;
            } else if (heading != null && !ResumeSectionHeadings.isAmbiguousProjectsHeading(trimmed)) {
                navigationStreak = 0;
            }
            if (ResumeSectionHeadings.PROJECT.equals(heading)
                    && ResumeSectionHeadings.isAmbiguousProjectsHeading(trimmed) && navigationStreak >= 2) {
                heading = ResumeSectionHeadings.OTHER;
            }
            if (heading != null) {
                section = heading;
                if ("PROJECT".equals(section)) {
                    projectSequence++;
                    projectId = "project-" + projectSequence;
                } else {
                    projectId = "";
                }
                if (!trimmed.isBlank()) {
                    lines.add(new LineContext(start, end, line, section, projectId));
                }
            } else if (!trimmed.isBlank() && COMPANY_ROLE_DATE.matcher(trimmed).matches()) {
                navigationStreak = 0;
                section = "WORK";
                projectId = "";
                lines.add(new LineContext(start, end, line, section, projectId));
            } else if (!trimmed.isBlank() && ResumeSectionHeadings.isInlineProjectHeading(trimmed)
                    && Set.of("WORK", "INTERNSHIP", "PROJECT").contains(section)) {
                navigationStreak = 0;
                section = "PROJECT";
                projectSequence++;
                projectId = "project-" + projectSequence;
            } else if (!trimmed.isBlank()) {
                navigationStreak = 0;
                lines.add(new LineContext(start, end, line, section, projectId));
            }
        }
        return lines;
    }

    private List<int[]> logicalBoundaries(String text) {
        Set<Integer> starts = new java.util.TreeSet<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        Pattern marker = Pattern.compile("(?i)(?<![\\p{L}])(?:\\\\\\s*|#{1,6}\\s*)?(职业经历|工作经历|工作经验|项目经历|项目经验|技术栈|专业技能|技能|教育背景|教育经历|实习经历|selected\\s+work|selected\\s+experience|selected\\s+projects|summary|profile|projects|skills|technicalskills|experience|experiences|education)(?=\\s|$|[:：])");
        Matcher matcher = marker.matcher(text);
        while (matcher.find()) {
            String markerName = matcher.group(1).toLowerCase(Locale.ROOT);
            boolean technicalStack = markerName.equals("技术栈");
            boolean startsAfterNewline = matcher.start() == 0 || text.charAt(matcher.start() - 1) == '\n';
            if (!technicalStack || !startsAfterNewline) starts.add(matcher.start());
        }
        Matcher exported = Pattern.compile("(?i)(?<![\\p{L}])export\\s+const\\s+(projects?|works?|experiences?|skills?|technologies|summary|profile|about|contact)\\b").matcher(text);
        while (exported.find()) starts.add(exported.start());
        Matcher recordKey = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])[\\\"']?(works?|projects?|experiences?)[\\\"']?\\s*[:=]").matcher(text);
        while (recordKey.find()) starts.add(recordKey.start());
        List<Integer> ordered = new ArrayList<>(starts);
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            int segmentStart = ordered.get(i);
            int segmentEnd = i + 1 < ordered.size() ? ordered.get(i + 1) : text.length();
            if (segmentStart < segmentEnd) result.add(new int[]{segmentStart, segmentEnd});
        }
        return result;
    }

    private String heading(String line) {
        return ResumeSectionHeadings.section(line);
    }

    private LineContext lineAt(List<LineContext> lines, int position) {
        return lines.stream().filter(line -> position >= line.start() && position <= line.end()).findFirst()
                .orElse(new LineContext(position, position, "", "UNKNOWN", ""));
    }

    private LineContext withAdjacentEvidence(List<LineContext> lines, LineContext current) {
        int index = lines.indexOf(current);
        if (index <= 0) return current;
        LineContext previous = lines.get(index - 1);
        if (!previous.section().equals(current.section()) || !previous.projectId().equals(current.projectId())) return current;
        if (previous.text().length() + current.text().length() > 800) return current;
        return new LineContext(previous.start(), current.end(), previous.text() + "\n" + current.text(),
                current.section(), current.projectId());
    }

    private boolean isNegated(String line, int relativePosition) {
        int start = Math.max(0, relativePosition - 8);
        String prefix = line.substring(start, Math.max(start, relativePosition));
        return prefix.matches(".*(未使用|没有使用|未采用|不使用|不熟悉|不了解|无).*");
    }

    private String contextType(LineContext line, boolean negated) {
        if (negated) return "NEGATED";
        if ("WORK".equals(line.section()) || "INTERNSHIP".equals(line.section())
                || containsAnyIgnoreCase(line.text(), "上线", "生产环境", "线上服务", "production", "deployed")) return "PRODUCTION";
        if ("PROJECT".equals(line.section())) return "PROJECT";
        if (containsAny(line.text(), "课程", "学习", "证书", "认证")) return "COURSE";
        return "SELF_REPORT";
    }

    private int evidenceLevel(LineContext line, String contextType, boolean negated) {
        if (negated) return 0;
        if (ResumeSectionHeadings.SKILLS.equals(line.section())) return 1;
        if ("PRODUCTION".equals(contextType)) {
            if (containsAnyIgnoreCase(line.text(), "上线", "生产环境", "线上服务", "production", "deployed")) return 4;
            if (action(line.text()).isBlank()) return 1;
            return outcome(line.text()).isBlank() ? 3 : 4;
        }
        if (containsAny(line.text(), "测试", "通过", "提升", "降低", "%", "验收")) return 3;
        if ("PROJECT".equals(contextType)) return 2;
        if ("COURSE".equals(contextType)) return 1;
        return 0;
    }

    private String action(String line) {
        return ResumeEvidenceSignals.action(line);
    }

    private String outcome(String line) {
        return ResumeEvidenceSignals.hasOutcome(line) ? line : "";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private boolean containsAnyIgnoreCase(String text, String... values) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String value : values) if (lower.contains(value.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算简历文本哈希", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record LineContext(int start, int end, String text, String section, String projectId) {
    }
}
