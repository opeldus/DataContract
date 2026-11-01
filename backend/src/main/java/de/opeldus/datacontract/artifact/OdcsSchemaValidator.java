package de.opeldus.datacontract.artifact;

import org.springframework.stereotype.Component;

@Component
public class OdcsSchemaValidator extends BundledSchemaValidator {

    public OdcsSchemaValidator() {
        super("schema/odcs-json-schema-v3.2.0.json");
    }
}
