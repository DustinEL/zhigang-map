package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.ai.LocalModelAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "local.llm.contract", matches = "true")
class LocalModelEmergingContractTest {
    @Test
    void localModelHonorsEmergingRoleEvidenceContract() {
        ObjectMapper objectMapper = new ObjectMapper();
        String endpoint = System.getProperty("local.llm.endpoint", "http://127.0.0.1:11500/v1/chat/completions");
        LocalModelAdapter modelClient = new LocalModelAdapter(objectMapper, endpoint, "", "qwen-local",
                3000, 120000, 2048);
        VerifiedWebEvidenceSearch webEvidenceSearch = mock(VerifiedWebEvidenceSearch.class);
        when(webEvidenceSearch.search("大模型推理加速工程师")).thenReturn(List.of(
                new VerifiedWebEvidenceSearch.VerifiedEvidence("E1", "PyTorch 推理性能调优", "PyTorch",
                        "https://pytorch.org/tutorials/recipes/recipes/tuning_guide.html", "",
                        "推理性能调优涉及算子融合、内存布局、量化与运行时配置。"),
                new VerifiedWebEvidenceSearch.VerifiedEvidence("E2", "TensorRT-LLM", "NVIDIA",
                        "https://developer.nvidia.com/tensorrt-llm", "",
                        "TensorRT-LLM 面向大语言模型推理，涵盖量化、并行执行与延迟吞吐优化。"),
                new VerifiedWebEvidenceSearch.VerifiedEvidence("E3", "Text Generation Inference", "Hugging Face",
                        "https://huggingface.co/docs/text-generation-inference/index", "",
                        "生产级文本生成服务需要连续批处理、张量并行、监控与服务部署能力。")));

        var result = new EmergingQueryGenerator(modelClient, objectMapper, webEvidenceSearch)
                .generate("大模型推理加速工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.structured()).isTrue();
        assertThat(result.fieldEvidenceComplete()).isTrue();
        assertThat(result.evidence()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.fieldEvidence()).isNotEmpty();
        assertThat(result.model()).isEqualTo("qwen-local");
    }
}
