package com.zhigang.platform.career;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

final class ResumeSectionHeadings {
    static final String PROJECT = "PROJECT";
    static final String WORK = "WORK";
    static final String INTERNSHIP = "INTERNSHIP";
    static final String SKILLS = "SKILLS";
    static final String EDUCATION = "EDUCATION";
    static final String SOURCE = "SOURCE";
    static final String OTHER = "OTHER";

    private static final Set<String> PROJECT_HEADINGS = Set.of(
            "项目经历", "项目经验", "projects", "projectexperience", "keyprojects", "selectedprojects",
            "selectedproject", "selectedworkprojects",
            "academicprojects", "personalprojects", "notableprojects", "opensource", "opensourcecontributions",
            "项目经历projects", "项目经验projects");
    private static final Set<String> WORK_HEADINGS = Set.of(
            "工作经历", "工作经验", "workexperience", "experience", "professionalexperience",
            "experiences", "professionalexperiences", "employmenthistory", "employmentexperience",
            "careerhistory", "professionalbackground", "selectedwork", "selectedexperience",
            "selectedworkexperience");
    private static final Set<String> INTERNSHIP_HEADINGS = Set.of(
            "实习经历", "internship", "internships", "internshipexperience",
            "consultingassignmentsinternship", "consultingassignmentsinternships");
    private static final Set<String> SKILL_HEADINGS = Set.of(
            "专业技能", "技能", "skills", "technicalskills", "technologies", "techstack",
            "专业技能skills", "技能skills");
    private static final Set<String> EDUCATION_HEADINGS = Set.of(
            "教育背景", "教育经历", "education", "academicbackground", "educationalbackground",
            "academicqualifications");
    private static final Set<String> OTHER_HEADINGS = Set.of(
            "个人信息", "个人简介", "证书", "认证", "personalinfo", "profile", "summary",
            "professionalsummary", "certificates", "certifications", "awards", "achievements",
            "publications", "languages", "interests", "consultingassignments", "home", "about", "contact");
    private static final Set<String> NAVIGATION_HEADINGS = Set.of("home", "about", "contact");
    private static final Pattern LABELED_PROJECT = Pattern.compile(
            "(?i)^(?:project\\s*(?:title|name)\\b.*|.+\\bproject\\s*:)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^\\s*\\d{1,2}\\s*[-—–.:|]\\s*\\p{L}.*$");
    private static final Pattern INLINE_MARKER = Pattern.compile(
            "^\\s*[\\\\#。；;|,，]*\\s*(职业经历|工作经历|工作经验|项目经历|项目经验|技术栈|专业技能|技能|教育背景|教育经历|实习经历|projects|projectexperience|skills|technicalskills|experience|experiences|education)(?=\\s|$|[:：])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPORTED_RECORDS = Pattern.compile(
            "^\\s*export\\s+const\\s+(projects?|works?|experiences?)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPORTED_SKILLS = Pattern.compile(
            "^\\s*export\\s+const\\s+(skills?|technologies)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPORTED_PROFILE = Pattern.compile(
            "^\\s*export\\s+const\\s+(summary|profile|about|contact)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_KEY = Pattern.compile(
            "^\\s*[\\\"']?(projects?|works?|experiences?)[\\\"']?\\s*[:=]\\s*.*",
            Pattern.CASE_INSENSITIVE);

    private ResumeSectionHeadings() {
    }

