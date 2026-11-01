package de.opeldus.datacontract.product;

import de.opeldus.datacontract.artifact.OdpsProductGenerator;
import de.opeldus.datacontract.artifact.OdpsSchemaValidator;
import de.opeldus.datacontract.mapping.ContractVersion;
import de.opeldus.datacontract.mapping.ContractVersionRepository;
import de.opeldus.datacontract.validation.SemanticVersion;
import de.opeldus.datacontract.validation.ValidationIssue;
import de.opeldus.datacontract.validation.ValidationIssue.Severity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProductValidator {

    private final DataProductRepository products;
    private final ContractVersionRepository contractVersions;
    private final OdpsProductGenerator generator;
    private final OdpsSchemaValidator schema;

    public ProductValidator(DataProductRepository products, ContractVersionRepository contractVersions,
            OdpsProductGenerator generator, OdpsSchemaValidator schema) {
        this.products = products;
        this.contractVersions = contractVersions;
        this.generator = generator;
        this.schema = schema;
    }

    public List<ValidationIssue> validate(DataProduct product) {
        var issues = new ArrayList<ValidationIssue>();
        validateHeader(product, issues);

        var outputs = product.ports(PortDirection.OUTPUT);
        if (outputs.isEmpty()) {
            issues.add(error("NO_OUTPUT_PORT", "Ein Datenprodukt braucht mindestens einen Output-Port.", null, null));
        }
        if (product.ports(PortDirection.INPUT).isEmpty()) {
            issues.add(warning("NO_INPUT_PORT", "Das Datenprodukt hat keinen Input-Port.", null, null));
        }
        var names = new HashSet<String>();
        var allProducts = products.findAll();
        for (var port : product.getPorts()) {
            validatePort(product, port, names, allProducts, issues);
        }
        if (findsCycle(product, allProducts)) {
            issues.add(error("DEPENDENCY_CYCLE", "Die Ports erzeugen einen Abhängigkeitskreis zwischen Datenprodukten.",
                    null, null));
        }
        if (issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR)) {
            for (var message : schema.validate(generator.toDocument(product))) {
                issues.add(error("ODPS_SCHEMA", "Das erzeugte Datenprodukt verletzt ODPS: " + message, null, null));
            }
        }
        return issues;
    }

    private void validateHeader(DataProduct product, List<ValidationIssue> issues) {
        if (product.getDomain() == null) {
            issues.add(error("DOMAIN_MISSING", "Die Domäne fehlt.", null, "domain"));
        }
        if (product.getOwnerTeam() == null) {
            issues.add(error("OWNER_MISSING", "Das verantwortliche Team fehlt.", null, "ownerTeam"));
        }
        var version = product.getProductVersion();
        if (!SemanticVersion.isValid(version)) {
            issues.add(error("VERSION_INVALID", "Die Version muss das Format 1.2.3 haben.", null, "productVersion"));
        } else if (product.getStatus().isEditable() && product.getPublishedVersion() != null
                && SemanticVersion.compare(version, product.getPublishedVersion()) <= 0) {
            issues.add(error("VERSION_NOT_INCREASED",
                    "Die Version muss höher sein als die veröffentlichte Version " + product.getPublishedVersion() + ".",
                    null, "productVersion"));
        }
    }

    private void validatePort(DataProduct product, DataProductPort port, Set<String> names,
            List<DataProduct> allProducts, List<ValidationIssue> issues) {
        var id = port.getId();
        var label = port.getName() != null ? "Port '" + port.getName() + "'" : "Ein Port";
        if (port.getName() == null) {
            issues.add(error("PORT_NAME_MISSING", "Der Port braucht einen Namen.", id, "name"));
        } else if (!names.add(port.getDirection() + ":" + port.getName().toLowerCase(Locale.ROOT))) {
            issues.add(error("PORT_NAME_DUPLICATE", label + " kommt mehrfach vor.", id, "name"));
        }
        var contract = port.getContract();
        if (contract == null) {
            if (port.getDirection() == PortDirection.OUTPUT) {
                issues.add(error("OUTPUT_WITHOUT_CONTRACT", label + " braucht einen Contract.", id, "contract"));
            } else {
                issues.add(warning("INPUT_WITHOUT_CONTRACT",
                        label + " hat keinen Contract. Änderungen an der Quelle bleiben so unbemerkt.", id, "contract"));
            }
            return;
        }
        var published = contractVersions.findByMappingId(contract.getId()).stream()
                .map(ContractVersion::getVersion)
                .toList();
        if (published.isEmpty()) {
            issues.add(error("CONTRACT_NOT_PUBLISHED",
                    "Der Contract '" + contract.getContractName() + "' ist noch nicht veröffentlicht.", id, "contract"));
            return;
        }
        var pinned = port.getContractVersion();
        if (pinned == null) {
            issues.add(error("CONTRACT_VERSION_MISSING", label + " braucht eine Contract-Version.", id, "contractVersion"));
        } else if (!published.contains(pinned)) {
            issues.add(error("CONTRACT_VERSION_UNKNOWN",
                    "Version " + pinned + " von '" + contract.getContractName() + "' ist nicht veröffentlicht.",
                    id, "contractVersion"));
        } else {
            var latest = published.stream().max(SemanticVersion.ORDER).orElse(pinned);
            if (!latest.equals(pinned)) {
                var breaking = !SemanticVersion.sameMajor(latest, pinned);
                issues.add(warning(breaking ? "CONTRACT_NEW_MAJOR" : "CONTRACT_OUTDATED",
                        label + " nutzt " + contract.getContractName() + " " + pinned + ", aktuell ist " + latest
                                + (breaking ? ". Die neue Hauptversion kann inkompatibel sein." : "."),
                        id, "contractVersion"));
            }
        }
        if (port.getDirection() == PortDirection.OUTPUT) {
            for (var other : allProducts) {
                if (!other.getId().equals(product.getId()) && produces(other, contract.getId())) {
                    issues.add(error("CONTRACT_HAS_OTHER_PRODUCER",
                            "Den Contract '" + contract.getContractName() + "' liefert bereits '"
                                    + other.getProductName() + "'.",
                            id, "contract"));
                }
            }
        }
    }

    private static boolean produces(DataProduct product, UUID contractId) {
        return product.ports(PortDirection.OUTPUT).stream()
                .anyMatch(port -> port.getContract() != null && port.getContract().getId().equals(contractId));
    }

    /** Walks downstream: a product depends on another when one of its inputs is the other's output. */
    private boolean findsCycle(DataProduct start, List<DataProduct> allProducts) {
        var visited = new HashSet<UUID>();
        var queue = new ArrayDeque<DataProduct>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var candidate : allProducts) {
                var effective = candidate.getId().equals(start.getId()) ? start : candidate;
                if (!feeds(current, effective)) {
                    continue;
                }
                if (effective.getId().equals(start.getId())) {
                    return true;
                }
                if (visited.add(effective.getId())) {
                    queue.add(effective);
                }
            }
        }
        return false;
    }

    private static boolean feeds(DataProduct producer, DataProduct consumer) {
        return consumer.ports(PortDirection.INPUT).stream()
                .anyMatch(port -> port.getContract() != null && produces(producer, port.getContract().getId()));
    }

    private static ValidationIssue error(String code, String message, UUID portId, String field) {
        return new ValidationIssue(Severity.ERROR, code, message, portId, field);
    }

    private static ValidationIssue warning(String code, String message, UUID portId, String field) {
        return new ValidationIssue(Severity.WARNING, code, message, portId, field);
    }
}
