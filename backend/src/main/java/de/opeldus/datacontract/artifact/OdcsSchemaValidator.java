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
import org.springframework.stereotype.Component;

/** Validates against the official ODCS JSON schema shipped in resources/schema. */
@Component
public class OdcsSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchema schema;

    public OdcsSchemaValidator() {
        try (var input = new ClassPathResource("schema/odcs-json-schema-v3.2.0.json").getInputStream()) {
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
