package de.opeldus.datacontract.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.util.List;
import java.util.Map;

final class Yaml {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS));

    private Yaml() {}

    static String write(Map<String, Object> document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("YAML could not be written", exception);
        }
    }

    static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        if (value != null) {
            target.put(key, value);
        }
    }
}
