package com.zhigang.platform.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.evolution.RoleEvolutionModels.SkillAnchor;
import com.zhigang.platform.evolution.RoleEvolutionModels.SourceDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleEvolutionStatisticsEngineTest {
    private RoleEvolutionStatisticsEngine engine;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        engine = new RoleEvolutionStatisticsEngine(objectMapper, new RoleEvolutionPolicyLoader(objectMapper));
    }

    @Test
    void formsControlledWindowsAndClassifiesAllFiveStates() {
        List<SourceDocument> documents = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            boolean baseline = index < 20;
            int ordinal = baseline ? index : index - 20;
            List<String> skills = new ArrayList<>(List.of("Java", "稳定技能"));
            if (baseline && ordinal < 16 || !baseline && ordinal < 4) skills.add("旧框架");
            if (baseline && ordinal < 4 || !baseline && ordinal < 16) skills.add("云原生");
            if (baseline && ordinal < 1 || !baseline && ordinal < 10) skills.add("新增技能");
            if (baseline && ordinal < 10) skills.add("候选删除技能");
            documents.add(document(index + 1, baseline ? LocalDate.of(2026, 3 + ordinal % 3, 1 + ordinal % 20)
                    : LocalDate.of(2026, 6 + ordinal % 3, 1 + ordinal % 20), "企业" + index, skills));
        }

        var result = engine.analyze(2, "JAVA_ENGINEER", "Java 开发工程师", documents);

        assertThat(result.windows()).hasSize(2).allMatch(window -> window.companyCount() == 20);
        assertThat(result.changes()).anyMatch(change -> change.skill().equals("新增技能") && change.changeType().equals("NEW"));
        assertThat(result.changes()).anyMatch(change -> change.skill().equals("云原生") && change.changeType().equals("INCREASED"));
        assertThat(result.changes()).anyMatch(change -> change.skill().equals("旧框架") && change.changeType().equals("DECREASED"));
        assertThat(result.changes()).anyMatch(change -> change.skill().equals("候选删除技能") && change.changeType().equals("REMOVAL_CANDIDATE"));
        assertThat(result.changes()).anyMatch(change -> change.skill().equals("稳定技能") && change.changeType().equals("STABLE"));
        assertThat(result.changes().stream().filter(RoleEvolutionModels.ChangeDraft::publishable))
                .allMatch(change -> change.differenceLowerBound() > 0 || change.differenceUpperBound() < 0);
    }

    @Test
    void smallWindowsRemainInsufficientInsteadOfFabricatingTrend() {
        var result = engine.analyze(2, "JAVA_ENGINEER", "Java 开发工程师", List.of(
                document(1, LocalDate.of(2026, 3, 1), "企业甲", List.of("Java")),
                document(2, LocalDate.of(2026, 6, 1), "企业乙", List.of("云原生"))));

        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.conclusionLevel()).isEqualTo("INSUFFICIENT_TIME_DATA");
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void removalRequiresAbsenceInConsecutiveSampledMonths() {
        List<SourceDocument> documents = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            documents.add(document(index + 1, LocalDate.of(2026, 3 + index % 3, 1), "旧企业" + index,
                    List.of("Java", "旧技能")));
            List<String> currentSkills = index == 0 ? List.of("Java", "旧技能") : List.of("Java");
            documents.add(document(index + 101, LocalDate.of(2026, 6 + index % 2, 1), "新企业" + index, currentSkills));
        }

        var result = engine.analyze(2, "JAVA_ENGINEER", "Java 开发工程师", documents);
        var change = result.changes().stream().filter(item -> item.skill().equals("旧技能")).findFirst().orElseThrow();

        assertThat(change.changeType()).isNotEqualTo("REMOVAL_CANDIDATE");
        assertThat(change.consecutiveAbsentWindows()).isLessThan(2);
    }

    @Test
    void inputHashChangesWhenReviewedSkillEvidenceChanges() {
        List<SourceDocument> documents = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            documents.add(document(index + 1, LocalDate.of(2026, 3 + index % 3, 1), "旧企业" + index,
                    List.of("Java")));
            documents.add(document(index + 101, LocalDate.of(2026, 6 + index % 3, 1), "新企业" + index,
                    List.of("Java")));
        }
        var first = engine.analyze(2, "JAVA_ENGINEER", "Java 开发工程师", documents);
        List<SourceDocument> revised = new ArrayList<>(documents);
        revised.set(0, document(1, LocalDate.of(2026, 3, 1), "旧企业0", List.of("Java", "Docker")));
        var second = engine.analyze(2, "JAVA_ENGINEER", "Java 开发工程师", revised);

        assertThat(second.inputHash()).isNotEqualTo(first.inputHash());
        assertThat(second.runCode()).isNotEqualTo(first.runCode());
    }

    private SourceDocument document(long id, LocalDate date, String company, List<String> skills) {
        String quote = String.join("、", skills);
        List<SkillAnchor> anchors = skills.stream().map(skill -> {
            int start = quote.indexOf(skill);
            return new SkillAnchor(id, skill, skill, start, start + skill.length(), true, "SUPPORT");
        }).toList();
        return new SourceDocument(id, "Java 开发工程师", company, "共同来源", "https://example.test/" + id,
                "全国", "全职级", date, anchors);
    }
}
