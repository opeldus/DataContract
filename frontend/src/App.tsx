import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { api, type Me } from "./api";
import { DependencyGraph } from "./DependencyGraph";
import { MappingEditor } from "./MappingEditor";
import { MappingList } from "./MappingList";
import { ProductEditor } from "./ProductEditor";
import { ProductList } from "./ProductList";

type Page =
  | { view: "mappings" }
  | { view: "mapping"; id: string }
  | { view: "products" }
  | { view: "product"; id: string }
  | { view: "graph" };

function Login({ onLogin }: { onLogin: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await api.login(username, password);
      onLogin();
    } catch {
      setError("Anmeldung fehlgeschlagen. Bitte Benutzername und Passwort prüfen.");
    }
  }

  return (
    <main className="login">
      <form className="panel" onSubmit={submit}>
        <h1>DataContract</h1>
        {error && <p role="alert" className="error">{error}</p>}
        <label className="field">
          <span>Benutzername</span>
          <input autoComplete="username" autoFocus value={username} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label className="field">
          <span>Passwort</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        <button type="submit" className="primary">Anmelden</button>
      </form>
    </main>
  );
}

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [page, setPage] = useState<Page>({ view: "mappings" });
  const [error, setError] = useState<string | null>(null);
  const dirty = useRef(false);
  const onDirtyChange = useCallback((value: boolean) => {
    dirty.current = value;
  }, []);

  function refreshUser() {
    api.me().then(setMe).catch((cause: Error) => setError(cause.message));
  }

  useEffect(refreshUser, []);

  function confirmLeave() {
    return !dirty.current || window.confirm("Nicht gespeicherte Änderungen verwerfen?");
  }

  function navigate(next: Page) {
    if (confirmLeave()) {
      dirty.current = false;
      setPage(next);
    }
  }

  if (error) {
    return <main className="page"><p role="alert" className="error">{error}</p></main>;
  }
  if (!me) {
    return <main className="page"><p>Lädt…</p></main>;
  }
  if (!me.authenticated) {
    return <Login onLogin={refreshUser} />;
  }

  const section = page.view === "mapping" ? "mappings" : page.view === "product" ? "products" : page.view;

  return (
    <>
      <nav className="topbar">
        <strong>DataContract</strong>
        <div className="nav-tabs">
          {(
            [
              ["mappings", "Mappings"],
              ["products", "Datenprodukte"],
              ["graph", "Abhängigkeiten"],
            ] as const
          ).map(([view, label]) => (
            <button
              key={view}
              type="button"
              aria-current={section === view ? "page" : undefined}
              onClick={() => navigate({ view })}
            >
              {label}
            </button>
          ))}
        </div>
        <span className="spacer" />
        <span>{me.displayName}</span>
        <button
          type="button"
          onClick={() => {
            if (!confirmLeave()) {
              return;
            }
            dirty.current = false;
            api.logout().finally(() => {
              setPage({ view: "mappings" });
              setMe(null);
              refreshUser();
            });
          }}
        >
          Abmelden
        </button>
      </nav>
      {page.view === "mappings" && <MappingList me={me} onOpen={(id) => navigate({ view: "mapping", id })} />}
      {page.view === "mapping" && (
        <MappingEditor
          key={page.id}
          id={page.id}
          me={me}
          onBack={() => navigate({ view: "mappings" })}
          onOpenProduct={(id) => navigate({ view: "product", id })}
          onDirtyChange={onDirtyChange}
        />
      )}
      {page.view === "products" && <ProductList me={me} onOpen={(id) => navigate({ view: "product", id })} />}
      {page.view === "product" && (
        <ProductEditor
          key={page.id}
          id={page.id}
          me={me}
          onBack={() => navigate({ view: "products" })}
          onDirtyChange={onDirtyChange}
        />
      )}
      {page.view === "graph" && (
        <DependencyGraph
          onOpen={(node) =>
            navigate(node.kind === "PRODUCT" ? { view: "product", id: node.subjectId } : { view: "mapping", id: node.subjectId })
          }
        />
      )}
    </>
  );
}
