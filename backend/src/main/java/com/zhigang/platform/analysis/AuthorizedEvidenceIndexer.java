package com.zhigang.platform.analysis;

import com.zhigang.platform.analysis.EvidenceVectorStore.EvidenceFragment;
import com.zhigang.platform.source.SourceImportRepository.ImportedDocument;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AuthorizedEvidenceIndexer {
    private static final Set<String> ALLOWED_LICENSES = Set.of("AUTHORIZED", "OPEN");
    private static final Pattern CHUNK_PATTERN = Pattern.compile("[^\\n。；;]{1,700}(?:[。；;]|\\n|$)");
    private final EvidenceVectorStore vectorStore;

    public AuthorizedEvidenceIndexer(EvidenceVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int index(String sourceName, String licenseStatus, List<ImportedDocument> documents) {
        String normalizedLicense = clean(licenseStatus).toUpperCase(Locale.ROOT);
        if (!ALLOWED_LICENSES.contains(normalizedLicense) || documents == null || documents.isEmpty()) return 0;
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (ImportedDocument document : documents) {
            long documentId = documentId(document.contentHash());
            Matcher matcher = CHUNK_PATTERN.matcher(document.text());
            while (matcher.find()) {
                String matched = matcher.group();
                int leading = leadingWhitespace(matched);
                int trailing = trailingWhitespace(matched);
                String quote = matched.substring(leading, matched.length() - trailing);
                if (quote.length() < 8) continue;
                int start = matcher.start() + leading;
                fragments.add(new EvidenceFragment(documentId, sourceName, "", normalizedLicense, quote,
                        start, start + quote.length(), document.modifiedAt()));
            }
        }
        vectorStore.upsert(fragments);
        return fragments.size();
    }

    public List<EvidenceVectorStore.EvidenceMatch> search(String query, int limit) {
        return vectorStore.search(query, limit);
    }

    public String mode() {
        return vectorStore.mode();
    }

    private long documentId(String contentHash) {
        if (contentHash != null && contentHash.matches("[0-9a-fA-F]{16,}")) {
            return ByteBuffer.wrap(java.util.HexFormat.of().parseHex(contentHash.substring(0, 16))).getLong() & Long.MAX_VALUE;
        }
        return Math.abs((long) clean(contentHash).hashCode()) + 1;
    }

    private int leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private int trailingWhitespace(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(value.length() - count - 1))) count++;
        return count;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
