package com.zhigang.platform.governance;

import com.zhigang.platform.api.Requests.ChangeProposalRequest;
import com.zhigang.platform.api.Requests.GraphReleaseRequest;
import com.zhigang.platform.api.Requests.ReviewRequest;
import com.zhigang.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceServiceTest {
    private final GovernanceService service = new GovernanceService("v1.0.0");

    @Test
    void proposalMustBeReviewedByAnotherPersonBeforePublishing() {
        var proposal = service.create(new ChangeProposalRequest(
                "Java 岗位能力更新", "ROLE_EVOLUTION", "两个窗口证据支持", List.of("增强：Docker")
        ));

        assertThatThrownBy(() -> service.review(proposal.id(), new ReviewRequest("APPROVE", proposal.creator(), "自审")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能独立审核");
        assertThatThrownBy(() -> service.publish(new GraphReleaseRequest(
                "2026.08-draft", "snapshot-1", "管理员", List.of(proposal.id())
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("审核通过");

        var reviewed = service.review(proposal.id(), new ReviewRequest("APPROVE", "审核员-甲", "证据有效"));
        var release = service.publish(new GraphReleaseRequest(
                "2026.08", "snapshot-1", "管理员-甲", List.of(reviewed.id())
        ));
        var diff = service.diff(release.id());

        assertThat(reviewed.status()).isEqualTo("APPROVED");
        assertThat(release.changes()).containsExactly("增强：Docker");
        assertThat(diff.changed()).containsExactly("增强：Docker");
        assertThat(service.releases()).filteredOn(value -> "PUBLISHED".equals(value.status())).containsExactly(release);
    }

    @Test
    void rejectionAndMissingResourcesReturnClearErrors() {
        var proposal = service.create(new ChangeProposalRequest(
                "无效候选", "EMERGING_ROLE", "反对证据充分", List.of("删除：候选岗位")
        ));
        var rejected = service.review(proposal.id(), new ReviewRequest("REJECT", "审核员-乙", "证据不足"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(service.proposals()).contains(rejected);
        assertThat(service.releases()).hasSizeGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> service.review(999L, new ReviewRequest("APPROVE", "审核员", "")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.diff(999L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rollbackRestoresExactlyOnePublishedRelease() {
        var restored = service.rollback(9L, "管理员-乙");

        assertThat(restored.version()).isEqualTo("2025.12");
        assertThat(restored.status()).isEqualTo("PUBLISHED");
        assertThat(service.releases()).filteredOn(value -> "PUBLISHED".equals(value.status())).containsExactly(restored);
        assertThatThrownBy(() -> service.rollback(9L, "管理员-乙"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前生效");
        assertThatThrownBy(() -> service.publish(new GraphReleaseRequest(
                "2025.12", "snapshot-1", "管理员-甲", List.of()
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("版本号已存在");
    }
}
