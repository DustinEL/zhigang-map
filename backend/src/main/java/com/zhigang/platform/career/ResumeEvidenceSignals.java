package com.zhigang.platform.career;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResumeEvidenceSignals {
    private static final List<String> CHINESE_ACTIONS = List.of(
            "实现", "开发", "部署", "测试", "优化", "设计", "使用", "负责", "容器化");
    private static final Pattern ENGLISH_ACTION_PATTERN = Pattern.compile(
            "(?i)\\b(?:built|created|developed|implemented|designed|deployed|deploying|optimized|improved|"
                    + "led|integrated|integrating|automated|tested|maintained|managed|delivered|pretrained|"
                    + "fine-tuned|migrated|engineered|architected|instrumented|programmed|configured|identified|"
                    + "spearheaded|computed|containerized|orchestrated|owned|own)\\b");
    private static final Pattern ENGLISH_OUTCOME_PATTERN = Pattern.compile(
            "(?i)(?:\\d+(?:\\.\\d+)?\\s*%|\\b(?:production|latency|throughput|reliability|performance|accuracy|passed|tests?|users?|saving|reduced|increased|improved|deployed|delivered)\\b)");

    private ResumeEvidenceSignals() {
    }

    static String action(String line) {
        for (String verb : CHINESE_ACTIONS) {
            if (line.contains(verb)) return verb;
        }
        Matcher english = ENGLISH_ACTION_PATTERN.matcher(line);
        return english.find() ? english.group().toLowerCase(Locale.ROOT) : "";
    }

    static boolean hasAction(String line) {
        return !action(line).isBlank();
    }

    static boolean hasOutcome(String line) {
        return containsAny(line, "上线", "生产", "测试", "通过", "提升", "降低", "%", "验收")
                || ENGLISH_OUTCOME_PATTERN.matcher(line).find();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
