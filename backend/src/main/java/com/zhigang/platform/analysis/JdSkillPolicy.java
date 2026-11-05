package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class JdSkillPolicy {
    private static final String RESOURCE = "analysis/jd-skill-policy-v2.json";

    private final String version;
    private final List<Entry> entries;
    private final Map<String, String> canonicalByAlias;
    private final Set<String> contextual;
    private final Set<String> supplemental;
    private final Set<String> blocked;

    public JdSkillPolicy() {
        this(loadDocument());
    }

    JdSkillPolicy(Document document) {
        this.version = document.version();
        this.blocked = lowerCaseSet(document.blocked());
        Map<String, Entry> byAlias = new LinkedHashMap<>();
        document.aliases().forEach((canonical, aliases) -> {
            addEntry(byAlias, canonical, canonical, true, this.blocked);
            aliases.forEach(alias -> addEntry(byAlias, alias, canonical, false, this.blocked));
        });
        for (String literal : document.literals().split("\\|")) {
            addLiteral(byAlias, literal, this.blocked);
        }
        List<Entry> sorted = new ArrayList<>(byAlias.values());
        sorted.sort(Comparator.comparingInt((Entry entry) -> entry.alias().length()).reversed());
        this.entries = List.copyOf(sorted);
        Map<String, String> lookup = new LinkedHashMap<>();
        entries.forEach(entry -> lookup.putIfAbsent(entry.alias(), entry.canonical()));
        this.canonicalByAlias = Map.copyOf(lookup);
        this.contextual = lowerCaseSet(document.contextual());
        this.supplemental = lowerCaseSet(document.supplemental());
    }

    public String version() {
        return version;
    }

    List<Entry> entries() {
        return entries;
    }

    boolean requiresContext(String canonical) {
        return contextual.contains(canonical.toLowerCase(Locale.ROOT));
    }

    boolean isSupplemental(String canonical) {
        return supplemental.contains(canonical.toLowerCase(Locale.ROOT));
    }

    boolean isBlocked(String canonical) {
        return blocked.contains(canonical.toLowerCase(Locale.ROOT));
    }

    String canonicalize(String value) {
        if (value == null || value.isBlank()) return null;
        return canonicalByAlias.get(value.trim().toLowerCase(Locale.ROOT));
    }

    private static Document loadDocument() {
        try (InputStream input = JdSkillPolicy.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing JD skill policy: " + RESOURCE);
            return new ObjectMapper().readValue(input, Document.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load JD skill policy: " + RESOURCE, exception);
        }
    }

    private static void addEntry(Map<String, Entry> entries, String alias, String canonical,
                                 boolean exactCanonical, Set<String> blocked) {
        String normalizedAlias = alias.trim().toLowerCase(Locale.ROOT);
        if (normalizedAlias.isBlank() || blocked.contains(canonical.toLowerCase(Locale.ROOT))) return;
        Entry candidate = new Entry(normalizedAlias, canonical.trim(), exactCanonical);
        Entry existing = entries.putIfAbsent(normalizedAlias, candidate);
        if (existing != null && !existing.canonical().equalsIgnoreCase(candidate.canonical())) {
            throw new IllegalArgumentException("JD skill alias conflict: " + alias + " -> "
                    + existing.canonical() + " / " + candidate.canonical());
        }
    }

    private static void addLiteral(Map<String, Entry> entries, String literal, Set<String> blocked) {
        String normalized = literal.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || blocked.contains(normalized) || entries.containsKey(normalized)) return;
        entries.put(normalized, new Entry(normalized, literal.trim(), true));
    }

    private static Set<String> lowerCaseSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT)));
        return Set.copyOf(result);
    }

    record Entry(String alias, String canonical, boolean exactCanonical) {
    }

    record Document(String version, String literals, Map<String, List<String>> aliases,
                    List<String> contextual, List<String> supplemental, List<String> blocked) {
        Document {
            literals = literals == null ? "" : literals;
            aliases = aliases == null ? Map.of() : aliases;
            contextual = contextual == null ? List.of() : contextual;
            supplemental = supplemental == null ? List.of() : supplemental;
            blocked = blocked == null ? List.of() : blocked;
        }
    }
}
