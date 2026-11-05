package com.zhigang.platform.analysis;

import com.zhigang.platform.ai.ModelGateway;
import com.zhigang.platform.api.Requests.EvidenceExplanationRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.Evidence;
import com.zhigang.platform.domain.PlatformModels.EvidenceExplanation;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.MarketCard;
import com.zhigang.platform.domain.PlatformModels.ResultMetadata;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.reporting.JobAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EvidenceExplanationService {
    private static final Set<String> TYPES = Set.of("SKILL_REQUIREMENT", "ROLE_EVOLUTION", "LEARNING_ORDER");
    private final JobAnalysisService analysisService;
    private final PlatformDataProvider dataProvider;
    private final ModelGateway modelGateway;
    private final ConclusionEvidenceGate evidenceGate;
    private final AuthorizedEvidenceIndexer evidenceIndexer;

    @Autowired
    public EvidenceExplanationService(JobAnalysisService analysisService, PlatformDataProvider dataProvider,
                                      ModelGateway modelGateway, ConclusionEvidenceGate evidenceGate,
                                      AuthorizedEvidenceIndexer evidenceIndexer) {
        this.analysisService = analysisService;
        this.dataProvider = dataProvider;
        this.modelGateway = modelGateway;
        this.evidenceGate = evidenceGate;
        this.evidenceIndexer = evidenceIndexer;
    }

    public EvidenceExplanationService(JobAnalysisService analysisService, PlatformDataProvider dataProvider,
                                      ModelGateway modelGateway) {
        this(analysisService, dataProvider, modelGateway, new ConclusionEvidenceGate(2, 1), null);
    }

    public EvidenceExplanation explain(EvidenceExplanationRequest request) {
        if (!TYPES.contains(request.questionType())) {
            throw new BusinessException("UNSUPPORTED_EXPLANATION_TYPE", "只支持技能要求、岗位演化和学习顺序三类解释");
        }
        JobRole role = analysisService.role(request.jobRoleId());
        MarketCard card = analysisService.marketCard(role.id());
        String question = question(request, role);
        List<Evidence> registeredEvidence = card.evidence().stream()
                .filter(item -> request.skill() == null || request.skill().isBlank() || item.quote().contains(request.skill()))
                .limit(6).toList();
        List<Evidence> evidence = retrieve(question, registeredEvidence);
        ConclusionEvidenceGate.GateDecision gate = evidenceGate.evaluate(evidence);
        ResultMetadata metadata = new ResultMetadata(dataMode(), role.releaseVersion(), "2025-08 至 2026-07",
                card.sampleSize(), card.independentCompanies(), card.confidence(), gate.passed());
        if (!gate.passed()) {
            return new EvidenceExplanation(request.questionType(), "证据不足", gate.evidence(), true, metadata);
        }
        String evidenceText = gate.evidence().stream().map(item -> item.company() + "：" + item.quote())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new EvidenceExplanation(request.questionType(), modelGateway.explain(question, evidenceText),
                gate.evidence(), false, metadata);
    }

    private List<Evidence> retrieve(String question, List<Evidence> registeredEvidence) {
        Map<String, Evidence> merged = new LinkedHashMap<>();
        registeredEvidence.forEach(item -> merged.put(key(item), item));
        if (evidenceIndexer != null) {
            evidenceIndexer.search(question, 6).forEach(match -> {
                var fragment = match.fragment();
                Evidence item = new Evidence(fragment.documentId(), fragment.source(), fragment.company(),
                        fragment.publishedAt(), fragment.quote(), fragment.startOffset(), fragment.endOffset(),
                        1.0, "EVIDENCE_VALID");
                merged.putIfAbsent(key(item), item);
            });
        }
        return new ArrayList<>(merged.values()).stream().limit(6).toList();
    }

    private String key(Evidence evidence) {
        return evidence.documentId() + ":" + evidence.startOffset() + ":" + evidence.endOffset();
    }

    private String question(EvidenceExplanationRequest request, JobRole role) {
        return switch (request.questionType()) {
            case "SKILL_REQUIREMENT" -> "为什么岗位“" + role.name() + "”需要技能“" + request.skill() + "”？";
            case "ROLE_EVOLUTION" -> "为什么岗位“" + role.name() + "”在当前图谱版本发生这项变化？";
            default -> "为什么面向岗位“" + role.name() + "”的学习计划要优先学习“" + request.skill() + "”？";
        };
    }

    private String dataMode() {
        return dataProvider.dataMode();
    }
}
