package com.zhigang.platform.emerging;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryEvidenceDraft;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryFieldEvidenceDraft;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class EmergingQueryDiscoveryServiceTest {
    private final EmergingQueryRepository repository = mock(EmergingQueryRepository.class);
    private final PlatformDataProvider dataProvider = mock(PlatformDataProvider.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final EmergingQueryVectorCache vectorCache = mock(EmergingQueryVectorCache.class);
    private final EmergingQueryGenerator generator = mock(EmergingQueryGenerator.class);
    private final EmergingQueryDiscoveryService service = new EmergingQueryDiscoveryService(repository, dataProvider,
            embeddingClient, vectorCache, generator, 0.93, 0.88, 3, 2);

    @Test
    void exactNormalizedQueryUsesStoredResultWithoutCallingModelOrEmbedding() {
        QueryDiscovery stored = discovery(true);
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.of(stored));

        QueryDiscovery result = service.discover(" 提示词工程师 ");

        assertThat(result.fromCache()).isTrue();
        assertThat(result.cacheSimilarity()).isEqualTo(1);
        verifyNoInteractions(embeddingClient, vectorCache, generator, dataProvider);
    }

    @Test
    void legacyPureEnglishEmergingResultIsRegeneratedInsteadOfReused() {
        QueryDiscovery legacy = withName(discovery(true), "Machine Learning Engineer");
        when(repository.findByNormalizedQuery("machinelearningengineer")).thenReturn(Optional.of(legacy));
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(generator.generate(any(), any())).thenReturn(new EmergingQueryGenerator.GenerationResult(
                "EVIDENCE_INSUFFICIENT", "EVIDENCE_INSUFFICIENT", true, "人工智能", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false,
                "input-hash", "generalv3", "WEB_EVIDENCE_INSUFFICIENT", "资料不足"));
        when(repository.save(any())).thenReturn(discovery(false, "EVIDENCE_INSUFFICIENT"));

        service.discover("Machine Learning Engineer");

        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void removedPublishedRoleIsRegeneratedSoItCanBePublishedAgain() {
        QueryDiscovery removed = withPublication(discovery(true), 13, 2);
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.of(removed));
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(generator.generate(any(), any())).thenReturn(new EmergingQueryGenerator.GenerationResult(
                "EMERGING_ROLE", "WEB", true, "人工智能", "提示词工程师", "岗位定义",
                List.of("设计提示策略"), List.of("大语言模型"), List.of(), List.of("AI 应用优化"),
                List.of(), List.of(), true, "input-hash", "generalv3", "", ""));
        when(repository.save(any())).thenReturn(discovery(false));

        QueryDiscovery result = service.discover("提示词工程师");

        assertThat(result.publishedRoleId()).isNull();
        assertThat(result.status()).isEqualTo("EMERGING_ROLE");
        org.mockito.Mockito.verifyNoInteractions(generator);
    }

    @Test
    void removedPublishedRoleReplayIsReturnedAsPublishableEmergingResult() {
        QueryDiscovery removed = withPublication(discovery(true), 13, 2);
        QueryDiscovery replayed = discovery(true);
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.of(removed));
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(repository.save(any())).thenReturn(replayed);

        QueryDiscovery result = service.discover("提示词工程师");

        assertThat(result.status()).isEqualTo("EMERGING_ROLE");
        assertThat(result.publishable()).isTrue();
        assertThat(result.publishedRoleId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(embeddingClient, vectorCache, generator);
    }

    @Test
    void rejectsEmptyNormalizedOrMojibakeQueriesWithoutCallingDependencies() {
        assertThatThrownBy(() -> service.discover("??"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("空白或标点");
        assertThatThrownBy(() -> service.discover("锟斤拷岗位"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("乱码");

        verifyNoInteractions(repository, embeddingClient, vectorCache, generator, dataProvider);
    }

    @Test
    void recentPublicQueriesExcludeInvalidStoredRecords() {
        QueryDiscovery valid = discovery(true);
        QueryDiscovery invalid = new QueryDiscovery(9, "???", "", "EMERGING_ROLE", "人工智能",
                "锟斤拷岗位", "岗位定义", List.of(), List.of(), List.of(), List.of(), null, "", 0,
                0, 0, 0, false, false, false, "", "", "", null, null, List.of(), List.of(), false,
                0, "", null, valid.createdAt(), valid.updatedAt());
        when(repository.findRecent(50)).thenReturn(List.of(invalid, valid));

        assertThat(service.recent(12)).extracting(QueryDiscovery::id).containsExactly(valid.id());
    }

    @Test
    void recentPublicQueriesExcludeLegacyPureEnglishEmergingRoleNames() {
        QueryDiscovery valid = discovery(true);
        QueryDiscovery english = withName(valid, "Machine Learning Engineer");
        when(repository.findRecent(50)).thenReturn(List.of(english, valid));

        assertThat(service.recent(12)).extracting(QueryDiscovery::name).containsExactly("提示词工程师");
    }

    @Test
    void manualPublicationAllowsCompleteItRoleWithoutFixedEvidenceCount() {
        QueryDiscovery stored = discovery(true);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(repository.publish(stored.id(), "用户手动加入图谱")).thenReturn(stored);

        assertThat(service.publish(stored.id())).isSameAs(stored);
        org.mockito.Mockito.verify(repository).publish(stored.id(), "用户手动加入图谱");
    }

    @Test
    void manualPublicationReturnsPublishedRoleAndIsIdempotent() {
        QueryDiscovery stored = discovery(true);
        QueryDiscovery published = withPublication(stored, 99L, 101L);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored), Optional.of(published));
        when(repository.publish(stored.id(), "用户手动加入图谱")).thenReturn(published);

        QueryDiscovery first = service.publish(stored.id());
        QueryDiscovery second = service.publish(stored.id());

        assertThat(first.publishedRoleId()).isEqualTo(99L);
        assertThat(second.publishedRoleId()).isEqualTo(99L);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1))
                .publish(stored.id(), "用户手动加入图谱");
    }

    @Test
    void removalClearsTheCurrentPublicationReference() {
        QueryDiscovery published = withPublication(discovery(true), 99L, 101L);
        QueryDiscovery removed = discovery(true);
        when(repository.findById(published.id())).thenReturn(Optional.of(published));
        when(repository.removeFromGraph(published.id(), "用户手动移出图谱")).thenReturn(removed);

        QueryDiscovery result = service.removeFromGraph(published.id());

        assertThat(result.publishedRoleId()).isNull();
        assertThat(result.publishedReleaseId()).isNull();
        org.mockito.Mockito.verify(repository).removeFromGraph(published.id(), "用户手动移出图谱");
    }

    @Test
    void removalAllowsLegacyPublishedDiscoveryWithStaleStatus() {
        QueryDiscovery published = withPublication(discovery(false, "EVIDENCE_INSUFFICIENT"), 16L, 3L);
        QueryDiscovery removed = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findById(published.id())).thenReturn(Optional.of(published));
        when(repository.removeFromGraph(published.id(), "用户手动移出图谱")).thenReturn(removed);

        assertThat(service.removeFromGraph(published.id()).publishedRoleId()).isNull();
    }

    @Test
    void removalRejectsAnUnpublishedDiscovery() {
        QueryDiscovery stored = discovery(true);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.removeFromGraph(stored.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未加入图谱");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXISTING_ROLE", "WAITING_GENERATION", "OUT_OF_SCOPE"})
    void manualPublicationRejectsEveryNonEmergingStatus(String status) {
        QueryDiscovery stored = discovery(status, true, true);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.publish(stored.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("新兴岗位");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .publish(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void manualPublicationAllowsReviewedEvidenceInsufficientRecord() {
        QueryDiscovery stored = discovery("EVIDENCE_INSUFFICIENT", true, true);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(repository.publish(stored.id(), "用户手动加入图谱")).thenReturn(stored);

        assertThat(service.publish(stored.id())).isSameAs(stored);
        org.mockito.Mockito.verify(repository).publish(stored.id(), "用户手动加入图谱");
    }

    @Test
    void manualPublicationRejectsIncompleteFieldEvidence() {
        QueryDiscovery stored = discovery("EMERGING_ROLE", false, true);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.publish(stored.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字段证据");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .publish(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void manualPublicationRejectsDiscoveryMarkedNotPublishable() {
        QueryDiscovery stored = discovery("EMERGING_ROLE", true, false);
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.publish(stored.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发布条件");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .publish(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void manualPublicationRejectsLegacyPureEnglishRoleName() {
        QueryDiscovery stored = withName(discovery(true), "Machine Learning Engineer");
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.publish(stored.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("中文岗位名称");
    }

    @Test
    void existingRoleMatchDoesNotSpendSparkQuota() {
        JobRole role = new JobRole(12, "JAVA_BACKEND", "Java 开发工程师", "软件开发", "通用", false,
                "负责 Java 服务端系统设计与开发", "", List.of("开发后端服务"), List.of(), List.of(),
                List.of("企业应用开发"), "v1", OffsetDateTime.parse("2026-08-03T12:00:00+08:00"));
        QueryDiscovery stored = new QueryDiscovery(8, "Java 开发工程师", "java开发工程师", "EXISTING_ROLE", "软件开发",
                role.name(), role.definition(), role.responsibilities(), List.of(), List.of(), role.scenarios(), role.id(),
                role.name(), 1, 0, 0, 0, false, false, false, "LOCAL_TITLE_MATCH", "EXISTING_ROLE_MATCH",
                "已匹配现有岗位，无需联网创建新岗位", null, null, List.of(), List.of(), false, 0, "", null,
                OffsetDateTime.parse("2026-08-03T12:00:00+08:00"), OffsetDateTime.parse("2026-08-03T12:00:00+08:00"));
        when(repository.findByNormalizedQuery("java开发工程师")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(repository.save(any())).thenReturn(stored);

        QueryDiscovery result = service.discover("Java 开发工程师");

        assertThat(result.status()).isEqualTo("EXISTING_ROLE");
        assertThat(result.webSearchUsed()).isFalse();
        verifyNoInteractions(generator);
    }

    @Test
    void localTitleCoreMatchesAiAgentToAiAgentDevelopmentEngineerWithoutExternalServices() {
        JobRole role = role(12, "AI_AGENT", "AI Agent 开发工程师");
        QueryDiscovery stored = existingRoleDiscovery(8, "AI Agent", role);
        when(repository.findByNormalizedQuery("aiagent")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(repository.save(any())).thenReturn(stored);

        QueryDiscovery result = service.discover("AI Agent");

        assertThat(result.status()).isEqualTo("EXISTING_ROLE");
        assertThat(result.nearestRoleName()).isEqualTo("AI Agent 开发工程师");
        verifyNoInteractions(generator);
    }

    @Test
    void unknownPromptEngineerIsNotPromotedToExistingRoleByTitleFallback() {
        JobRole role = role(12, "AI_AGENT", "AI Agent 开发工程师");
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "暂未找到足够证据"));
        when(repository.save(any())).thenReturn(stored);

        QueryDiscovery result = service.discover("提示词工程师");

        assertThat(result.status()).isEqualTo("EVIDENCE_INSUFFICIENT");
        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void localTitleFallbackAllowsAtMostFourAdditionalCoreCharacters() {
        JobRole role = role(21, "VECTOR_PLATFORM", "向量检索应用平台工程师");
        QueryDiscovery stored = existingRoleDiscovery(9, "向量检索", role);
        when(repository.findByNormalizedQuery("向量检索")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(repository.save(any())).thenReturn(stored);

        QueryDiscovery result = service.discover("向量检索");

        assertThat(result.status()).isEqualTo("EXISTING_ROLE");
        verifyNoInteractions(generator);
    }

    @Test
    void localTitleFallbackRejectsMoreThanFourAdditionalCoreCharacters() {
        JobRole role = role(22, "VECTOR_SMART_PLATFORM", "向量检索智能应用平台工程师");
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("向量检索")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "暂未找到足够证据"));
        when(repository.save(any())).thenReturn(stored);

        service.discover("向量检索");

        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void localTitleFallbackOnlyAllowsCandidateCoreToExtendQueryCore() {
        JobRole role = role(23, "VECTOR", "向量检索工程师");
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("智能向量检索")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "暂未找到足够证据"));
        when(repository.save(any())).thenReturn(stored);

        service.discover("智能向量检索");

        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void localTitleFallbackIsNotUsedWhenEmbeddingIsAvailable() {
        JobRole role = role(24, "AI_AGENT", "AI Agent 开发工程师");
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("aiagent")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(role));
        when(embeddingClient.embed(any())).thenReturn(
                List.of(List.of(1d, 0d)), List.of(List.of(0d, 1d)));
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "暂未找到足够证据"));
        when(repository.save(any())).thenReturn(stored);

        service.discover("AI Agent");

        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void equallySpecificLocalTitleCandidatesAreTreatedAsAmbiguous() {
        JobRole first = role(25, "VECTOR_APP", "向量检索应用平台工程师");
        JobRole second = role(26, "VECTOR_SERVICE", "向量检索服务平台工程师");
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("向量检索")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of(first, second));
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "暂未找到足够证据"));
        when(repository.save(any())).thenReturn(stored);

        service.discover("向量检索");

        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void insufficientEvidenceIsRefreshedInsteadOfPermanentlyCached() {
        QueryDiscovery insufficient = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.of(insufficient));
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "仍未找到足够证据"));
        when(repository.save(any())).thenReturn(insufficient);

        QueryDiscovery result = service.discover("提示词工程师");

        assertThat(result.fromCache()).isFalse();
        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void lowSimilarityModelExistingResultIsRefreshedInsteadOfCached() {
        QueryDiscovery disputed = discovery(false, "EXISTING_ROLE");
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.of(disputed));
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(generator.generate(any(), any())).thenReturn(EmergingQueryGenerator.GenerationResult.insufficient(
                "input-hash", "generalv3", "重新检索后证据仍不足"));
        when(repository.save(any())).thenReturn(disputed);

        QueryDiscovery result = service.discover("提示词工程师");

        assertThat(result.fromCache()).isFalse();
        org.mockito.Mockito.verify(generator).generate(any(), any());
    }

    @Test
    void modelCannotOverrideLocalExistingRoleThreshold() {
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        when(generator.generate(any(), any())).thenReturn(new EmergingQueryGenerator.GenerationResult(
                "READY", "EXISTING_ROLE", true, "人工智能", "提示词工程师", "岗位定义",
                List.of("设计提示"), List.of("大语言模型"), List.of(), List.of("AI 应用"),
                List.of(), List.of(), true, "input-hash", "generalv3", "", ""));
        when(repository.save(any())).thenReturn(stored);

        QueryDiscovery result = service.discover("提示词工程师");

        assertThat(result.status()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(result.publishable()).isFalse();
    }

    @Test
    void completeStructuredEmergingResultIsPublishableWithoutFixedEvidenceCount() {
        QueryDiscovery stored = discovery(false, "EVIDENCE_INSUFFICIENT");
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        List<QueryEvidenceDraft> evidence = List.of(
                new QueryEvidenceDraft("E1", "来源一", "one.example.cn", "one.example.cn",
                        "https://one.example.cn/a", "", "支持岗位字段的正文"),
                new QueryEvidenceDraft("E2", "来源二", "two.example.cn", "two.example.cn",
                        "https://two.example.cn/b", "", "支持岗位字段的正文")
        );
        when(generator.generate(any(), any())).thenReturn(new EmergingQueryGenerator.GenerationResult(
                "READY", "EMERGING_ROLE", true, "人工智能", "提示词工程师", "岗位定义",
                List.of("设计提示"), List.of("大语言模型"), List.of(), List.of("AI 应用"), evidence,
                List.of(new QueryFieldEvidenceDraft("NAME", "提示词工程师", "E1")), true,
                "input-hash", "4.0Ultra", "", ""));
        when(repository.save(any())).thenReturn(stored);

        service.discover("提示词工程师");

        ArgumentCaptor<QueryDraft> draft = ArgumentCaptor.forClass(QueryDraft.class);
        org.mockito.Mockito.verify(repository).save(draft.capture());
        assertThat(draft.getValue().status()).isEqualTo("EMERGING_ROLE");
        assertThat(draft.getValue().publishable()).isTrue();
        assertThat(draft.getValue().errorCode()).isEmpty();
    }

    @Test
    void discoverLeavesEvidenceCompleteEmergingRoleForManualPublication() {
        QueryDiscovery saved = discovery(true);
        when(repository.findByNormalizedQuery("提示词工程师")).thenReturn(Optional.empty());
        when(dataProvider.findJobRoles()).thenReturn(List.of());
        List<QueryEvidenceDraft> evidence = List.of(
                new QueryEvidenceDraft("E1", "来源一", "one.example.cn", "one.example.cn", "https://one.example.cn/a", "", "职责证据"),
                new QueryEvidenceDraft("E2", "来源二", "two.example.cn", "two.example.cn", "https://two.example.cn/b", "", "技能证据"),
                new QueryEvidenceDraft("E3", "来源三", "three.example.cn", "three.example.cn", "https://three.example.cn/c", "", "场景证据")
        );
        when(generator.generate(any(), any())).thenReturn(new EmergingQueryGenerator.GenerationResult(
                "READY", "EMERGING_ROLE", true, "人工智能", "提示词工程师", "岗位定义",
                List.of("设计提示策略"), List.of("大语言模型"), List.of(), List.of("AI 应用"), evidence,
                List.of(new QueryFieldEvidenceDraft("NAME", "提示词工程师", "E1")), true,
                "input-hash", "4.0Ultra", "", ""));
        when(repository.save(any())).thenReturn(saved);

        service.discover("提示词工程师");

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .publish(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    private QueryDiscovery discovery(boolean publishable) {
        return discovery(publishable, "EMERGING_ROLE");
    }

    private QueryDiscovery discovery(boolean publishable, String status) {
        return discovery(status, true, publishable);
    }

    private QueryDiscovery discovery(String status, boolean fieldEvidenceComplete, boolean publishable) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T12:00:00+08:00");
        return new QueryDiscovery(7, "提示词工程师", "提示词工程师", status, "人工智能",
                "提示词工程师", "岗位定义", List.of("设计提示策略"), List.of("大语言模型"), List.of(),
                List.of("AI 应用优化"), null, "AI Agent 开发工程师", 0.61, 0.39, 3, 2, fieldEvidenceComplete,
                true, publishable, "generalv3", "", "", null, null, List.of(), List.of(), false,
                0, "", null, now, now);
    }

    private JobRole role(long id, String code, String name) {
        return new JobRole(id, code, name, "人工智能", "通用", false,
                "负责人工智能应用系统设计与开发", "", List.of("开发智能应用"), List.of(), List.of(),
                List.of("企业智能应用"), "v1", OffsetDateTime.parse("2026-08-03T12:00:00+08:00"));
    }

    private QueryDiscovery existingRoleDiscovery(long id, String query, JobRole role) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T12:00:00+08:00");
        return new QueryDiscovery(id, query, EmergingQueryDiscoveryService.normalize(query), "EXISTING_ROLE",
                role.family(), role.name(), role.definition(), role.responsibilities(), List.of(), List.of(),
                role.scenarios(), role.id(), role.name(), 1, 0, 0, 0, false, false, false,
                "LOCAL_TITLE_MATCH", "EXISTING_ROLE_MATCH", "已匹配现有岗位", null, null,
                List.of(), List.of(), false, 0, "", null, now, now);
    }

    private QueryDiscovery withPublication(QueryDiscovery value, long roleId, long releaseId) {
        return new QueryDiscovery(value.id(), value.query(), value.normalizedQuery(), value.status(), value.domain(),
                value.name(), value.definition(), value.responsibilities(), value.requiredSkills(), value.bonusSkills(),
                value.scenarios(), value.nearestRoleId(), value.nearestRoleName(), value.nearestRoleSimilarity(),
                value.noveltyScore(), value.evidenceCount(), value.sourceCount(), value.fieldEvidenceComplete(),
                value.webSearchUsed(), value.publishable(), value.modelName(), value.errorCode(), value.errorMessage(),
                roleId, releaseId, value.evidence(), value.fieldEvidence(), value.fromCache(), value.cacheSimilarity(),
                value.matchedQuery(), OffsetDateTime.parse("2026-08-03T12:30:00+08:00"),
                value.createdAt(), value.updatedAt());
    }

    private QueryDiscovery withName(QueryDiscovery value, String name) {
        return new QueryDiscovery(value.id(), value.query(), value.normalizedQuery(), value.status(), value.domain(),
                name, value.definition(), value.responsibilities(), value.requiredSkills(), value.bonusSkills(),
                value.scenarios(), value.nearestRoleId(), value.nearestRoleName(), value.nearestRoleSimilarity(),
                value.noveltyScore(), value.evidenceCount(), value.sourceCount(), value.fieldEvidenceComplete(),
                value.webSearchUsed(), value.publishable(), value.modelName(), value.errorCode(), value.errorMessage(),
                value.publishedRoleId(), value.publishedReleaseId(), value.evidence(), value.fieldEvidence(),
                value.fromCache(), value.cacheSimilarity(), value.matchedQuery(), value.publishedAt(),
                value.createdAt(), value.updatedAt());
    }
}
