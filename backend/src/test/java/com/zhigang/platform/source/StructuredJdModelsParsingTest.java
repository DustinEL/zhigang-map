package com.zhigang.platform.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredJdModelsParsingTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsProcessingMetadataAndReviewExtensions() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.0.0",
                  "qualitySummary": {"recordCount": 1},
                  "processingPolicy": {
                    "version": "jd-pilot-v1.1",
                    "method": "DICTIONARY_WITH_EVIDENCE",
                    "publishedAtPolicy": "UNKNOWN_REMAINS_NULL",
                    "sourceLicenseStatus": "MANUAL_PUBLIC_REFERENCE",
                    "statisticsEligibility": "REAL_AND_ELIGIBLE_ONLY",
                    "futurePolicyField": "compatible"
                  },
                  "documents": [{
                    "id": 1,
                    "sourceRecordKey": "JD-001",
                    "jobTitle": "Java developer",
                    "facts": {
                      "skills": [{
                        "ontologyStatus": "STANDARD",
                        "canonicalSkill": "Java",
                        "rawValue": "Java",
                        "requirementType": "REQUIRED",
                        "startOffset": 0,
                        "endOffset": 4,
                        "evidenceQuote": "Java",
                        "confidence": 0.99,
                        "offsetVerified": true,
                        "review": {"decision": null}
                      }],
                      "responsibilities": [{
                        "rawValue": "Build services",
                        "normalizedValue": "Build services",
                        "startOffset": 5,
                        "endOffset": 19,
                        "evidenceQuote": "Build services",
                        "confidence": 0.9,
                        "offsetVerified": true,
                        "review": {"decision": null}
                      }],
                      "futureFactsField": true
                    },
                    "review": {"decision": null}
                  }]
                }
                """;

        StructuredJdBatch batch = objectMapper.readValue(json, StructuredJdBatch.class);

        assertThat(batch.processingPolicy().publishedAtPolicy()).isEqualTo("UNKNOWN_REMAINS_NULL");
        assertThat(batch.processingPolicy().sourceLicenseStatus()).isEqualTo("MANUAL_PUBLIC_REFERENCE");
        assertThat(batch.processingPolicy().statisticsEligibility()).isEqualTo("REAL_AND_ELIGIBLE_ONLY");
        assertThat(batch.documents()).hasSize(1);
        assertThat(batch.documents().get(0).facts().skills()).hasSize(1);
        assertThat(batch.documents().get(0).facts().responsibilities()).hasSize(1);
    }
}
