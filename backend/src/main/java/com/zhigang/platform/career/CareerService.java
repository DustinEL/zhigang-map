package com.zhigang.platform.career;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.api.Requests.LearningPlanRequest;
import com.zhigang.platform.api.Requests.MatchRunRequest;
import com.zhigang.platform.api.Requests.AbilityProfileRequest;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.api.Requests.ResumeFactsUpdateRequest;
import com.zhigang.platform.api.Requests.VerificationSubmissionRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.AbilityEvidence;
import com.zhigang.platform.domain.PlatformModels.AdjacentRole;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.domain.PlatformModels.HardGate;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.LearningPlan;
import com.zhigang.platform.domain.PlatformModels.LearningPlanItem;
import com.zhigang.platform.domain.PlatformModels.LearningResource;
import com.zhigang.platform.domain.PlatformModels.DiagnosisComparison;
import com.zhigang.platform.domain.PlatformModels.MatchDimension;
import com.zhigang.platform.domain.PlatformModels.MatchReport;
import com.zhigang.platform.domain.PlatformModels.MatchSkillDetail;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import com.zhigang.platform.domain.PlatformModels.ResumeFact;
import com.zhigang.platform.domain.PlatformModels.ResultMetadata;
import com.zhigang.platform.domain.PlatformModels.SkillRequirement;
import com.zhigang.platform.domain.PlatformModels.VerificationSubmission;
import com.zhigang.platform.domain.PlatformModels.VerificationSubmissionPage;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.reporting.JobAnalysisService;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CareerService {
    private static final Set<String> VERIFICATION_STATUSES = Set.of(
            "PENDING_VERIFICATION", "VERIFIED", "REJECTED");
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:姓名)[:：]\\s*([^\\n]+)");
    private static final Pattern EDUCATION_PATTERN = Pattern.compile("(博士|硕士|研究生|本科在读|本科生|本科学历|本科|学士|专科|大专)");
    private static final Pattern ENGLISH_DOCTORATE_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z])PH\\s*\\.?\\s*D\\s*\\.?(?![A-Z])|\\bDOCTOR(?:ATE| OF PHILOSOPHY)\\b");
    private static final Pattern ENGLISH_MASTER_PATTERN = Pattern.compile(
            "(?i)\\bMASTER(?:['’]S)?\\s+(?:OF|IN)\\b|(?<![A-Z])M\\s*\\.?\\s*(?:SCI|SC|ENG|TECH)\\.?(?![A-Z])|(?<![A-Z])M\\.S\\.(?![A-Z])");
    private static final Pattern ENGLISH_BACHELOR_PATTERN = Pattern.compile(
            "(?i)\\bBACHELOR(?:['’]S)?\\s+(?:OF|IN)\\b|(?<![A-Z])B\\s*\\.?\\s*(?:SC|ENG|TECH)\\.?(?![A-Z])|(?<![A-Z])B\\.(?:S|E)\\.(?![A-Z])");
    private static final Pattern ENGLISH_DIPLOMA_PATTERN = Pattern.compile(
            "(?i)\\b(?:DIPLOMA\\s+(?:IN|OF)|ASSOCIATE(?:['’]S)? DEGREE\\s+(?:IN|OF))\\b");
    private static final Pattern LABELED_MAJOR_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:专业|所学专业|就读专业)\\s*[:：]\\s*([^\\n，,；;。]{2,30})");
    private static final List<String> KNOWN_MAJORS = List.of(
            "计算机科学与技术", "软件工程", "人工智能", "数据科学与大数据技术", "数据科学",
            "信息管理与信息系统", "网络工程", "物联网工程", "电子信息工程", "通信工程",
            "自动化", "数学与应用数学", "统计学", "电子商务");
    private static final Pattern INVALID_MAJOR_WORDS = Pattern.compile(
            "(?:^|\\s)(?:我|本人)|精通|熟悉|掌握|擅长|会使用|项目|技能|开发|负责",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAJOR_SUFFIX = Pattern.compile(
            ".*(?:专业|工程|科学|技术|管理|信息系统|数学|统计学|自动化|电子商务)$");
    private final SkillNormalizationService normalizationService;
    private final PlatformDataProvider dataProvider;
    private final JobAnalysisService jobAnalysisService;
    private final ResumePrivacyService privacyService;
    private final SkillAlignmentService alignmentService;
    private final MatchScoringPolicy scoringPolicy;
    private final VerificationEvidenceService verificationEvidenceService;
    private final RoleCapabilityCardCatalog capabilityCardCatalog;
    private final ResumeEvidenceExtractor resumeEvidenceExtractor;
    private final MatchDecisionEngine matchDecisionEngine;
    private final Map<Long, ResumeProfile> resumes = new ConcurrentHashMap<>();
    private final Map<Long, ResumeEvidenceBundle> resumeEvidence = new ConcurrentHashMap<>();
    private final Map<Long, MatchReport> matches = new ConcurrentHashMap<>();
    private final Map<Long, LearningPlan> plans = new ConcurrentHashMap<>();
    private final Map<Long, VerificationSubmission> submissions = new ConcurrentHashMap<>();
    private final Map<Long, String> resumeOwners = new ConcurrentHashMap<>();
    private final Map<Long, OffsetDateTime> resumeCreatedAt = new ConcurrentHashMap<>();
    private final AtomicLong resumeSequence = new AtomicLong(1000);
    private final AtomicLong matchSequence = new AtomicLong(2000);
    private final AtomicLong planSequence = new AtomicLong(3000);
    private final AtomicLong submissionSequence = new AtomicLong(4000);

    @Autowired
    public CareerService(
            SkillNormalizationService normalizationService,
            PlatformDataProvider dataProvider,
            JobAnalysisService jobAnalysisService,
            ResumePrivacyService privacyService,
            SkillAlignmentService alignmentService,
            MatchScoringPolicy scoringPolicy,
            VerificationEvidenceService verificationEvidenceService,
            RoleCapabilityCardCatalog capabilityCardCatalog,
            ResumeEvidenceExtractor resumeEvidenceExtractor,
            MatchDecisionEngine matchDecisionEngine
    ) {
        this.normalizationService = normalizationService;
        this.dataProvider = dataProvider;
        this.jobAnalysisService = jobAnalysisService;
        this.privacyService = privacyService;
        this.alignmentService = alignmentService;
        this.scoringPolicy = scoringPolicy;
        this.verificationEvidenceService = verificationEvidenceService;
        this.capabilityCardCatalog = capabilityCardCatalog;
        this.resumeEvidenceExtractor = resumeEvidenceExtractor;
        this.matchDecisionEngine = matchDecisionEngine;
    }

    /**
     * 保留旧版公共构造签名，外部集成可在不感知新能力卡依赖的情况下升级。
     */
    public CareerService(
            SkillNormalizationService normalizationService,
            PlatformDataProvider dataProvider,
            JobAnalysisService jobAnalysisService,
            ResumePrivacyService privacyService,
            SkillAlignmentService alignmentService,
            MatchScoringPolicy scoringPolicy,
            VerificationEvidenceService verificationEvidenceService
    ) {
        this(normalizationService, dataProvider, jobAnalysisService, privacyService, alignmentService,
                scoringPolicy, verificationEvidenceService,
                new RoleCapabilityCardCatalog(new com.fasterxml.jackson.databind.ObjectMapper()),
                new ResumeEvidenceExtractor(), new MatchDecisionEngine());
    }

    public CareerService(SkillNormalizationService normalizationService, PlatformDataProvider dataProvider,
                         JobAnalysisService jobAnalysisService) {
        this(normalizationService, dataProvider, jobAnalysisService, new ResumePrivacyService(),
                new SkillAlignmentService(normalizationService), MatchScoringPolicy.defaults(),
                new VerificationEvidenceService(url -> new EvidenceUrlChecker.UrlCheck(false, false, "测试环境未访问外部链接")),
                new RoleCapabilityCardCatalog(new com.fasterxml.jackson.databind.ObjectMapper()),
                new ResumeEvidenceExtractor(), new MatchDecisionEngine());
    }

    public ResumeProfile parseResume(ResumeRequest request) {
        return parseResume(request, "TEST_SESSION");
    }

    public ResumeProfile parseResume(ResumeRequest request, String owner) {
        return parseResume(request, owner, "TEXT");
    }

    public ResumeProfile parseResume(ResumeRequest request, String owner, String parsingMode) {
        long id = resumeSequence.incrementAndGet();
        ResumeProfile profile = buildResumeProfile(id, request, parsingMode);
        resumes.put(id, profile);
        resumeOwners.put(id, owner);
        resumeCreatedAt.put(id, OffsetDateTime.now());
        return profile;
    }

    public ResumeProfile reparseResume(long id, ResumeRequest request, String owner) {
        return reparseResume(id, request, owner, "TEXT");
    }

    public ResumeProfile reparseResume(long id, ResumeRequest request, String owner, String parsingMode) {
        resume(id, owner);
        ResumeProfile profile = buildResumeProfile(id, request, parsingMode);
        resumes.put(id, profile);
        resumeCreatedAt.put(id, OffsetDateTime.now());
        removeDerivedResults(id);
        return profile;
    }

    private ResumeProfile buildResumeProfile(long id, ResumeRequest request, String parsingMode) {
        ResumePrivacyService.RedactionResult redaction = privacyService.redact(request.text());
        String text = redaction.text();
        List<ExtractedFact> extracted = normalizationService.extract(text).stream()
                .filter(fact -> !"NEGATED".equals(fact.requirementType()))
                .toList();
        List<AbilityEvidence> abilities = extracted.stream().map(fact -> ability(fact, text)).toList();
        String education = extractEducation(text);
        String major = extractMajor(text);
        List<String> projects = extractProjects(text);
        List<String> certificates = extractLines(text, List.of("证书", "认证", "软考", "ACP", "HCIP"));
        List<ResumeFact> facts = resumeFacts(text, extracted, education, major, projects, certificates);
        ResumeEvidenceBundle extractedEvidence = resumeEvidenceExtractor.extract(text, facts);
        abilities = evidenceAwareAbilities(abilities, extractedEvidence);
        ResumeProfile profile = new ResumeProfile(
                id,
                "已脱敏候选人",
                education,
                major,
                request.graduationYear(),
                request.weeklyHours(),
                abilities,
                projects,
                certificates,
                "NEEDS_CONFIRMATION",
                null,
                facts,
                parsingMode,
                redaction.fields()
        );
        resumeEvidence.put(id, extractedEvidence);
        return withMetadata(profile);
    }

    private List<AbilityEvidence> evidenceAwareAbilities(List<AbilityEvidence> abilities,
                                                         ResumeEvidenceBundle bundle) {
        Map<String, ResumeEvidenceBundle.SkillEvidence> strongest = bundle.evidence().stream()
                .filter(item -> !item.negated())
                .collect(Collectors.toMap(ResumeEvidenceBundle.SkillEvidence::skill, item -> item,
                        (left, right) -> left.evidenceLevel() >= right.evidenceLevel() ? left : right,
                        LinkedHashMap::new));
        return abilities.stream().map(ability -> {
            ResumeEvidenceBundle.SkillEvidence evidence = strongest.get(ability.skill());
            if (evidence == null || evidence.evidenceLevel() <= ability.level()) return ability;
            String sourceType = "PRODUCTION".equals(evidence.contextType()) ? "WORK_EXPERIENCE"
                    : "PROJECT".equals(evidence.contextType()) ? "PROJECT" : ability.sourceType();
            return new AbilityEvidence(ability.skill(), evidence.evidenceLevel(),
                    levelName(evidence.evidenceLevel()), evidence.quote(), sourceType,
                    ability.confidence(), ability.verificationStatus());
        }).toList();
    }

    public ResumeProfile createAbilityProfile(AbilityProfileRequest request, String owner) {
        long id = resumeSequence.incrementAndGet();
        List<AbilityEvidence> abilities = request.skills().stream().distinct()
                .map(skill -> new AbilityEvidence(skill, 0, "L0 自述", "用户手动填写", "SELF_REPORT", 1))
                .toList();
        List<String> projects = safeList(request.projects());
        List<String> certificates = safeList(request.certificates());
        List<ResumeFact> facts = updatedResumeFacts(List.of(), request.education(), request.major(), abilities,
                projects, certificates);
        ResumeProfile profile = withMetadata(new ResumeProfile(id,
                request.name() == null || request.name().isBlank() ? "匿名同学" : request.name().trim(),
                request.education(), request.major(), request.graduationYear(), request.weeklyHours(), abilities,
                projects, certificates, "NEEDS_CONFIRMATION", null, facts, "MANUAL", List.of()));
        resumes.put(id, profile);
        resumeOwners.put(id, owner);
        resumeCreatedAt.put(id, OffsetDateTime.now());
        return profile;
    }

    public ResumeProfile resume(long id) {
        return resume(id, "TEST_SESSION");
    }

    public ResumeProfile resume(long id, String owner) {
        assertOwner(id, owner);
        ResumeProfile profile = resumes.get(id);
        return profile != null ? profile : sampleResume(id);
    }

    public ResumeProfile confirmResume(long id, boolean confirmed) {
        return confirmResume(id, confirmed, "TEST_SESSION");
    }

    public ResumeProfile confirmResume(long id, boolean confirmed, String owner) {
        ResumeProfile current = resume(id, owner);
        String status = confirmed ? "CONFIRMED" : "NEEDS_CONFIRMATION";
        ResumeProfile updated = new ResumeProfile(current.id(), current.name(), current.education(), current.major(),
                current.graduationYear(), current.weeklyHours(), current.abilities(), current.projects(), current.certificates(),
                status, current.metadata(), current.facts(), current.parsingMode(), current.redactedFields());
        resumes.put(id, updated);
        return updated;
    }

    public ResumeProfile updateResume(long id, ResumeFactsUpdateRequest request, String owner) {
        ResumeProfile current = resume(id, owner);
        List<AbilityEvidence> abilities = request.skills() == null ? current.abilities() : request.skills().stream().distinct()
                .map(skill -> current.abilities().stream().filter(value -> value.skill().equals(skill)).findFirst()
                        .orElse(new AbilityEvidence(skill, 0, "L0 自述", "用户修正", "USER_CORRECTION", 1)))
                .toList();
        String education = valueOr(request.education(), current.education());
        String major = valueOr(request.major(), current.major());
        List<String> projects = request.projects() == null ? current.projects() : safeList(request.projects());
        List<String> certificates = request.certificates() == null ? current.certificates() : safeList(request.certificates());
        List<ResumeFact> facts = updatedResumeFacts(current.facts(), education, major, abilities, projects, certificates);
        ResumeProfile updated = withMetadata(new ResumeProfile(current.id(), valueOr(request.name(), current.name()),
                education, major,
                request.graduationYear() == null ? current.graduationYear() : request.graduationYear(),
                request.weeklyHours() == null ? current.weeklyHours() : request.weeklyHours(), abilities,
                projects, certificates,
                Boolean.TRUE.equals(request.confirmed()) ? "CONFIRMED" : "NEEDS_CONFIRMATION",
                current.metadata(), facts, current.parsingMode(), current.redactedFields()));
        resumes.put(id, updated);
        resumeEvidence.put(id, mergeEvidence(updated, resumeEvidence.get(id), false));
        removeDerivedResults(id);
        return updated;
    }

    public void deleteResume(long id, String owner) {
        resume(id, owner);
        resumes.remove(id);
        resumeEvidence.remove(id);
        resumeOwners.remove(id);
        resumeCreatedAt.remove(id);
        removeDerivedResults(id);
    }

    public MatchReport match(MatchRunRequest request) {
        return match(request, "TEST_SESSION");
    }

    public MatchReport match(MatchRunRequest request, String owner) {
        ResumeProfile profile = resume(request.resumeId(), owner);
        if (!"CONFIRMED".equals(profile.status())) {
            throw new BusinessException("RESUME_NOT_CONFIRMED", "请先确认简历解析结果，再运行岗位匹配");
        }
        JobRole role = jobAnalysisService.role(request.jobRoleId());
        if (role.emerging()) {
            throw new BusinessException("ROLE_NOT_ACTIONABLE", "新型岗位暂不能用于能力诊断或行动计划");
        }
        java.util.Optional<RoleCapabilityCard> capabilityCard = capabilityCardCatalog.find(role.id());
        if (capabilityCard.isEmpty() && role.requiredSkills().isEmpty()) {
            throw new BusinessException("ROLE_PROFILE_INCOMPLETE", "该岗位画像仍在建设中，暂不能用于能力诊断");
        }
        List<String> roleSkills = new ArrayList<>();
        role.requiredSkills().forEach(value -> roleSkills.add(value.skill()));
        role.bonusSkills().forEach(value -> roleSkills.add(value.skill()));
        Map<String, SkillAlignmentService.Alignment> alignments = alignmentService.align(roleSkills, profile.abilities());

        List<MatchSkillDetail> details = new ArrayList<>();
        for (SkillRequirement requirement : role.requiredSkills()) {
            details.add(skillDetail(requirement, alignments.get(requirement.skill()), scoringPolicy.hardGateEvidenceLevel()));
        }
        for (SkillRequirement requirement : role.bonusSkills()) {
            details.add(skillDetail(requirement, alignments.get(requirement.skill()), 1));
        }

        double requiredScore = weightedSkillCoverage(role.requiredSkills(), alignments, scoringPolicy.hardGateEvidenceLevel());
        double responsibilityScore = projectRelevance(profile, role);
        double bonusScore = weightedSkillCoverage(role.bonusSkills(), alignments, 1);
        double proficiencyScore = profile.abilities().stream().mapToInt(AbilityEvidence::level).average().orElse(0) / 4.0 * 100;
        double scenarioScore = profile.projects().isEmpty() ? 35 : 72;
        double certificateScore = profile.certificates().isEmpty() ? 20 : 85;

        List<MatchDimension> dimensions = List.of(
                dimension("必备技能覆盖与证据等级", scoringPolicy.weight(MatchScoringPolicy.REQUIRED_SKILLS), requiredScore, "技能出现不等于掌握，项目证据可提高等级"),
                dimension("职责/项目相关度", scoringPolicy.weight(MatchScoringPolicy.RESPONSIBILITIES), responsibilityScore, "按岗位职责关键词与项目任务覆盖计算"),
                dimension("加分技能覆盖", scoringPolicy.weight(MatchScoringPolicy.BONUS_SKILLS), bonusScore, "加分项不替代必备技能"),
                dimension("证据等级与近期使用", scoringPolicy.weight(MatchScoringPolicy.PROFICIENCY), proficiencyScore, "只使用结构化证据等级计算，不由大模型决定分数"),
                dimension("行业场景相关度", scoringPolicy.weight(MatchScoringPolicy.SCENARIOS), scenarioScore, "项目场景与岗位应用场景的可解释重合"),
                dimension("证书等补充项", scoringPolicy.weight(MatchScoringPolicy.CERTIFICATES), certificateScore, "证书只作为补充证据")
        );
        double rawReadiness = dimensions.stream().mapToDouble(MatchDimension::weightedScore).sum();
        Set<String> coreSkills = role.requiredSkills().stream()
                .sorted(java.util.Comparator.comparingDouble(SkillRequirement::demandRate).reversed()
                        .thenComparing(java.util.Comparator.comparingInt(SkillRequirement::evidenceCount).reversed()))
                .limit(2).map(SkillRequirement::skill).collect(Collectors.toSet());
        long legacyCriticalGaps = details.stream().filter(detail -> coreSkills.contains(detail.skill()) && detail.score() < 60).count();
        boolean educationVerified = educationPassed(profile.education());
        boolean educationGate = educationVerified || "未明确".equals(profile.education());
        MatchDecisionEngine.MatchDecisionTrace orderedDecision = capabilityCard
                .map(card -> matchDecisionEngine.evaluate(card, evidenceFor(profile), educationGate)).orElse(null);
        long criticalGaps = orderedDecision == null ? legacyCriticalGaps : orderedDecision.blockingGapCount();
        boolean skillGate = criticalGaps == 0;
        List<HardGate> gates = List.of(
                new HardGate("学历硬门槛", "本科及以上", profile.education(), educationVerified ? "PASSED" : "RISK",
                        educationGate ? "学历未识别时保留类别并提示人工核验" : "硬门槛失败时准备度上限单独生效"),
                new HardGate("必备技能门槛", "冻结能力卡阻断项必须满足最低证据等级",
                        criticalGaps + " 项阻断缺口", skillGate ? "PASSED" : "RISK", "加分技能不能抵消核心能力缺口"),
                new HardGate("项目职责", "至少一条相关项目或实习证据", profile.projects().isEmpty() ? "未识别" : "有项目证据",
                        profile.projects().isEmpty() ? "RISK" : "PASSED", "学生模式使用项目和比赛替代初级工作年限证据"),
                new HardGate("地域", request.preferredCity() == null || request.preferredCity().isBlank() ? "不限" : request.preferredCity(),
                        "岗位支持多城市", "PASSED", "地域仅作为用户主动筛选条件")
        );
        boolean hardGatePassed = educationGate && skillGate;
        double readiness = orderedDecision == null
                ? (hardGatePassed ? rawReadiness : Math.min(rawReadiness, scoringPolicy.hardGateScoreCap()))
                : orderedDecision.readiness();
        double growthPotential = Math.max(25, Math.min(96, 92 - criticalGaps * 9 + profile.weeklyHours() * 0.8));
        String applicationState = orderedDecision == null
                ? genericApplicationState(readiness, requiredScore, responsibilityScore, criticalGaps, educationGate)
                : orderedDecision.label();
        long verifiedEvidence = profile.abilities().stream().filter(value -> "VERIFIED".equals(value.verificationStatus())).count();
        long semanticMatches = details.stream().filter(value -> "SEMANTIC_CONFIRMED".equals(value.matchType())).count();
        double scoreConfidence = Math.max(45, Math.min(96, 62 + verifiedEvidence * 7 + (details.size() - criticalGaps) * 2 - semanticMatches * 2));
        String scoreLimitReason = hardGatePassed ? "" : orderedDecision == null
                ? "存在未通过的硬门槛，准备度已限制在 " + scoringPolicy.hardGateScoreCap() + " 分以内"
                : "存在未满足的冻结能力卡门槛，最终类别不能晋级";
        String policyVersion = orderedDecision == null ? scoringPolicy.version() : orderedDecision.policyVersion();
        String validationHash = orderedDecision == null ? scoringPolicy.validationDatasetHash()
                : "sha256:" + capabilityCardCatalog.snapshotSha256();
        List<AdjacentRole> adjacentRoles = jobAnalysisService.adjacentRoles(role.id()).stream().limit(3).toList();
        MatchReport report = new MatchReport(matchSequence.incrementAndGet(), profile.id(), role.id(), role.name(), role.releaseVersion(), gates,
                round(readiness), round(growthPotential), applicationState, dimensions, details, adjacentRoles, OffsetDateTime.now(),
                metadata(role.releaseVersion(), true), policyVersion, validationHash,
                round(scoreConfidence), scoreLimitReason);
        matches.put(report.id(), report);
        return report;
    }

    public MatchReport match(long id) {
        MatchReport report = matches.get(id);
        if (report == null) {
            throw new BusinessException("MATCH_NOT_FOUND", "未找到匹配任务，请先提交简历诊断");
        }
        return report;
    }

    public MatchReport match(long id, String owner) {
        MatchReport report = match(id);
        assertOwner(report.resumeId(), owner);
        return report;
    }

    public LearningPlan learningPlan(LearningPlanRequest request) {
        return learningPlan(request, "TEST_SESSION");
    }

    public LearningPlan learningPlan(LearningPlanRequest request, String owner) {
        MatchReport report = match(request.matchRunId(), owner);
        JobRole role = jobAnalysisService.role(report.roleId());
        if (role.emerging()) {
            throw new BusinessException("ROLE_NOT_ACTIONABLE", "新型岗位暂不能用于能力诊断或行动计划");
        }
        Set<String> missingSkills = report.skillDetails().stream()
                .filter(detail -> detail.requiredLevel() >= 2 && detail.score() < 80)
                .map(MatchSkillDetail::skill)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> ordered = topologicalOrder(missingSkills, normalizationService.prerequisiteGraph());
        Map<String, List<LearningResource>> resourcesBySkill = resourceIndex();
        List<LearningPlanItem> items = new ArrayList<>();
        int weeklyHours = Math.max(1, request.weeklyHours());
        int totalHours = ordered.stream().mapToInt(this::estimateHours).sum();
        int estimatedWeeks = Math.max(4, Math.min(6, (int) Math.ceil(totalHours / (double) weeklyHours)));
        for (int index = 0; index < ordered.size(); index++) {
            String skill = ordered.get(index);
            int estimatedHours = estimateHours(skill);
            List<String> resources = resourcesBySkill.getOrDefault(skill, List.of()).stream()
                    .filter(LearningResource::reviewed)
                    .map(LearningResource::name)
                    .distinct().limit(3).toList();
            int weekStart = Math.max(1, (int) Math.floor(index * estimatedWeeks / (double) ordered.size()) + 1);
            int weekEnd = Math.max(weekStart,
                    (int) Math.ceil((index + 1) * estimatedWeeks / (double) ordered.size()));
            items.add(new LearningPlanItem(index + 1, stage(index, ordered.size()), skill,
                    "目标岗位将该技能列为必备项，当前证据等级不足", normalizationService.prerequisiteGraph().getOrDefault(skill, List.of()),
                    resources, practicalTask(skill), acceptanceCriteria(skill), estimatedHours, weekStart, weekEnd,
                    "项目链接、实现说明、README 与测试结果"));
        }
        LearningPlan plan = new LearningPlan(planSequence.incrementAndGet(), report.id(), role.name(), weeklyHours, estimatedWeeks, items,
                report.graphRelease(), metadata(report.graphRelease(), true));
        plans.put(plan.id(), plan);
        return plan;
    }

    public VerificationSubmission submitVerification(VerificationSubmissionRequest request) {
        return submitVerification(request, "TEST_SESSION");
    }

    public VerificationSubmission submitVerification(VerificationSubmissionRequest request, String owner) {
        if (!Set.of("PROJECT_LINK", "CODE_DESCRIPTION", "TEST_RESULT", "DOCUMENT", "CERTIFICATE").contains(request.evidenceType())) {
            throw new BusinessException("INVALID_EVIDENCE_TYPE", "不支持的能力证据类型");
        }
        LearningPlan plan = plans.get(request.learningPlanId());
        if (plan == null) throw new BusinessException("LEARNING_PLAN_NOT_FOUND", "未找到成长计划，请先生成计划");
        LearningPlanItem item = plan.items().stream().filter(value -> value.order() == request.itemOrder()).findFirst()
                .orElseThrow(() -> new BusinessException("PLAN_ITEM_NOT_FOUND", "未找到需要验证的成长任务"));
        match(plan.matchRunId(), owner);
        VerificationEvidenceService.Assessment assessment = verificationEvidenceService.assess(
                item.skill(), request.evidenceType(), request.evidenceUrl(), request.evidenceText());
        OffsetDateTime now = OffsetDateTime.now();
        List<String> validationChecks = new ArrayList<>();
        validationChecks.add("自动校验建议=" + assessment.status());
        validationChecks.addAll(assessment.checks());
        VerificationSubmission submission = new VerificationSubmission(submissionSequence.incrementAndGet(), plan.id(), item.order(),
                item.skill(), request.evidenceType(), request.evidenceUrl(), request.evidenceText(), "PENDING_VERIFICATION", now,
                "自动校验建议：" + assessment.status() + "；" + assessment.reason(), List.copyOf(validationChecks), null);
        submissions.put(submission.id(), submission);
        return submission;
    }

    public synchronized VerificationSubmission reviewVerification(long submissionId, String decision, String reason, String reviewer) {
        if (!Set.of("VERIFIED", "REJECTED").contains(decision)) {
            throw new BusinessException("INVALID_VERIFICATION_DECISION", "审核结论只能是 VERIFIED 或 REJECTED");
        }
        VerificationSubmission current = submissions.get(submissionId);
        if (current == null) throw new BusinessException("VERIFICATION_NOT_FOUND", "未找到能力验证记录");
        if (!"PENDING_VERIFICATION".equals(current.status())) {
            throw new BusinessException("VERIFICATION_ALREADY_REVIEWED", "该能力证据已完成审核，不能重复变更结论");
        }
        LearningPlan plan = plans.get(current.learningPlanId());
        if (plan == null) throw new BusinessException("LEARNING_PLAN_NOT_FOUND", "未找到成长计划");
        LearningPlanItem item = plan.items().stream().filter(value -> value.order() == current.itemOrder()).findFirst()
                .orElseThrow(() -> new BusinessException("PLAN_ITEM_NOT_FOUND", "未找到需要验证的成长任务"));
        if (!Objects.equals(item.skill(), current.skill())) {
            throw new BusinessException("VERIFICATION_MISMATCH", "能力验证记录与成长任务不匹配");
        }
        MatchReport sourceMatch = match(plan.matchRunId());
        ResumeProfile sourceProfile = resumes.get(sourceMatch.resumeId());
        if (sourceProfile == null) throw new BusinessException("RESUME_NOT_FOUND", "未找到能力档案");
        ResumeProfile upgradedProfile = "VERIFIED".equals(decision)
                ? upgradedAbility(sourceProfile, current.skill(), current.evidenceText())
                : null;
        OffsetDateTime verifiedAt = "VERIFIED".equals(decision) ? OffsetDateTime.now() : null;
        VerificationSubmission updated = new VerificationSubmission(current.id(), current.learningPlanId(), current.itemOrder(),
                current.skill(), current.evidenceType(), current.evidenceUrl(), current.evidenceText(), decision,
                current.createdAt(), current.reviewReason() + "；专业审核：" + reviewer + "：" + reason,
                current.validationChecks(), verifiedAt);
        if (upgradedProfile != null) {
            resumes.put(upgradedProfile.id(), upgradedProfile);
            resumeEvidence.put(upgradedProfile.id(), mergeEvidence(upgradedProfile, resumeEvidence.get(upgradedProfile.id()), true));
        }
        submissions.put(updated.id(), updated);
        return updated;
    }

    public VerificationSubmissionPage verificationSubmissions(String status, int page, int size) {
        if (page < 0) {
            throw new BusinessException("INVALID_PAGE", "页码不能小于 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("INVALID_PAGE_SIZE", "每页数量需为 1 至 100");
        }
        String requestedStatus = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!requestedStatus.isBlank() && !VERIFICATION_STATUSES.contains(requestedStatus)) {
            throw new BusinessException("INVALID_VERIFICATION_STATUS",
                    "状态只能是 PENDING_VERIFICATION、VERIFIED 或 REJECTED");
        }
        List<VerificationSubmission> filtered = submissions.values().stream()
                .filter(value -> requestedStatus.isBlank() || requestedStatus.equals(value.status()))
                .sorted(Comparator.comparing(VerificationSubmission::createdAt).reversed()
                        .thenComparing(VerificationSubmission::id, Comparator.reverseOrder()))
                .toList();
        long offset = (long) page * size;
        int from = (int) Math.min(offset, filtered.size());
        int to = (int) Math.min((long) from + size, filtered.size());
        return new VerificationSubmissionPage(List.copyOf(filtered.subList(from, to)), page, size, filtered.size());
    }

    public DiagnosisComparison compareDiagnosis(long beforeMatchRunId, long verificationSubmissionId) {
        return compareDiagnosis(beforeMatchRunId, verificationSubmissionId, "TEST_SESSION");
    }

    public DiagnosisComparison compareDiagnosis(long beforeMatchRunId, long verificationSubmissionId, String owner) {
        MatchReport before = match(beforeMatchRunId, owner);
        VerificationSubmission submission = submissions.get(verificationSubmissionId);
        if (submission == null) throw new BusinessException("VERIFICATION_NOT_FOUND", "未找到能力验证记录");
        if (!"VERIFIED".equals(submission.status())) {
            throw new BusinessException("VERIFICATION_NOT_APPROVED", "只有 VERIFIED 的证据才能更新能力等级并触发再次诊断");
        }
        LearningPlan plan = plans.get(submission.learningPlanId());
        if (plan == null || plan.matchRunId() != before.id()) {
            throw new BusinessException("VERIFICATION_MISMATCH", "验证记录与诊断任务不匹配");
        }
        MatchReport after = match(new MatchRunRequest(before.resumeId(), before.roleId(), "", ""), owner);
        return new DiagnosisComparison(before.id(), after.id(), before.readiness(), after.readiness(),
                round(after.readiness() - before.readiness()), List.of(submission.skill()), OffsetDateTime.now());
    }

    private ResumeProfile upgradedAbility(ResumeProfile current, String skill, String evidenceText) {
        Map<String, AbilityEvidence> abilities = new LinkedHashMap<>();
        current.abilities().forEach(value -> abilities.put(value.skill(), value));
        AbilityEvidence previous = abilities.get(skill);
        int level = Math.max(3, previous == null ? 0 : previous.level());
        abilities.put(skill, new AbilityEvidence(skill, level, levelName(level), evidenceText,
                "PROJECT_VERIFICATION", 0.98, "VERIFIED"));
        return new ResumeProfile(current.id(), current.name(), current.education(), current.major(),
                current.graduationYear(), current.weeklyHours(), List.copyOf(abilities.values()), current.projects(),
                current.certificates(), "CONFIRMED", current.metadata(), current.facts(), current.parsingMode(), current.redactedFields());
    }

    private AbilityEvidence ability(ExtractedFact fact, String text) {
        int contextStart = Math.max(0, fact.startOffset() - 28);
        int contextEnd = Math.min(text.length(), fact.endOffset() + 48);
        String context = text.substring(contextStart, contextEnd);
        int level = evidenceLevel(context);
        return new AbilityEvidence(fact.normalizedValue(), level, levelName(level), context, sourceType(context, level), fact.confidence());
    }

    private List<ResumeFact> resumeFacts(String text, List<ExtractedFact> extracted, String education, String major,
                                         List<String> projects, List<String> certificates) {
        List<ResumeFact> facts = new ArrayList<>();
        for (ExtractedFact extractedFact : extracted) {
            AbilityEvidence evidence = ability(extractedFact, text);
            facts.add(new ResumeFact("SKILL", extractedFact.rawValue(), extractedFact.normalizedValue(),
                    extractedFact.startOffset(), extractedFact.endOffset(), evidence.quote(), evidence.level(),
                    evidence.sourceType(), evidence.verificationStatus(), extractedFact.confidence()));
        }
        facts.add(anchorFact("EDUCATION", education, education, text, 0, "RESUME_TEXT", 0.95));
        facts.add(anchorFact("MAJOR", major, major, text, 0, "RESUME_TEXT", "未明确".equals(major) ? 0.4 : 0.95));
        projects.forEach(value -> facts.add(anchorFact("PROJECT", value, value, text, 2, "PROJECT", 0.92)));
        certificates.forEach(value -> facts.add(anchorFact("CERTIFICATE", value, value, text, 1, "CERTIFICATE", 0.90)));
        return List.copyOf(facts);
    }

    private ResumeFact anchorFact(String type, String value, String normalized, String text, int level,
                                  String sourceType, double confidence) {
        int start = value == null || value.isBlank() ? -1 : text.indexOf(value);
        int end = start < 0 ? -1 : start + value.length();
        String quote = start < 0 ? "未在正文中定位" : text.substring(Math.max(0, start - 20), Math.min(text.length(), end + 30));
        return new ResumeFact(type, value, normalized, start, end, quote, level, sourceType,
                "PENDING_VERIFICATION", confidence);
    }

    private List<ResumeFact> updatedResumeFacts(List<ResumeFact> currentFacts, String education, String major,
                                                List<AbilityEvidence> abilities, List<String> projects,
                                                List<String> certificates) {
        List<ResumeFact> facts = new ArrayList<>();
        abilities.forEach(value -> facts.add(retainOrCreateFact(currentFacts, "SKILL", value.skill(), 0)));
        facts.add(retainOrCreateFact(currentFacts, "EDUCATION", education, 0));
        facts.add(retainOrCreateFact(currentFacts, "MAJOR", major, 0));
        projects.forEach(value -> facts.add(retainOrCreateFact(currentFacts, "PROJECT", value, 2)));
        certificates.forEach(value -> facts.add(retainOrCreateFact(currentFacts, "CERTIFICATE", value, 1)));
        return List.copyOf(facts);
    }

    private ResumeFact retainOrCreateFact(List<ResumeFact> currentFacts, String type, String value, int evidenceLevel) {
        return currentFacts.stream()
                .filter(fact -> type.equals(fact.factType()) && Objects.equals(value, fact.normalizedValue()))
                .findFirst()
                .orElseGet(() -> new ResumeFact(type, value, value, -1, -1, "用户手动修正，未定位到原文偏移",
                        evidenceLevel, "USER_CORRECTION", "PENDING_VERIFICATION", 1.0));
    }

    private int evidenceLevel(String context) {
          if (containsAny(context, "实习", "工作", "上线", "生产", "负责", "容器化")) return 4;
        if (containsAny(context, "测试结果", "作品", "代码仓库", "评测", "验收")) return 3;
          if (containsAny(context, "项目", "系统", "平台", "实现", "开发", "容器化")) return 2;
        if (containsAny(context, "课程", "证书", "认证", "学习")) return 1;
        return 0;
    }

    private String levelName(int level) {
        return switch (level) {
            case 4 -> "L4 实战";
            case 3 -> "L3 验证";
            case 2 -> "L2 实践";
            case 1 -> "L1 学习";
            default -> "L0 自述";
        };
    }

    private String sourceType(String context, int level) {
        if (level == 4) return "WORK_EXPERIENCE";
        if (level >= 2) return "PROJECT";
        if (containsAny(context, "证书", "认证")) return "CERTIFICATE";
        return "SELF_REPORT";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private MatchSkillDetail skillDetail(SkillRequirement requirement, SkillAlignmentService.Alignment alignment, int requiredLevel) {
        String bonusState = "BONUS".equals(requirement.requirementType()) ? "BONUS" : null;
        if (alignment == null) {
            String state = bonusState == null ? "MISSING" : bonusState;
            return new MatchSkillDetail(requirement.skill(), bonusState == null ? "UNMATCHED" : "BONUS", 0, requiredLevel, 0, "无简历证据",
                    bonusState == null ? "缺少岗位要求技能" : "当前未覆盖岗位加分项", "按学习路径完成项目并提交可验证证据",
                    requiredLevel, 0, state, List.of(), List.of(),
                    bonusState == null ? "个人能力镜像中没有该技能证据" : "该技能是岗位加分项，不作为关键缺口",
                    "完成一个使用 " + requirement.skill() + " 的可运行任务，并保留代码与测试结果", 0, false);
        }
        AbilityEvidence evidence = alignment.evidence();
        double score = Math.min(100, 45 + evidence.level() * 15);
        String gap = evidence.level() >= requiredLevel ? "已满足证据等级" : "技能已出现，但证据等级不足";
        String state = bonusState != null ? bonusState : evidence.level() >= requiredLevel ? "SATISFIED" : "EVIDENCE_INSUFFICIENT";
        String nextAction = evidence.level() >= requiredLevel ? "继续积累真实产出" : "将技能用于项目任务，并记录测试和结果";
        String matchType = bonusState == null ? alignment.method() : "BONUS";
        return new MatchSkillDetail(requirement.skill(), matchType, evidence.level(), requiredLevel, score, evidence.quote(), gap,
                nextAction, requiredLevel, evidence.level(), state, List.of(),
                List.of(new com.zhigang.platform.domain.PlatformModels.PersonalEvidence(evidence.sourceType(), evidence.quote(), evidence.level())),
                gap, nextAction, round(alignment.similarity()), alignment.manualConfirmed());
    }

    private double weightedSkillCoverage(List<SkillRequirement> requirements,
                                         Map<String, SkillAlignmentService.Alignment> alignments, int requiredLevel) {
        if (requirements.isEmpty()) return 100;
        double totalWeight = requirements.stream().mapToDouble(SkillRequirement::demandRate).sum();
        double earned = 0;
        for (SkillRequirement requirement : requirements) {
            SkillAlignmentService.Alignment alignment = alignments.get(requirement.skill());
            if (alignment != null) {
                AbilityEvidence evidence = alignment.evidence();
            double evidenceRatio = evidence.level() <= 0 ? 0.0 : evidence.level() == 1 ? 0.60 : evidence.level() == 2 ? 0.85 : 1.0;
                earned += requirement.demandRate() * evidenceRatio;
            }
        }
        return totalWeight == 0 ? 0 : earned / totalWeight * 100;
    }

    private double projectRelevance(ResumeProfile profile, JobRole role) {
        if (profile.projects().isEmpty()) return 25;
        String joined = String.join(" ", profile.projects());
        long matched = role.responsibilities().stream().map(this::keywords).filter(keywords -> keywords.stream().anyMatch(joined::contains)).count();
        return Math.min(100, 50 + matched * 50.0 / Math.max(1, role.responsibilities().size()));
    }

    private ResumeEvidenceBundle evidenceFor(ResumeProfile profile) {
        return resumeEvidence.computeIfAbsent(profile.id(), ignored -> evidenceFromProfile(profile));
    }

    private ResumeEvidenceBundle evidenceFromProfile(ResumeProfile profile) {
        String sourceHash = resumeEvidenceExtractor.extract("", List.of()).sourceSha256();
        List<ResumeEvidenceBundle.SkillEvidence> evidence = profile.abilities().stream().map(item -> {
            String contextType = item.level() >= 4 || "WORK_EXPERIENCE".equals(item.sourceType())
                    ? "PRODUCTION" : item.level() >= 2 ? "PROJECT" : item.level() == 1 ? "COURSE" : "SELF_REPORT";
            return new ResumeEvidenceBundle.SkillEvidence(item.skill(), item.quote(), -1, -1,
                    "MANUAL", item.level() >= 2 && !profile.projects().isEmpty() ? "project-1" : "",
                    contextType, "", item.level() >= 3 ? item.quote() : "", item.level(), false, item.confidence());
        }).toList();
        return new ResumeEvidenceBundle(ResumeEvidenceExtractor.VERSION, sourceHash, evidence);
    }

    private ResumeEvidenceBundle mergeEvidence(ResumeProfile profile, ResumeEvidenceBundle existing, boolean allowUpgrade) {
        if (existing == null) return evidenceFromProfile(profile);
        java.util.Map<String, Integer> existingLevels = existing.evidence().stream()
                .filter(item -> !item.negated())
                .collect(java.util.stream.Collectors.toMap(ResumeEvidenceBundle.SkillEvidence::skill,
                        ResumeEvidenceBundle.SkillEvidence::evidenceLevel, Math::max));
        List<ResumeEvidenceBundle.SkillEvidence> merged = new ArrayList<>(existing.evidence().stream()
                .filter(item -> profile.abilities().stream().anyMatch(value -> value.skill().equals(item.skill())))
                .toList());
        for (AbilityEvidence ability : profile.abilities()) {
            if (!existingLevels.containsKey(ability.skill()) ||
                    (allowUpgrade && ability.level() > existingLevels.getOrDefault(ability.skill(), -1))) {
                merged.add(syntheticEvidence(profile, ability));
            }
        }
        return new ResumeEvidenceBundle(existing.extractorVersion(), existing.sourceSha256(), merged);
    }

    private ResumeEvidenceBundle.SkillEvidence syntheticEvidence(ResumeProfile profile, AbilityEvidence item) {
        String contextType = item.level() >= 4 || "WORK_EXPERIENCE".equals(item.sourceType())
                ? "PRODUCTION" : item.level() >= 2 ? "PROJECT" : item.level() == 1 ? "COURSE" : "SELF_REPORT";
        return new ResumeEvidenceBundle.SkillEvidence(item.skill(), item.quote(), -1, -1,
                "MANUAL", item.level() >= 2 && !profile.projects().isEmpty() ? "project-1" : "",
                contextType, "", item.level() >= 3 ? item.quote() : "", item.level(), false, item.confidence());
    }

    private String genericApplicationState(double readiness, double requiredScore, double responsibilityScore,
                                           long criticalGaps, boolean educationGate) {
        boolean directReady = criticalGaps == 0 && requiredScore >= 65 && responsibilityScore >= 45;
        return educationGate && directReady && readiness >= 65 ? "可申请实习/校招"
                : criticalGaps <= 1 && readiness >= 55 ? "接近可申请" : "需先补关键能力";
    }

    private Set<String> keywords(String value) {
        return java.util.Arrays.stream(value.split("[、，和与 ]"))
                .filter(token -> token.length() >= 2).collect(Collectors.toSet());
    }

    private MatchDimension dimension(String name, double weight, double score, String explanation) {
        return new MatchDimension(name, weight, round(score), round(score * weight), explanation);
    }

    private boolean educationPassed(String education) {
        return education.contains("本科") || education.contains("硕士") || education.contains("博士");
    }

    private AbilityEvidence stronger(AbilityEvidence left, AbilityEvidence right) {
        return left.level() >= right.level() ? left : right;
    }

    private String firstMatch(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return fallback;
        String value = matcher.group(1).trim();
        if (value.contains("博士")) return "博士";
        if (value.contains("硕士") || value.contains("研究生")) return "硕士";
        if (value.contains("本科") || value.contains("学士")) return "本科";
        return value;
    }

    private String extractEducation(String text) {
        String chinese = firstMatch(EDUCATION_PATTERN, text, "未明确");
        if (!"未明确".equals(chinese)) return chinese;
        if (ENGLISH_DOCTORATE_PATTERN.matcher(text).find()) return "博士";
        if (ENGLISH_MASTER_PATTERN.matcher(text).find()) return "硕士";
        if (ENGLISH_BACHELOR_PATTERN.matcher(text).find()) return "本科";
        if (ENGLISH_DIPLOMA_PATTERN.matcher(text).find()) return "专科";
        return "未明确";
    }

    private String extractMajor(String text) {
        Matcher labeled = LABELED_MAJOR_PATTERN.matcher(text);
        if (labeled.find()) {
            String candidate = labeled.group(1).trim();
            String known = KNOWN_MAJORS.stream().filter(candidate::contains).findFirst().orElse(null);
            if (known != null) return known;
            if (candidate.length() <= 24 && !INVALID_MAJOR_WORDS.matcher(candidate).find()
                    && MAJOR_SUFFIX.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return KNOWN_MAJORS.stream().filter(text::contains).findFirst().orElse("未明确");
    }

    private List<String> extractLines(String text, List<String> markers) {
        return java.util.Arrays.stream(text.split("[\\n。]"))
                .map(String::trim).filter(line -> !line.isBlank())
                .filter(line -> markers.stream().anyMatch(line::contains)).limit(8).toList();
    }

    private List<String> extractProjects(String text) {
        List<String> unifiedEvidence = resumeEvidenceExtractor.extractProjectTexts(text);
        List<String> explicitProjects = new ArrayList<>();
        List<String> namedProjects = new ArrayList<>();
        List<String> experienceEvidence = new ArrayList<>();
        String activeSection = null;
        boolean explicitProjectSection = false;
        int navigationStreak = 0;
        String projectText = text == null ? "" : text
                .replaceAll("(?i)(?<![\\p{L}\\p{N}_])(?=export\\s+const\\s+(?:projects?|works?|experiences?)\\b)", "\\n")
                .replaceAll("(?i)(?<![\\p{L}\\p{N}_])(?=[\\\"']?(?:projects?|works?|experiences?)[\\\"']?\\s*[:=])", "\\n");
        for (String rawLine : projectText.split("[\\r\\n]+")) {
            String line = rawLine.trim();
            if (line.isBlank()) continue;
            String section = ResumeSectionHeadings.section(line);
            if (ResumeSectionHeadings.SOURCE.equals(section)) break;
            if (section != null) {
                if (ResumeSectionHeadings.isNavigationHeading(line)) {
                    navigationStreak++;
                } else if (!ResumeSectionHeadings.isAmbiguousProjectsHeading(line)) {
                    navigationStreak = 0;
                }
                if (ResumeSectionHeadings.PROJECT.equals(section)
                        && ResumeSectionHeadings.isAmbiguousProjectsHeading(line) && navigationStreak >= 2) {
                    activeSection = ResumeSectionHeadings.OTHER;
                    explicitProjectSection = false;
                    continue;
                }
                activeSection = section;
                explicitProjectSection = ResumeSectionHeadings.PROJECT.equals(section);
                continue;
            }
            navigationStreak = 0;
            if (ResumeSectionHeadings.isExperience(activeSection)
                    && ResumeSectionHeadings.isInlineProjectHeading(line)) {
                (explicitProjectSection ? explicitProjects : namedProjects).add(line);
                activeSection = ResumeSectionHeadings.PROJECT;
                continue;
            }
            if (ResumeSectionHeadings.PROJECT.equals(activeSection)) {
                if (isProjectBodyLine(line)) {
                    (explicitProjectSection ? explicitProjects : namedProjects).add(line);
                }
                continue;
            }
            if (!ResumeSectionHeadings.WORK.equals(activeSection)
                    && !ResumeSectionHeadings.INTERNSHIP.equals(activeSection)) continue;
            if (ResumeEvidenceSignals.hasAction(line)) {
                experienceEvidence.add(line);
            }
        }
        List<String> selected = !explicitProjects.isEmpty() ? explicitProjects
                : !namedProjects.isEmpty() ? namedProjects : experienceEvidence;
        if (!selected.isEmpty()) {
            return selected.stream().distinct().limit(8).toList();
        }
        return unifiedEvidence.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> ResumeSectionHeadings.section(value) == null)
                .distinct()
                .limit(8)
                .toList();
    }

    private boolean isProjectBodyLine(String line) {
        String normalized = line.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.length() >= 4 && !Set.of("home", "about", "contact", "hello", "projects").contains(normalized);
    }

    private ResumeProfile sampleResume(long id) {
        if (id != 1L) {
            throw new BusinessException("RESUME_NOT_FOUND", "未找到简历，请先提交简历解析");
        }
        String sample = "姓名：陈同学\n学历：本科 软件工程\n技能：Java、Spring Boot、MySQL、Redis、Linux、Docker。\n"
                + "项目：校园二手交易平台，负责后端接口、数据库设计和 Redis 缓存；完成企业知识库课程项目，学习 RAG 和大语言模型。\n证书：软考软件设计师。";
        ResumeProfile profile = withMetadata(new ResumeProfile(1, "陈同学", "本科", "软件工程", 2027, 12,
                normalizationService.extract(sample).stream().map(fact -> ability(fact, sample)).toList(),
                extractLines(sample, List.of("项目", "系统", "平台")), extractLines(sample, List.of("证书", "软考")), "CONFIRMED"));
        resumes.put(1L, profile);
        resumeOwners.putIfAbsent(1L, "TEST_SESSION");
        resumeCreatedAt.putIfAbsent(1L, OffsetDateTime.now());
        return profile;
    }

    @Scheduled(fixedDelayString = "${zhigang.privacy.cleanup-delay-ms:3600000}")
    void cleanupExpiredProfiles() {
        OffsetDateTime now = OffsetDateTime.now();
        resumeCreatedAt.entrySet().stream()
                .filter(entry -> Duration.between(entry.getValue(), now).toDays() >= 7)
                .map(Map.Entry::getKey).toList()
                .forEach(id -> {
                    resumes.remove(id);
                    resumeEvidence.remove(id);
                    resumeOwners.remove(id);
                    resumeCreatedAt.remove(id);
                    removeDerivedResults(id);
                });
    }

    private void assertOwner(long resumeId, String owner) {
        if (resumeId == 1L && !resumeOwners.containsKey(resumeId)) return;
        String actual = resumeOwners.get(resumeId);
        if (actual != null && actual.equals(owner)) return;
        if (actual == null && !resumes.containsKey(resumeId) && resumeId == 1L && "TEST_SESSION".equals(owner)) return;
        throw new BusinessException("RESUME_ACCESS_DENIED", "该匿名能力档案不存在或不属于当前会话");
    }

    private void removeDerivedResults(long resumeId) {
        Set<Long> removedMatchIds = matches.values().stream().filter(value -> value.resumeId() == resumeId)
                .map(MatchReport::id).collect(Collectors.toSet());
        matches.keySet().removeAll(removedMatchIds);
        Set<Long> removedPlanIds = plans.values().stream().filter(value -> removedMatchIds.contains(value.matchRunId()))
                .map(LearningPlan::id).collect(Collectors.toSet());
        plans.keySet().removeAll(removedPlanIds);
        submissions.entrySet().removeIf(entry -> removedPlanIds.contains(entry.getValue().learningPlanId()));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private ResumeProfile withMetadata(ResumeProfile profile) {
        return new ResumeProfile(profile.id(), profile.name(), profile.education(), profile.major(), profile.graduationYear(),
                profile.weeklyHours(), profile.abilities(), profile.projects(), profile.certificates(), profile.status(),
                metadata(null, false), profile.facts(), profile.parsingMode(), profile.redactedFields());
    }

    private ResultMetadata metadata(String graphRelease, boolean evidenceAvailable) {
        String dataMode = dataProvider.dataMode();
        return new ResultMetadata(dataMode, graphRelease, "2025-08 至 2026-07", null, null, null, evidenceAvailable);
    }

    private List<String> topologicalOrder(Set<String> missingSkills, Map<String, List<String>> graph) {
        Set<String> relevant = new HashSet<>(missingSkills);
        ArrayDeque<String> expansion = new ArrayDeque<>(missingSkills);
        while (!expansion.isEmpty()) {
            String skill = expansion.removeFirst();
            for (String prerequisite : graph.getOrDefault(skill, List.of())) {
                if (relevant.add(prerequisite)) expansion.addLast(prerequisite);
            }
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        relevant.forEach(skill -> indegree.put(skill, 0));
        for (String skill : relevant) {
            for (String prerequisite : graph.getOrDefault(skill, List.of())) {
                if (relevant.contains(prerequisite)) {
                    outgoing.computeIfAbsent(prerequisite, ignored -> new ArrayList<>()).add(skill);
                    indegree.compute(skill, (key, value) -> value == null ? 1 : value + 1);
                }
            }
        }
        ArrayDeque<String> queue = indegree.entrySet().stream().filter(entry -> entry.getValue() == 0).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayDeque::new));
        List<String> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String skill = queue.removeFirst();
            ordered.add(skill);
            for (String next : outgoing.getOrDefault(skill, List.of())) {
                int nextDegree = indegree.compute(next, (key, value) -> value - 1);
                if (nextDegree == 0) queue.addLast(next);
            }
        }
        if (ordered.size() != relevant.size()) {
            missingSkills.stream().filter(skill -> !ordered.contains(skill)).forEach(ordered::add);
        }
        return List.copyOf(ordered);
    }

    private Map<String, List<LearningResource>> resourceIndex() {
        Map<String, List<LearningResource>> index = new LinkedHashMap<>();
        dataProvider.findLearningResources().forEach(resource -> resource.skills()
                .forEach(skill -> index.computeIfAbsent(skill, ignored -> new ArrayList<>()).add(resource)));
        return index;
    }

    private int estimateHours(String skill) {
        return switch (skill) {
            case "RAG", "模型评测", "Kubernetes" -> 28;
            case "向量数据库", "Embedding", "Spring Cloud" -> 20;
            default -> 14;
        };
    }

    private String stage(int index, int total) {
        double ratio = index / (double) Math.max(1, total);
        if (ratio < 0.34) return "可申请实习";
        if (ratio < 0.72) return "可参加校招";
        return "入职后进阶";
    }

    private String practicalTask(String skill) {
        return switch (skill) {
            case "RAG" -> "实现文档切分、检索、重排、生成和引用来源的知识库问答系统";
            case "Embedding", "向量数据库" -> "比较两种切分策略，并输出 Recall@K 检索结果";
            case "模型评测" -> "建立 30 条评测集，统计引用正确率与无依据回答率";
            default -> "在目标岗位项目中完成一个可运行模块，并记录设计与测试结果";
        };
    }

    private String acceptanceCriteria(String skill) {
        return switch (skill) {
            case "RAG" -> "能追溯回答引用，离线评测集 Recall@5 不低于 80%";
            case "模型评测" -> "提交测试集、指标定义、结果表和错误分析";
            default -> "提交代码、README、测试结果和 3 分钟演示证据";
        };
    }

    private double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
