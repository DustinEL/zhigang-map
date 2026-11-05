package com.zhigang.platform.ai;

import java.util.Optional;

public interface ModelGateway {
    Optional<String> generateStructured(String task, String sourceText, String jsonSchema);

    String explain(String question, String evidenceText);

    String mode();
}
