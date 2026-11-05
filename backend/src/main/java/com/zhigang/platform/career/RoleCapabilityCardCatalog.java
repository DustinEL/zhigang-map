package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RoleCapabilityCardCatalog {
    private static final String RESOURCE = "matching/role-capability-cards-v2.json";
    private static final List<String> SENTENCE_MARKERS = List.of(
            "掌握", "熟悉", "具备", "能够", "负责", "经验", "能力", "包括", "涵盖", "要求");

    private final String version;
    private final String snapshotSha256;
    private final List<RoleCapabilityCard> cards;
    private final Map<Long, RoleCapabilityCard> byRoleId;

    public RoleCapabilityCardCatalog(ObjectMapper objectMapper) {
        try {
            byte[] bytes = new ClassPathResource(RESOURCE).getInputStream().readAllBytes();
            CatalogFile file = objectMapper.readValue(bytes, CatalogFile.class);
            if (file.cards() == null || file.cards().size() != 8) {
                throw new IllegalArgumentException("岗位能力卡必须完整覆盖 8 个标准岗位");
            }
            Map<Long, RoleCapabilityCard> indexed = new LinkedHashMap<>();
            for (RoleCapabilityCard card : file.cards()) {
                validate(card);
                if (indexed.putIfAbsent(card.roleId(), card) != null) {
                    throw new IllegalArgumentException("岗位能力卡 roleId 重复: " + card.roleId());
                }
            }
            this.version = file.schemaVersion();
            this.snapshotSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            this.cards = List.copyOf(indexed.values());
            this.byRoleId = Map.copyOf(indexed);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载岗位能力卡 " + RESOURCE, exception);
        }
    }

    public List<RoleCapabilityCard> cards() {
        return cards;
    }

    public String version() {
        return version;
    }

    public String snapshotSha256() {
        return snapshotSha256;
    }

    public Optional<RoleCapabilityCard> find(long roleId) {
        return Optional.ofNullable(byRoleId.get(roleId));
    }

    public RoleCapabilityCard card(JobRole role) {
        RoleCapabilityCard card = byRoleId.get(role.id());
        if (card == null) throw new IllegalArgumentException("岗位尚未建立冻结能力卡: " + role.id());
        if (!card.roleName().equals(role.name())) {
            throw new IllegalStateException("岗位能力卡与岗位快照名称不一致: " + role.id());
        }
        return card;
    }

    static void validate(RoleCapabilityCard card) {
        if (card.roleId() <= 0 || blank(card.roleCode()) || blank(card.roleName()) || blank(card.version())) {
            throw new IllegalArgumentException("岗位能力卡缺少身份或版本信息");
        }
        if (card.coreCapabilities() == null || card.coreCapabilities().size() < 3 || card.coreCapabilities().size() > 5) {
            throw new IllegalArgumentException("每个岗位必须配置 3 至 5 个核心能力组");
        }
        if (card.sourceReferences() == null || card.sourceReferences().isEmpty()) {
            throw new IllegalArgumentException("岗位能力卡必须包含来源引用");
        }
        for (RoleCapabilityCard.CapabilityGroup group : card.coreCapabilities()) {
            if (blank(group.id()) || group.alternatives() == null || group.alternatives().isEmpty()) {
                throw new IllegalArgumentException("核心能力组必须包含标识和替代技能");
            }
            if (group.nearEvidenceLevel() < 1 || group.nearEvidenceLevel() > 4
                    || group.readyEvidenceLevel() < group.nearEvidenceLevel() || group.readyEvidenceLevel() > 4) {
                throw new IllegalArgumentException("核心能力证据门槛必须位于 L1 至 L4 且 READY 不低于 NEAR");
            }
            group.alternatives().forEach(RoleCapabilityCardCatalog::validateAtomicSkill);
        }
        RoleCapabilityCard.DecisionPolicy policy = card.effectiveDecisionPolicy();
        Set<String> groupIds = card.coreCapabilities().stream()
                .map(RoleCapabilityCard.CapabilityGroup::id).collect(Collectors.toSet());
        if (!groupIds.containsAll(policy.nearAnchorGroupIds())) {
            throw new IllegalArgumentException("接近门槛锚点必须引用现有核心能力组");
        }
        if (!groupIds.containsAll(policy.nearRequiredAnyGroupIds())) {
            throw new IllegalArgumentException("接近组合门槛必须引用现有核心能力组");
        }
        if (!groupIds.containsAll(policy.nearRequiredAllGroupIds())) {
            throw new IllegalArgumentException("接近必备门槛必须引用现有核心能力组");
        }
        if (!groupIds.containsAll(policy.readyRequiredAnyGroupIds())
                || !groupIds.containsAll(policy.readyRequiredAllGroupIds())) {
            throw new IllegalArgumentException("可申请组合门槛必须引用现有核心能力组");
        }
        if (policy.readyMinimumCoreCoverage() < 0 || policy.readyMinimumCoreCoverage() > 1
                || policy.readyMinimumProjectRelevance() < 0 || policy.readyMinimumProjectRelevance() > 1
                || policy.readyMaximumBlockingGaps() < 0
                || policy.readyMaximumBlockingReadyShortfall() < 0) {
            throw new IllegalArgumentException("岗位决策策略门槛超出合法范围");
        }
    }

    private static void validateAtomicSkill(String skill) {
        boolean sentence = blank(skill) || skill.length() > 32 || skill.matches(".*[，。；：、].*")
                || SENTENCE_MARKERS.stream().anyMatch(skill::contains);
        if (sentence) throw new IllegalArgumentException("岗位核心项必须是原子技能: " + skill);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record CatalogFile(String schemaVersion, List<RoleCapabilityCard> cards) {
    }
}
