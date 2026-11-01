package de.opeldus.datacontract.product;

import de.opeldus.datacontract.mapping.ContractVersion;
import de.opeldus.datacontract.mapping.ContractVersionRepository;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.product.ProductDtos.ContractConsumer;
import de.opeldus.datacontract.product.ProductDtos.ContractOption;
import de.opeldus.datacontract.product.ProductDtos.Graph;
import de.opeldus.datacontract.product.ProductDtos.GraphEdge;
import de.opeldus.datacontract.product.ProductDtos.GraphNode;
import de.opeldus.datacontract.validation.SemanticVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DependencyController {

    private final MappingRepository mappings;
    private final ContractVersionRepository contractVersions;
    private final DataProductRepository products;

    public DependencyController(MappingRepository mappings, ContractVersionRepository contractVersions,
            DataProductRepository products) {
        this.mappings = mappings;
        this.contractVersions = contractVersions;
        this.products = products;
    }

    /** All contracts, newest version first, so a port can pick one; unpublished ones stay visible and fail validation. */
    @GetMapping("/contracts")
    @Transactional(readOnly = true)
    public List<ContractOption> contracts() {
        return mappings.findAll().stream()
                .sorted(Comparator.comparing(mapping -> mapping.getContractName()))
                .map(mapping -> new ContractOption(mapping.getId(), mapping.getContractName(), mapping.getDomain(),
                        contractVersions.findByMappingId(mapping.getId()).stream()
                                .map(ContractVersion::getVersion)
                                .sorted(SemanticVersion.ORDER.reversed())
                                .toList()))
                .toList();
    }

    @GetMapping("/mappings/{id}/consumers")
    @Transactional(readOnly = true)
    public List<ContractConsumer> consumers(@PathVariable UUID id) {
        var consumers = new ArrayList<ContractConsumer>();
        for (var product : products.findByContract(id)) {
            for (var port : product.getPorts()) {
                if (port.getContract() != null && port.getContract().getId().equals(id)) {
                    consumers.add(new ContractConsumer(product.getId(), product.getProductName(), product.getName(),
                            product.getStatus(), port.getDirection(), port.getName(), port.getContractVersion()));
                }
            }
        }
        return consumers;
    }

    @GetMapping("/graph")
    @Transactional(readOnly = true)
    public Graph graph() {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        for (var mapping : mappings.findAll()) {
            nodes.add(new GraphNode(contractNode(mapping.getId()), "CONTRACT", mapping.getContractName(),
                    mapping.getId(), mapping.getStatus(), mapping.getContractVersion()));
        }
        for (var product : products.findAll()) {
            var productNode = "product:" + product.getId();
            nodes.add(new GraphNode(productNode, "PRODUCT", product.getProductName(), product.getId(),
                    product.getStatus(), product.getProductVersion()));
            for (var port : product.getPorts()) {
                if (port.getContract() == null) {
                    continue;
                }
                var contractNode = contractNode(port.getContract().getId());
                edges.add(port.getDirection() == PortDirection.INPUT
                        ? new GraphEdge(contractNode, productNode, port.getContractVersion())
                        : new GraphEdge(productNode, contractNode, port.getContractVersion()));
            }
        }
        return new Graph(nodes, edges);
    }

    private static String contractNode(UUID mappingId) {
        return "contract:" + mappingId;
    }
}
