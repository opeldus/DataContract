package de.opeldus.datacontract.product;

import de.opeldus.datacontract.mapping.MappingDtos.PreviewFile;
import de.opeldus.datacontract.product.ProductDtos.CreateProductRequest;
import de.opeldus.datacontract.product.ProductDtos.ProductDetail;
import de.opeldus.datacontract.product.ProductDtos.ProductSummary;
import de.opeldus.datacontract.product.ProductDtos.UpdateProductRequest;
import de.opeldus.datacontract.review.ReviewController.ApproveRequest;
import de.opeldus.datacontract.review.ReviewController.CommentRequest;
import de.opeldus.datacontract.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;
    private final CurrentUser currentUser;

    public ProductController(ProductService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ProductSummary> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public ProductDetail create(@Valid @RequestBody CreateProductRequest request) {
        return service.create(request, currentUser.username());
    }

    @GetMapping("/{id}")
    public ProductDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('EDITOR')")
    public ProductDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return service.update(id, request, currentUser.username());
    }

    @GetMapping("/{id}/preview")
    public List<PreviewFile> preview(@PathVariable UUID id) {
        return service.preview(id);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('EDITOR')")
    public ProductDetail submit(@PathVariable UUID id) {
        return service.submit(id, currentUser.username());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ProductDetail approve(@PathVariable UUID id, @RequestBody ApproveRequest request) {
        return service.approve(id, request.contentHash(), currentUser.username());
    }

    @PostMapping("/{id}/request-changes")
    @PreAuthorize("hasRole('REVIEWER')")
    public ProductDetail requestChanges(@PathVariable UUID id, @RequestBody CommentRequest request) {
        return service.requestChanges(id, request.comment(), currentUser.username());
    }

    @PostMapping("/{id}/comments")
    public ProductDetail comment(@PathVariable UUID id, @RequestBody CommentRequest request) {
        return service.comment(id, request.comment(), currentUser.username());
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('EDITOR', 'REVIEWER')")
    public ProductDetail reopen(@PathVariable UUID id) {
        return service.reopen(id, currentUser.username());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('REVIEWER')")
    public ProductDetail publish(@PathVariable UUID id) {
        return service.publish(id, currentUser.username());
    }
}
