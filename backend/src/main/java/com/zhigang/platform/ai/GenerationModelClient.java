package com.zhigang.platform.ai;

import java.util.Optional;

public interface GenerationModelClient {
    Optional<String> generate(String systemPrompt, String userPrompt);

    Optional<String> generateJson(String systemPrompt, String userPrompt);

    boolean available();

    String model();

    String provider();
}