    static String section(String line) {
        boolean explicitInlinePrefix = line.matches("^\\s*[\\\\#。；;|,，].*");
        String inlineText = line.replaceFirst("^\\s*[\\\\#。；;|,，]*\\s*", "").toLowerCase(Locale.ROOT);
        if (explicitInlinePrefix && (inlineText.startsWith("职业经历") || inlineText.startsWith("工作经历") || inlineText.startsWith("工作经验"))) return WORK;
        if (explicitInlinePrefix && (inlineText.startsWith("项目经历") || inlineText.startsWith("项目经验") || inlineText.startsWith("projects "))) return PROJECT;
        if (explicitInlinePrefix && (inlineText.startsWith("技术栈") || inlineText.startsWith("专业技能") || inlineText.startsWith("技能 ") || inlineText.startsWith("skills "))) return SKILLS;
        if (explicitInlinePrefix && (inlineText.startsWith("教育背景") || inlineText.startsWith("教育经历") || inlineText.startsWith("education "))) return EDUCATION;
        if (explicitInlinePrefix && inlineText.startsWith("实习经历")) return INTERNSHIP;
        String normalized = normalize(line);
        String headingKey = normalized.replaceFirst("^[^\\p{L}]+", "");
        Matcher recordKey = RECORD_KEY.matcher(line);
        if (recordKey.matches()) {
            String key = recordKey.group(1).toLowerCase(Locale.ROOT);
            return key.startsWith("project") ? PROJECT : WORK;
        }
        if (PROJECT_HEADINGS.contains(headingKey)) return PROJECT;
        if (WORK_HEADINGS.contains(headingKey)) return WORK;
        if (INTERNSHIP_HEADINGS.contains(headingKey)) return INTERNSHIP;
        if (SKILL_HEADINGS.contains(headingKey)) return SKILLS;
        if (EDUCATION_HEADINGS.contains(headingKey)) return EDUCATION;
        String prefix = headingPrefix(line);
        if (prefix != null) return prefix;
        if (isShortSemanticHeading(line, headingKey, "技能")) return SKILLS;
        if (isShortSemanticHeading(line, headingKey, "项目")) return PROJECT;
        if (isShortSemanticHeading(line, headingKey, "实习")) return INTERNSHIP;
        if (isShortSemanticHeading(line, headingKey, "工作") && headingKey.contains("经历")) return WORK;
        if (normalized.startsWith("原始来源") || normalized.equals("source") || normalized.startsWith("来源说明")) {
            return SOURCE;
        }
        if (OTHER_HEADINGS.contains(normalized)) return OTHER;
        Matcher exported = EXPORTED_RECORDS.matcher(line);
        if (exported.matches()) {
            String record = exported.group(1).toLowerCase(Locale.ROOT);
            return record.startsWith("project") ? PROJECT : WORK;
        }
        if (EXPORTED_SKILLS.matcher(line).matches()) return SKILLS;
        if (EXPORTED_PROFILE.matcher(line).matches()) return OTHER;
        Matcher inline = INLINE_MARKER.matcher(line);
        if (inline.find()) {
            String marker = normalize(inline.group(1));
            if (marker.contains("技能") || marker.equals("skills") || marker.equals("skill")
                    || marker.equals("technicalskills")) return SKILLS;
            if (marker.contains("项目") || marker.startsWith("project")) return PROJECT;
            if (marker.contains("实习")) return INTERNSHIP;
            if (marker.contains("工作") || marker.equals("experience") || marker.equals("experiences")) return WORK;
            if (marker.contains("教育") || marker.equals("education")) return EDUCATION;
        }
        if (line.trim().length() <= 60 && NUMBERED_HEADING.matcher(line).matches()
                && !ResumeEvidenceSignals.hasAction(line)) return OTHER;
        return null;
    }

    static boolean isInlineSectionMarker(String line) {
        return INLINE_MARKER.matcher(line).find()
                || EXPORTED_RECORDS.matcher(line).matches()
                || EXPORTED_SKILLS.matcher(line).matches()
                || EXPORTED_PROFILE.matcher(line).matches();
    }

    private static boolean isShortSemanticHeading(String line, String normalized, String marker) {
        return line.trim().length() <= 40
                && normalized.contains(marker)
                && !ResumeEvidenceSignals.hasAction(line);
    }

    static boolean isExperience(String section) {
        return PROJECT.equals(section) || WORK.equals(section) || INTERNSHIP.equals(section);
    }

    static boolean isNavigationHeading(String line) {
        return NAVIGATION_HEADINGS.contains(normalize(line));
    }

    static boolean isAmbiguousProjectsHeading(String line) {
        return "projects".equals(normalize(line));
    }

    static boolean isInlineProjectHeading(String line) {
        String trimmed = line.trim();
        if (LABELED_PROJECT.matcher(trimmed).matches()) return true;
        String normalized = normalize(trimmed);
        return normalized.endsWith("project") && normalized.length() > "project".length()
                && trimmed.length() <= 80 && !ResumeEvidenceSignals.hasAction(trimmed);
    }

    private static String normalize(String line) {
        return line.replaceAll("[\\s（）()：:·|/&]+", "").toLowerCase(Locale.ROOT);
    }

    private static String headingPrefix(String line) {
        String normalized = normalize(line);
        if (normalized.startsWith("selectedwork") || normalized.startsWith("selectedexperience")
                || normalized.startsWith("professionalexperience") || normalized.startsWith("workexperience")
                || normalized.startsWith("experience")) return WORK;
        if (normalized.startsWith("selectedprojects") || normalized.startsWith("projectexperience")
                || normalized.startsWith("projects")) return PROJECT;
        if (normalized.startsWith("summary") || normalized.startsWith("profile")) return OTHER;
        return null;
    }
}
