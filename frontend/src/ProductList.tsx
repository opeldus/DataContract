import { type FormEvent, useEffect, useState } from "react";
import { api, type Me, type ProductSummary } from "./api";
import { STATUS_LABELS, toTechnicalName } from "./labels";

interface Props {
  me: Me;
  onOpen: (id: string) => void;
}

export function ProductList({ me, onOpen }: Props) {
  const [products, setProducts] = useState<ProductSummary[] | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listProducts().then(setProducts).catch((cause: Error) => setError(cause.message));
  }, []);

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const created = await api.createProduct(name.trim(), toTechnicalName(name));
      onOpen(created.id);
    } catch (cause) {
      setError((cause as Error).message);
    }
  }

  return (
    <main className="page">
      <h1>Datenprodukte</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {me.roles.includes("EDITOR") && (
        <form className="toolbar panel" onSubmit={create}>
          <label className="field grow">
            <span>Neues Datenprodukt</span>
            <input value={name} onChange={(event) => setName(event.target.value)} placeholder="z. B. Kunden-Mart" />
          </label>
          <button type="submit" className="primary" disabled={!toTechnicalName(name)}>Anlegen</button>
        </form>
      )}
      <section className="panel">
        {products === null && !error && <p>Lädt…</p>}
        {products?.length === 0 && <p className="empty">Es gibt noch keine Datenprodukte.</p>}
        {products && products.length > 0 && (
          <table className="list">
            <thead>
              <tr>
                <th>Name</th>
                <th>Produkt</th>
                <th>Version</th>
                <th>Status</th>
                <th>Team</th>
                <th>Ports (ein/aus)</th>
                <th>Geändert</th>
              </tr>
            </thead>
            <tbody>
              {products.map((product) => (
                <tr key={product.id}>
                  <td>
                    <button type="button" className="link" onClick={() => onOpen(product.id)}>{product.name}</button>
                  </td>
                  <td><code>{product.productName}</code></td>
                  <td>{product.productVersion}</td>
                  <td><span className={`status status-${product.status}`}>{STATUS_LABELS[product.status]}</span></td>
                  <td>{product.ownerTeam ?? "–"}</td>
                  <td>{product.inputPorts} / {product.outputPorts}</td>
                  <td>{new Date(product.updatedAt).toLocaleString("de-DE")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
