package de.opeldus.datacontract.artifact;

import org.springframework.stereotype.Component;

@Component
public class OdpsSchemaValidator extends BundledSchemaValidator {

    public OdpsSchemaValidator() {
        super("schema/odps-json-schema-v1.1.0.json");
    }
}
