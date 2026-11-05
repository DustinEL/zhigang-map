package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateSeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEmergingDiscoveryRepositoryTest {
    @Test
    void candidateWithoutRagEvidenceCannotBeSubmitted() {
        InMemoryEmergingDiscoveryRepository repository = new InMemoryEmergingDiscoveryRepository();
        var run = repository.createRun("run-1", "v1", "hash", "input", "bge-m3", 5, "研究员");
        repository.markRunning(run.id());
        repository.complete(run.id(), 1024, "hdbscan", 1, 1, 0, List.of(new CandidateSeed(
                0, "WAITING_GENERATION", 5, 3, 2, .8, null, "暂无可比岗位", 0, 70,
                Map.of(), List.of(), "待生成岗位簇 0", "中", List.of(), List.of(), List.of(), List.of(),
                "WAITING_MODEL", "input", List.of(), List.of())));
        long candidateId = repository.findCandidates().get(0).id();

        assertThatThrownBy(() -> repository.submit(candidateId, 1, "研究员"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("岗位定义生成");
    }

    @Test
    void successfulRunSupersedesOlderOpenCandidatesButKeepsTheirAuditDetail() {
        InMemoryEmergingDiscoveryRepository repository = new InMemoryEmergingDiscoveryRepository();
        var firstRun = repository.createRun("run-1", "v1", "hash", "input-1", "bge-m3", 5, "研究员");
        repository.markRunning(firstRun.id());
        repository.complete(firstRun.id(), 1024, "hdbscan", 1, 1, 0, List.of(seed("候选一")));
        long oldCandidateId = repository.findCandidates().get(0).id();

        var secondRun = repository.createRun("run-2", "v1", "hash", "input-2", "bge-m3", 5, "研究员");
        repository.markRunning(secondRun.id());
        repository.complete(secondRun.id(), 1024, "hdbscan", 1, 1, 0, List.of(seed("候选二")));

        assertThat(repository.findCandidates()).extracting(candidate -> candidate.name())
                .containsExactly("候选二");
        assertThat(repository.findCandidate(oldCandidateId)).get()
                .extracting(candidate -> candidate.status()).isEqualTo("SUPERSEDED");
        assertThat(repository.findCandidateDetail(oldCandidateId).candidate().status()).isEqualTo("SUPERSEDED");
    }

    private CandidateSeed seed(String name) {
        return new CandidateSeed(0, "WAITING_GENERATION", 5, 3, 2, .8, null, "暂无可比岗位", 0, 70,
                Map.of(), List.of(), name, "中", List.of(), List.of(), List.of(), List.of(),
                "WAITING_MODEL", "input", List.of(), List.of());
    }
}
