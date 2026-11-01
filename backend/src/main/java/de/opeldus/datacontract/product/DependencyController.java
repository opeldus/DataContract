package de.opeldus.datacontract.product;

import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.product.ProductDtos.ContractConsumer;
import de.opeldus.datacontract.product.ProductDtos.ContractOption;
import de.opeldus.datacontract.product.ProductDtos.Graph;
import de.opeldus.datacontract.product.ProductDtos.GraphEdge;
import de.opeldus.datacontract.product.ProductDtos.GraphNode;
import de.opeldus.datacontract.release.ReleaseScopes;
import de.opeldus.datacontract.validation.SemanticVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DependencyController {

    private final MappingRepository mappings;
    private final ReleaseScopes scopes;

    public DependencyController(MappingRepository mappings, ReleaseScopes scopes) {
        this.mappings = mappings;
        this.scopes = scopes;
    }

    /** All contracts a release sees, newest version first; unpublished ones stay visible and fail validation. */
    @GetMapping("/contracts")
    @Transactional(readOnly = true)
    public List<ContractOption> contracts(@RequestParam(required = false) UUID release) {
        return scopes.effectiveMappings(release).values().stream()
                .sorted(Comparator.comparing(mapping -> mapping.getContractName()))
                .map(mapping -> {
                    var published = scopes.visibleVersions(mapping.getKey(), release).stream()
                            .filter(SemanticVersion::isValid)
                            .sorted(SemanticVersion.ORDER.reversed())
                            .toList();
                    var planned = release != null && release.equals(mapping.getReleaseId())
                            && !published.contains(mapping.getContractVersion()) ? mapping.getContractVersion() : null;
                    return new ContractOption(mapping.getKey(), mapping.getContractName(), mapping.getDomain(), published,
                            planned);
                })
                .toList();
    }

    @GetMapping("/mappings/{id}/consumers")
    @Transactional(readOnly = true)
    public List<ContractConsumer> consumers(@PathVariable UUID id) {
        var mapping = mappings.findById(id).orElseThrow(() -> notFound("Mapping nicht gefunden."));
        var consumers = new ArrayList<ContractConsumer>();
        for (var product : scopes.effectiveProducts(mapping.getReleaseId()).values()) {
            for (var port : product.getPorts()) {
                if (Objects.equals(port.getContractKey(), mapping.getKey())) {
                    consumers.add(new ContractConsumer(product.getId(), product.getProductName(), product.getName(),
                            product.getStatus(), port.getDirection(), port.getName(), port.getContractVersion()));
                }
            }
        }
        return consumers;
    }

    @GetMapping("/graph")
    @Transactional(readOnly = true)
    public Graph graph(@RequestParam(required = false) UUID release) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        for (var mapping : scopes.effectiveMappings(release).values()) {
            nodes.add(new GraphNode(contractNode(mapping.getKey()), "CONTRACT", mapping.getContractName(),
                    mapping.getId(), mapping.getStatus(), mapping.getContractVersion()));
        }
        for (var product : scopes.effectiveProducts(release).values()) {
            var productNode = "product:" + product.getKey();
            nodes.add(new GraphNode(productNode, "PRODUCT", product.getProductName(), product.getId(),
                    product.getStatus(), product.getProductVersion()));
            for (var port : product.getPorts()) {
                if (port.getContractKey() == null) {
                    continue;
                }
                var contractNode = contractNode(port.getContractKey());
                edges.add(port.getDirection() == PortDirection.INPUT
                        ? new GraphEdge(contractNode, productNode, port.getContractVersion())
                        : new GraphEdge(productNode, contractNode, port.getContractVersion()));
            }
        }
        return new Graph(nodes, edges);
    }

    private static String contractNode(UUID contractKey) {
        return "contract:" + contractKey;
    }
}
