package de.opeldus.datacontract.release;

import de.opeldus.datacontract.product.DataProduct;
import de.opeldus.datacontract.product.DataProductPort;
import de.opeldus.datacontract.product.PortDirection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class ProductMergeAdapter implements MergeAdapter<DataProduct> {

    private static final Map<String, String> LABELS = Map.of("name", "Name", "domain", "Domäne", "productType", "Typ",
            "purpose", "Zweck", "ownerTeam", "Team", "portName", "Portname", "contractKey", "Contract",
            "contractVersion", "Contract-Version");

    @Override
    public MergeDocument toDocument(DataProduct product) {
        return document(ProductSnapshot.of(product));
    }

    @Override
    public MergeDocument fromSnapshot(String json) {
        return document(ProductSnapshot.fromJson(json));
    }

    private static MergeDocument document(ProductSnapshot snapshot) {
        var header = new LinkedHashMap<String, String>();
        header.put("name", snapshot.name());
        header.put("domain", snapshot.domain());
        header.put("productType", snapshot.productType());
        header.put("purpose", snapshot.purpose());
        header.put("ownerTeam", snapshot.ownerTeam());
        var items = new LinkedHashMap<UUID, Map<String, String>>();
        for (var port : snapshot.ports()) {
            var fields = new LinkedHashMap<String, String>();
            fields.put("direction", port.direction());
            fields.put("portName", port.name());
            fields.put("contractKey", port.contractKey() == null ? null : port.contractKey().toString());
            fields.put("contractVersion", port.contractVersion());
            items.put(port.id(), fields);
        }
        return new MergeDocument(header, items);
    }

    @Override
    public void apply(DataProduct product, MergeDocument document) {
        var header = document.header();
        if (header.get("name") != null) {
            product.setName(header.get("name"));
        }
        product.setDomain(header.get("domain"));
        product.setProductType(header.get("productType"));
        product.setPurpose(header.get("purpose"));
        product.setOwnerTeam(header.get("ownerTeam"));
        var existing = product.getPorts().stream()
                .collect(Collectors.toMap(DataProductPort::getLineageId, Function.identity()));
        var updated = new ArrayList<DataProductPort>();
        for (var item : document.items().entrySet()) {
            var fields = item.getValue();
            var port = existing.get(item.getKey());
            if (port == null) {
                port = new DataProductPort(product, PortDirection.valueOf(fields.get("direction")));
                port.setLineageId(item.getKey());
            }
            port.setSortOrder(updated.size());
            port.setName(fields.get("portName"));
            port.setContractKey(fields.get("contractKey") == null ? null : UUID.fromString(fields.get("contractKey")));
            port.setContractVersion(fields.get("contractVersion"));
            updated.add(port);
        }
        product.getPorts().clear();
        product.getPorts().addAll(updated);
    }

    @Override
    public String label(UUID itemId, String field, MergeDocument document, Map<String, String> theirsItem) {
        var fieldLabel = field == null ? null : LABELS.getOrDefault(field, field);
        if (itemId == null) {
            return fieldLabel;
        }
        var fields = document.items().get(itemId);
        var port = fields != null ? fields.get("portName") : theirsItem != null ? theirsItem.get("portName") : null;
        var name = "Port " + (port != null ? port : "");
        return fieldLabel == null ? name.trim() : name.trim() + ": " + fieldLabel;
    }
}
