package com.zhigang.platform.career;

import java.util.List;

public record RoleCapabilityCard(
        long roleId,
        String roleCode,
        String roleName,
        String version,
        List<CapabilityGroup> coreCapabilities,
        List<String> transferableSkills,
        List<String> bonusSkills,
        List<String> sourceReferences,
        List<String> reviewerIds,
        String reviewStatus,
        DecisionPolicy decisionPolicy
) {
    private static final DecisionPolicy DEFAULT_DECISION_POLICY =
            new DecisionPolicy(List.<String>of(), List.<String>of(), List.<String>of(), List.<String>of(), List.<String>of(), 0.0, 0.75, 0.50, 0, 0, 1);

    public RoleCapabilityCard(long roleId, String roleCode, String roleName, String version,
                              List<CapabilityGroup> coreCapabilities, List<String> transferableSkills,
                              List<String> bonusSkills, List<String> sourceReferences,
                              List<String> reviewerIds, String reviewStatus) {
        this(roleId, roleCode, roleName, version, coreCapabilities, transferableSkills, bonusSkills,
                sourceReferences, reviewerIds, reviewStatus, null);
    }

    public DecisionPolicy effectiveDecisionPolicy() {
        return decisionPolicy == null ? DEFAULT_DECISION_POLICY : decisionPolicy;
    }

    public record CapabilityGroup(
            String id,
            List<String> alternatives,
            int nearEvidenceLevel,
            int readyEvidenceLevel,
            boolean blocking
    ) {
    }

    public record DecisionPolicy(
            List<String> nearAnchorGroupIds,
            List<String> nearRequiredAnyGroupIds,
            List<String> nearRequiredAllGroupIds,
            List<String> readyRequiredAnyGroupIds,
            List<String> readyRequiredAllGroupIds,
            double nearMinimumProjectRelevance,
            double readyMinimumCoreCoverage,
            double readyMinimumProjectRelevance,
            int readyMaximumBlockingGaps,
            int readyMaximumBlockingReadyShortfall,
            Integer nearMaximumBlockingGaps
    ) {
        public DecisionPolicy {
            nearAnchorGroupIds = nearAnchorGroupIds == null ? List.of() : List.copyOf(nearAnchorGroupIds);
            nearRequiredAnyGroupIds = nearRequiredAnyGroupIds == null ? List.of() : List.copyOf(nearRequiredAnyGroupIds);
            nearRequiredAllGroupIds = nearRequiredAllGroupIds == null ? List.of() : List.copyOf(nearRequiredAllGroupIds);
            readyRequiredAnyGroupIds = readyRequiredAnyGroupIds == null ? List.of() : List.copyOf(readyRequiredAnyGroupIds);
            readyRequiredAllGroupIds = readyRequiredAllGroupIds == null ? List.of() : List.copyOf(readyRequiredAllGroupIds);
        }

        public DecisionPolicy(List<String> nearAnchorGroupIds, double readyMinimumCoreCoverage,
                              double readyMinimumProjectRelevance, int readyMaximumBlockingGaps,
                              int readyMaximumBlockingReadyShortfall) {
            this(nearAnchorGroupIds, List.of(), List.of(), List.of(), List.of(), 0.0, readyMinimumCoreCoverage, readyMinimumProjectRelevance,
                    readyMaximumBlockingGaps, readyMaximumBlockingReadyShortfall, null);
        }

        public DecisionPolicy(List<String> nearAnchorGroupIds, List<String> nearRequiredAnyGroupIds,
                              double readyMinimumCoreCoverage, double readyMinimumProjectRelevance,
                              int readyMaximumBlockingGaps, int readyMaximumBlockingReadyShortfall) {
            this(nearAnchorGroupIds, nearRequiredAnyGroupIds, List.of(), List.of(), List.of(), 0.0, readyMinimumCoreCoverage,
                    readyMinimumProjectRelevance, readyMaximumBlockingGaps, readyMaximumBlockingReadyShortfall, null);
        }

        public DecisionPolicy(List<String> nearAnchorGroupIds, List<String> nearRequiredAnyGroupIds,
                              List<String> nearRequiredAllGroupIds, double readyMinimumCoreCoverage,
                              double readyMinimumProjectRelevance, int readyMaximumBlockingGaps,
                              int readyMaximumBlockingReadyShortfall) {
            this(nearAnchorGroupIds, nearRequiredAnyGroupIds, nearRequiredAllGroupIds, List.of(), List.of(), 0.0,
                    readyMinimumCoreCoverage, readyMinimumProjectRelevance, readyMaximumBlockingGaps,
                    readyMaximumBlockingReadyShortfall, null);
        }

        public DecisionPolicy(List<String> nearAnchorGroupIds, List<String> nearRequiredAnyGroupIds,
                              List<String> nearRequiredAllGroupIds, double nearMinimumProjectRelevance,
                              double readyMinimumCoreCoverage, double readyMinimumProjectRelevance,
                              int readyMaximumBlockingGaps, int readyMaximumBlockingReadyShortfall) {
            this(nearAnchorGroupIds, nearRequiredAnyGroupIds, nearRequiredAllGroupIds, List.of(), List.of(), nearMinimumProjectRelevance,
                    readyMinimumCoreCoverage, readyMinimumProjectRelevance, readyMaximumBlockingGaps,
                    readyMaximumBlockingReadyShortfall, null);
        }

        public DecisionPolicy(List<String> nearAnchorGroupIds, List<String> nearRequiredAnyGroupIds,
                              List<String> nearRequiredAllGroupIds, double nearMinimumProjectRelevance,
                              double readyMinimumCoreCoverage, double readyMinimumProjectRelevance,
                              int readyMaximumBlockingGaps, int readyMaximumBlockingReadyShortfall,
                              int nearMaximumBlockingGaps) {
            this(nearAnchorGroupIds, nearRequiredAnyGroupIds, nearRequiredAllGroupIds, List.of(), List.of(), nearMinimumProjectRelevance,
                    readyMinimumCoreCoverage, readyMinimumProjectRelevance, readyMaximumBlockingGaps,
                    readyMaximumBlockingReadyShortfall, Integer.valueOf(nearMaximumBlockingGaps));
        }

        public int effectiveNearMaximumBlockingGaps() {
            // Keep the legacy constructors' near-match behavior: one blocking
            // gap was historically allowed when no explicit policy was given.
            // Frozen role cards set this field explicitly and are unaffected.
            return nearMaximumBlockingGaps == null ? 1 : Math.max(0, nearMaximumBlockingGaps);
        }
    }
}
