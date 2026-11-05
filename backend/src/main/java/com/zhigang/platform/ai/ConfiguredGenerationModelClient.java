package com.zhigang.platform.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Primary
@Component
public class ConfiguredGenerationModelClient implements GenerationModelClient {
    private final String provider;
    private final GenerationModelClient selected;

    public ConfiguredGenerationModelClient(
            @Value("${zhigang.ai.provider:local}") String provider,
            @Qualifier("localModelAdapter") GenerationModelClient local,
            @Qualifier("sparkModelAdapter") GenerationModelClient spark) {
        this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.selected = switch (this.provider) {
            case "local" -> local;
            case "spark" -> spark;
            case "rules" -> null;
            default -> throw new IllegalArgumentException(
                    "AI_PROVIDER 必须是 local、spark 或 rules，当前值：" + this.provider);
        };
    }

    @Override
    public Optional<String> generate(String systemPrompt, String userPrompt) {
        return selected == null ? Optional.empty() : selected.generate(systemPrompt, userPrompt);
    }

    @Override
    public Optional<String> generateJson(String systemPrompt, String userPrompt) {
        return selected == null ? Optional.empty() : selected.generateJson(systemPrompt, userPrompt);
    }

    @Override
    public boolean available() {
        return selected != null && selected.available();
    }

    @Override
    public String model() {
        return selected == null ? "rules" : selected.model();
    }

    @Override
    public String provider() {
        return provider;
    }
}
