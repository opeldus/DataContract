package de.opeldus.datacontract.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.product.DataProduct;
import java.util.List;
import java.util.UUID;

/** What is merged for a data product; stored as JSON with each published version. */
public record ProductSnapshot(String name, String domain, String productType, String purpose, String ownerTeam,
        List<Port> ports) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record Port(UUID id, String direction, String name, UUID contractKey, String contractVersion) {}

    public static ProductSnapshot of(DataProduct product) {
        var ports = product.getPorts().stream()
                .map(port -> new Port(port.getLineageId(), port.getDirection().name(), port.getName(),
                        port.getContractKey(), port.getContractVersion()))
                .toList();
        return new ProductSnapshot(product.getName(), product.getDomain(), product.getProductType(), product.getPurpose(),
                product.getOwnerTeam(), ports);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Snapshot could not be written", exception);
        }
    }

    public static ProductSnapshot fromJson(String json) {
        try {
            return JSON.readValue(json, ProductSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Snapshot could not be read", exception);
        }
    }
}
