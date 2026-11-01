package de.opeldus.datacontract.integration;

import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.product.DataProduct;
import de.opeldus.datacontract.product.DataProductPort;
import de.opeldus.datacontract.product.PortDirection;
import java.util.List;
import org.springframework.stereotype.Component;

/** Renders Confluence storage format, which is XHTML: every user-provided value goes through {@link #xml}. */
@Component
class ConfluenceRenderer {

    private final RequirementDirectory requirements;
    private final MappingRepository mappings;

    ConfluenceRenderer(RequirementDirectory requirements, MappingRepository mappings) {
        this.requirements = requirements;
        this.mappings = mappings;
    }

    static String marker(java.util.UUID subjectId) {
        return "datacontract-id:" + subjectId;
    }

    String contractTitle(Mapping mapping) {
        return "Contract: " + mapping.getContractName();
    }

    String productTitle(DataProduct product) {
        return "Datenprodukt: " + product.getProductName();
    }

    String contract(Mapping mapping, String appUrl) {
        var html = new StringBuilder(notice(marker(mapping.getKey()), appUrl));
        html.append("<h2>Überblick</h2>").append("<table><tbody>");
        row(html, "Name", mapping.getName());
        row(html, "Version", mapping.getContractVersion());
        row(html, "Domäne", mapping.getDomain());
        row(html, "Team", mapping.getOwnerTeam());
        row(html, "Zieltabelle", mapping.getTargetTable());
        row(html, "Zweck", mapping.getDescription());
        html.append("</tbody></table><h2>Regeln</h2><table><thead><tr>");
        for (var title : List.of("Zielfeld", "Typ", "Pflicht", "PK", "Quelle", "Transformation", "Beschreibung", "Anforderung")) {
            html.append("<th>").append(title).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (var rule : mapping.getRules()) {
            html.append("<tr>");
            cell(html, rule.getTargetColumn());
            cell(html, rule.getTargetType());
            cell(html, rule.isRequired() ? "ja" : "nein");
            cell(html, rule.isPrimaryKey() ? "ja" : "nein");
            cell(html, rule.getSourceDataset() == null ? null : rule.getSourceDataset() + "." + rule.getSourceColumn());
            cell(html, rule.getTransformation());
            cell(html, rule.getDescription());
            html.append("<td>").append(requirementLink(rule.getRequirementKey())).append("</td></tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    String product(DataProduct product, String appUrl) {
        var html = new StringBuilder(notice(marker(product.getKey()), appUrl));
        html.append("<h2>Überblick</h2><table><tbody>");
        row(html, "Name", product.getName());
        row(html, "Version", product.getProductVersion());
        row(html, "Domäne", product.getDomain());
        row(html, "Typ", product.getProductType());
        row(html, "Team", product.getOwnerTeam());
        row(html, "Zweck", product.getPurpose());
        html.append("</tbody></table>");
        ports(html, "Input-Ports", product.ports(PortDirection.INPUT));
        ports(html, "Output-Ports", product.ports(PortDirection.OUTPUT));
        return html.toString();
    }

    private void ports(StringBuilder html, String title, List<DataProductPort> ports) {
        html.append("<h2>").append(title).append("</h2>");
        if (ports.isEmpty()) {
            html.append("<p>Keine.</p>");
            return;
        }
        html.append("<table><thead><tr><th>Name</th><th>Contract</th><th>Version</th></tr></thead><tbody>");
        for (var port : ports) {
            html.append("<tr>");
            cell(html, port.getName());
            cell(html, port.getContractKey() == null ? null
                    : mappings.findFirstByContractKey(port.getContractKey()).map(Mapping::getContractName).orElse(null));
            cell(html, port.getContractVersion());
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    private static String notice(String marker, String appUrl) {
        var link = appUrl == null ? "DataContract" : "<a href=\"" + xml(appUrl) + "\">DataContract</a>";
        return "<ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>Diese Seite wird automatisch aus " + link
                + " erzeugt. Änderungen hier werden bei der nächsten Veröffentlichung überschrieben.</p><p><sub>"
                + xml(marker) + "</sub></p></ac:rich-text-body></ac:structured-macro>";
    }

    private String requirementLink(String key) {
        if (key == null) {
            return "";
        }
        return requirements.url(key)
                .map(url -> "<a href=\"" + xml(url) + "\">" + xml(key) + "</a>")
                .orElseGet(() -> xml(key));
    }

    private static void row(StringBuilder html, String title, String value) {
        html.append("<tr><th>").append(xml(title)).append("</th>");
        cell(html, value);
        html.append("</tr>");
    }

    private static void cell(StringBuilder html, String value) {
        html.append("<td>").append(xml(value)).append("</td>");
    }

    /** Also removes control characters, which are not allowed in XML even when escaped. */
    static String xml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
