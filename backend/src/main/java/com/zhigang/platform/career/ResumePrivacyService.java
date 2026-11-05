package com.zhigang.platform.career;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ResumePrivacyService {
    private static final String VERSION = "resume-pii-redaction-v1.0";
    private static final Pattern NAME = Pattern.compile("(?m)^(\\s*(?:姓名|称呼)\\s*[:：]\\s*)[^\\n]+$");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])");
    private static final Pattern ADDRESS_LINE = Pattern.compile("(?m)^(\\s*(?:地址|住址|现居地|所在地)\\s*[:：]\\s*)[^\\n]+$");

    public RedactionResult redact(String source) {
        String text = source == null ? "" : source;
        List<String> fields = new ArrayList<>();
        text = replace(text, NAME, "$1[已脱敏]", "NAME", fields);
        text = replace(text, PHONE, "[手机号已脱敏]", "PHONE", fields);
        text = replace(text, EMAIL, "[邮箱已脱敏]", "EMAIL", fields);
        text = replace(text, ADDRESS_LINE, "$1[地址已脱敏]", "ADDRESS", fields);
        return new RedactionResult(text, List.copyOf(fields), VERSION);
    }

    private String replace(String text, Pattern pattern, String replacement, String field, List<String> fields) {
        if (!pattern.matcher(text).find()) return text;
        fields.add(field);
        return pattern.matcher(text).replaceAll(replacement);
    }

    public record RedactionResult(String text, List<String> fields, String version) {
    }
}
