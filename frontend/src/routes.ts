export type Page =
  | { view: "mappings" }
  | { view: "mapping"; id: string }
  | { view: "products" }
  | { view: "product"; id: string }
  | { view: "graph" }
  | { view: "requirements" };

/** Hash routes work without server configuration; Jira and Confluence link to them. */
export function toHash(page: Page): string {
  switch (page.view) {
    case "mapping":
      return `#/mappings/${page.id}`;
    case "product":
      return `#/products/${page.id}`;
    default:
      return `#/${page.view}`;
  }
}

export function fromHash(hash: string): Page {
  const [section, id] = hash.replace(/^#\/?/, "").split("/");
  switch (section) {
    case "mappings":
      return id ? { view: "mapping", id } : { view: "mappings" };
    case "products":
      return id ? { view: "product", id } : { view: "products" };
    case "graph":
      return { view: "graph" };
    case "requirements":
      return { view: "requirements" };
    default:
      return { view: "mappings" };
  }
}
