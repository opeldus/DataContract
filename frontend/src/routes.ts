export type Page =
  | { view: "mappings" }
  | { view: "mapping"; id: string }
  | { view: "products" }
  | { view: "product"; id: string }
  | { view: "graph" }
  | { view: "requirements" }
  | { view: "releases" }
  | { view: "etl" }
  | { view: "etlJob"; id: string };

/** Hash routes work without server configuration; Jira and Confluence link to them. */
export function toHash(page: Page): string {
  switch (page.view) {
    case "mapping":
      return `#/mappings/${page.id}`;
    case "product":
      return `#/products/${page.id}`;
    case "etlJob":
      return `#/etl/${page.id}`;
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
    case "etl":
      return id ? { view: "etlJob", id } : { view: "etl" };
    case "graph":
      return { view: "graph" };
    case "requirements":
      return { view: "requirements" };
    case "releases":
      return { view: "releases" };
    default:
      return { view: "mappings" };
  }
}
