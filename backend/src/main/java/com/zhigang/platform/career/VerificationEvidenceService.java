package com.zhigang.platform.career;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class VerificationEvidenceService {
    private static final Pattern METRIC = Pattern.compile(
            "(?i)(?:\\d+(?:\\.\\d+)?\\s*%|\\d+\\s*/\\s*\\d+|recall@\\d+|precision@\\d+|f1|准确率|覆盖率|通过率|测试通过\\s*\\d+)");
    private static final Pattern CODE = Pattern.compile("(?i)(?:代码|仓库|commit|readme|接口|模块|实现|部署|运行结果|测试)");
    private static final Pattern CERTIFICATE = Pattern.compile("(?i)(?:证书|认证|编号|certificate|credential)");
    private final EvidenceUrlChecker urlChecker;

    public VerificationEvidenceService(EvidenceUrlChecker urlChecker) {
        this.urlChecker = urlChecker;
    }

    public Assessment assess(String skill, String evidenceType, String evidenceUrl, String evidenceText) {
        String text = evidenceText == null ? "" : evidenceText.trim();
        List<String> checks = new ArrayList<>();
        boolean relevant = containsIgnoreCase(text, skill);
        boolean metric = METRIC.matcher(text).find();
        boolean code = CODE.matcher(text).find();
        checks.add("内容匹配目标技能=" + relevant);
        checks.add("包含可验证指标=" + metric);
        checks.add("包含实现或测试说明=" + code);

        EvidenceUrlChecker.UrlCheck urlCheck = null;
        if (SetWithUrl.requires(evidenceType) || evidenceUrl != null && !evidenceUrl.isBlank()) {
            if (evidenceUrl == null || evidenceUrl.isBlank()) {
                return rejected(checks, "该证据类型必须提供可访问链接");
            }
            urlCheck = urlChecker.check(evidenceUrl);
            checks.add("链接检查=" + urlCheck.detail());
            if (urlCheck.pending()) return new Assessment("PENDING_VERIFICATION", "链接访问超时，等待人工页面确认", List.copyOf(checks));
            if (!urlCheck.accessible()) return rejected(checks, urlCheck.detail());
        }
        if (!relevant) return rejected(checks, "证据内容未匹配目标技能");

        return switch (evidenceType) {
            case "TEST_RESULT" -> metric
                    ? verified(checks, "测试结果包含可复核指标")
                    : rejected(checks, "测试结果必须包含百分比、通过数或 Recall/F1 等指标");
            case "PROJECT_LINK" -> code && (metric || text.length() >= 80)
                    ? verified(checks, "项目页面可访问且内容与技能匹配")
                    : pending(checks, "项目链接有效，但实现与测试证据需要人工确认");
            case "CODE_DESCRIPTION" -> code && (metric || urlCheck != null && urlCheck.accessible())
                    ? verified(checks, "代码说明与可访问产物或测试指标相互印证")
                    : pending(checks, "代码说明需要补充链接或可验证测试指标");
            case "DOCUMENT" -> text.length() >= 80 && (metric || code)
                    ? verified(checks, "文档内容、实现说明和目标技能一致")
                    : pending(checks, "文档需要人工确认内容完整性");
            case "CERTIFICATE" -> CERTIFICATE.matcher(text).find() && urlCheck != null && urlCheck.accessible()
                    ? verified(checks, "证书页面可访问且内容匹配")
                    : pending(checks, "证书需要可访问页面或人工确认");
            default -> rejected(checks, "不支持的能力证据类型");
        };
    }

    private boolean containsIgnoreCase(String text, String value) {
        return value != null && !value.isBlank() && text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private Assessment verified(List<String> checks, String reason) {
        return new Assessment("VERIFIED", reason, List.copyOf(checks));
    }

    private Assessment pending(List<String> checks, String reason) {
        return new Assessment("PENDING_VERIFICATION", reason, List.copyOf(checks));
    }

    private Assessment rejected(List<String> checks, String reason) {
        return new Assessment("REJECTED", reason, List.copyOf(checks));
    }

    private static final class SetWithUrl {
        private static boolean requires(String type) {
            return "PROJECT_LINK".equals(type) || "CERTIFICATE".equals(type);
        }
    }

    public record Assessment(String status, String reason, List<String> checks) {
    }
}
