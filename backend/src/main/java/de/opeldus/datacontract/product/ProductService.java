package de.opeldus.datacontract.product;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;
import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.artifact.ArtifactService;
import de.opeldus.datacontract.artifact.GeneratedArtifact;
import de.opeldus.datacontract.artifact.OdpsProductGenerator;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner;
import de.opeldus.datacontract.mapping.MappingDtos.EventDto;
import de.opeldus.datacontract.mapping.MappingDtos.PreviewFile;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.product.ProductDtos.CreateProductRequest;
import de.opeldus.datacontract.product.ProductDtos.PortDto;
import de.opeldus.datacontract.product.ProductDtos.ProductDetail;
import de.opeldus.datacontract.product.ProductDtos.ProductSummary;
import de.opeldus.datacontract.product.ProductDtos.UpdateProductRequest;
import de.opeldus.datacontract.review.ReviewEvent;
import de.opeldus.datacontract.review.ReviewEventRepository;
import de.opeldus.datacontract.review.ReviewEventType;
import de.opeldus.datacontract.review.ReviewWorkflow;
import de.opeldus.datacontract.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final DataProductRepository products;
    private final MappingRepository mappings;
    private final ReviewEventRepository events;
    private final ProductValidator validator;
    private final OdpsProductGenerator generator;
    private final ArtifactService artifacts;
    private final ReviewWorkflow workflow;
    private final IntegrationTaskPlanner integrationTasks;

    public ProductService(DataProductRepository products, MappingRepository mappings, ReviewEventRepository events,
            ProductValidator validator, OdpsProductGenerator generator, ArtifactService artifacts,
            ReviewWorkflow workflow, IntegrationTaskPlanner integrationTasks) {
        this.products = products;
        this.mappings = mappings;
        this.events = events;
        this.validator = validator;
        this.generator = generator;
        this.artifacts = artifacts;
        this.workflow = workflow;
        this.integrationTasks = integrationTasks;
    }

    @Transactional(readOnly = true)
    public List<ProductSummary> list() {
        return products.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(ProductSummary::from)
                .toList();
    }

    @Transactional
    public ProductDetail create(CreateProductRequest request, String actor) {
        if (products.existsByProductName(request.productName())) {
            throw conflict("Der Produktname '" + request.productName() + "' ist bereits vergeben.");
        }
        var product = products.saveAndFlush(new DataProduct(request.name(), request.productName(), actor));
        events.save(new ReviewEvent(product.getId(), ReviewEventType.CREATED, actor));
        return detail(product);
    }

    @Transactional(readOnly = true)
    public ProductDetail get(UUID id) {
        return detail(load(id));
    }

    @Transactional
    public ProductDetail update(UUID id, UpdateProductRequest request, String actor) {
        var product = load(id);
        if (!product.getStatus().isEditable()) {
            throw conflict("Im Status " + product.getStatus() + " kann das Datenprodukt nicht bearbeitet werden.");
        }
        if (product.getVersion() != request.version()) {
            throw conflict("Das Datenprodukt wurde zwischenzeitlich von jemand anderem geändert. Bitte neu laden.");
        }
        product.setName(request.name());
        product.setProductVersion(request.productVersion().trim());
        product.setDomain(blankToNull(request.domain()));
        product.setProductType(blankToNull(request.productType()));
        product.setPurpose(blankToNull(request.purpose()));
        product.setOwnerTeam(blankToNull(request.ownerTeam()));
        replacePorts(product, request.ports() == null ? List.of() : request.ports());
        product.touch();
        events.save(new ReviewEvent(id, ReviewEventType.EDITED, actor));
        return saved(product);
    }

    @Transactional(readOnly = true)
    public List<PreviewFile> preview(UUID id) {
        var artifact = generator.generate(load(id));
        return List.of(new PreviewFile(artifact.path(), artifact.content()));
    }

    @Transactional
    public ProductDetail submit(UUID id, String actor) {
        var product = load(id);
        var errors = validator.validate(product).stream()
                .filter(issue -> issue.severity() == ValidationIssue.Severity.ERROR)
                .count();
        workflow.submit(product, errors, hash(product), actor);
        return saved(product);
    }

    @Transactional
    public ProductDetail approve(UUID id, String expectedHash, String actor) {
        var product = load(id);
        workflow.approve(product, expectedHash, hash(product), actor);
        return saved(product);
    }

    @Transactional
    public ProductDetail requestChanges(UUID id, String comment, String actor) {
        var product = load(id);
        workflow.requestChanges(product, comment, actor);
        return saved(product);
    }

    @Transactional
    public ProductDetail comment(UUID id, String comment, String actor) {
        var product = load(id);
        workflow.comment(product, comment, null, actor);
        return detail(product);
    }

    @Transactional
    public ProductDetail reopen(UUID id, String actor) {
        var product = load(id);
        workflow.reopen(product, actor);
        return saved(product);
    }

    @Transactional
    public ProductDetail publish(UUID id, String actor) {
        var product = load(id);
        var artifact = generator.generate(product);
        var title = product.getProductName() + " " + product.getProductVersion();
        workflow.publish(product, List.<GeneratedArtifact>of(artifact), artifacts.contentHash(List.of(artifact)), title,
                actor);
        integrationTasks.planProduct(product);
        return saved(product);
    }

    private ProductDetail saved(DataProduct product) {
        return detail(products.saveAndFlush(product));
    }

    private ProductDetail detail(DataProduct product) {
        var history = events.findBySubjectIdOrderByCreatedAtAsc(product.getId()).stream()
                .map(EventDto::from)
                .toList();
        return new ProductDetail(product.getId(), product.getName(), product.getProductName(),
                product.getProductVersion(), product.getDomain(), product.getProductType(), product.getPurpose(),
                product.getOwnerTeam(), product.getStatus(), product.getPublishedVersion(),
                product.getPublishedCommit(), product.getCreatedBy(), product.getUpdatedAt(), product.getVersion(),
                product.getPorts().stream().map(PortDto::from).toList(), validator.validate(product), history,
                hash(product));
    }

    private String hash(DataProduct product) {
        return artifacts.contentHash(List.of(generator.generate(product)));
    }

    private void replacePorts(DataProduct product, List<PortDto> rows) {
        var existing = product.getPorts().stream()
                .collect(Collectors.toMap(DataProductPort::getId, Function.identity()));
        var updated = new ArrayList<DataProductPort>(rows.size());
        for (var row : rows) {
            var current = row.id() == null ? null : existing.get(row.id());
            var port = current != null && current.getDirection() == row.direction()
                    ? current
                    : new DataProductPort(product, row.direction());
            port.setSortOrder(updated.size());
            port.setName(blankToNull(row.name()));
            port.setContract(row.contractMappingId() == null
                    ? null
                    : mappings.findById(row.contractMappingId())
                            .orElseThrow(() -> badRequest("Der gewählte Contract existiert nicht.")));
            port.setContractVersion(blankToNull(row.contractVersion()));
            updated.add(port);
        }
        product.getPorts().clear();
        product.getPorts().addAll(updated);
    }

    private DataProduct load(UUID id) {
        return products.findById(id).orElseThrow(() -> notFound("Datenprodukt nicht gefunden."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
