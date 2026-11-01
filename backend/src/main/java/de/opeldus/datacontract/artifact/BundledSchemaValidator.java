package de.opeldus.datacontract.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** Validates against an official JSON schema shipped in resources/schema. */
abstract class BundledSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchema schema;

    BundledSchemaValidator(String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909).getSchema(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public List<String> validate(Map<String, Object> document) {
        return schema.validate(MAPPER.valueToTree(document)).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }
}
