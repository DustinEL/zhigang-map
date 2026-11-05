package com.zhigang.platform.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.evolution.RoleEvolutionModels.Policy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RoleEvolutionPolicyLoader {
    private final Policy policy;

    public RoleEvolutionPolicyLoader(ObjectMapper objectMapper) {
        try {
            policy = objectMapper.readValue(new ClassPathResource("role-evolution-policy-v1.0.json").getInputStream(), Policy.class);
        } catch (IOException exception) {
            throw new IllegalStateException("岗位演化策略文件无法加载", exception);
        }
    }

    public Policy policy() {
        return policy;
    }
}
