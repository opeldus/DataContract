package de.opeldus.datacontract.artifact;

import static de.opeldus.datacontract.artifact.Yaml.putIfPresent;

import de.opeldus.datacontract.product.DataProduct;
import de.opeldus.datacontract.product.DataProductPort;
import de.opeldus.datacontract.product.PortDirection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OdpsProductGenerator {

    static final String API_VERSION = "v1.1.0";

    public GeneratedArtifact generate(DataProduct product) {
        return new GeneratedArtifact("products/" + product.getProductName() + "/odps.yaml",
                Yaml.write(toDocument(product)));
    }

    /** The status is always "active": a product only reaches Git after approval. */
    public Map<String, Object> toDocument(DataProduct product) {
        var document = new LinkedHashMap<String, Object>();
        document.put("apiVersion", API_VERSION);
        document.put("kind", "DataProduct");
        document.put("id", "urn:dataproduct:" + product.getKey());
        document.put("name", product.getProductName());
        document.put("version", product.getProductVersion());
        document.put("status", "active");
        putIfPresent(document, "domain", product.getDomain());
        putIfPresent(document, "type", product.getProductType());
        if (product.getPurpose() != null) {
            document.put("description", Map.of("purpose", product.getPurpose()));
        }
        if (product.getOwnerTeam() != null) {
            document.put("team", Map.of("name", product.getOwnerTeam()));
        }
        var inputPorts = product.ports(PortDirection.INPUT);
        putIfPresent(document, "inputPorts", inputPorts.stream().map(this::port).toList());
        var inputContracts = inputContracts(inputPorts);
        putIfPresent(document, "outputPorts", product.ports(PortDirection.OUTPUT).stream()
                .map(port -> outputPort(port, inputContracts))
                .toList());
        return document;
    }

    /** ODPS links inputs per output port. Every output counts as dependent on every input, which errs towards more impact. */
    private List<Map<String, Object>> inputContracts(List<DataProductPort> inputPorts) {
        var contracts = new ArrayList<Map<String, Object>>();
        for (var port : inputPorts) {
            if (port.getContractKey() != null && port.getContractVersion() != null) {
                var contract = new LinkedHashMap<String, Object>();
                contract.put("id", OdcsContractGenerator.contractId(port.getContractKey()));
                contract.put("version", port.getContractVersion());
                contracts.add(contract);
            }
        }
        return contracts;
    }

    private Map<String, Object> outputPort(DataProductPort port, List<Map<String, Object>> inputContracts) {
        var document = port(port);
        putIfPresent(document, "inputContracts", inputContracts);
        return document;
    }

    private Map<String, Object> port(DataProductPort port) {
        var document = new LinkedHashMap<String, Object>();
        document.put("id", port.getLineageId().toString());
        document.put("name", port.getName() != null ? port.getName() : "");
        putIfPresent(document, "version", port.getContractVersion());
        if (port.getContractKey() != null) {
            document.put("contractId", OdcsContractGenerator.contractId(port.getContractKey()));
        }
        return document;
    }
}
