import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { api, type Me, type Release } from "./api";
import { DependencyGraph } from "./DependencyGraph";
import { MappingEditor } from "./MappingEditor";
import { MappingList } from "./MappingList";
import { ProductEditor } from "./ProductEditor";
import { ProductList } from "./ProductList";
import { ReleaseList } from "./ReleaseList";
import { RequirementTrace } from "./RequirementTrace";
import { fromHash, type Page, toHash } from "./routes";

const RELEASE_STORAGE_KEY = "datacontract.release";

function storedRelease(): string | null {
  try {
    return window.localStorage.getItem(RELEASE_STORAGE_KEY);
  } catch {
    return null;
  }
}

function storeRelease(release: string | null) {
  try {
    if (release) {
      window.localStorage.setItem(RELEASE_STORAGE_KEY, release);
    } else {
      window.localStorage.removeItem(RELEASE_STORAGE_KEY);
    }
  } catch {
    // The selection only lasts for this visit then.
  }
}

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
  const [page, setPage] = useState<Page>(() => fromHash(window.location.hash));
  const [error, setError] = useState<string | null>(null);
  const [releases, setReleases] = useState<Release[]>([]);
  const [release, setRelease] = useState<string | null>(storedRelease);
  const dirty = useRef(false);
  const currentPage = useRef(page);
  currentPage.current = page;
  const onDirtyChange = useCallback((value: boolean) => {
    dirty.current = value;
  }, []);

  function refreshUser() {
    api.me().then(setMe).catch((cause: Error) => setError(cause.message));
  }

  useEffect(refreshUser, []);

  const refreshReleases = useCallback(() => {
    api
      .listReleases()
      .then((loaded) => {
        setReleases(loaded);
        // A stored release may have been closed in the meantime.
        setRelease((current) => {
          const valid = loaded.some((candidate) => candidate.id === current && candidate.state === "OPEN");
          if (!valid) {
            storeRelease(null);
          }
          return valid ? current : null;
        });
      })
      .catch((cause: Error) => setError(cause.message));
  }, []);

  const authenticated = me?.authenticated ?? false;
  // Publishing elsewhere changes what blocks a release, so the page always loads the current state.
  const onReleasesPage = page.view === "releases";
  useEffect(() => {
    if (authenticated) {
      refreshReleases();
    }
  }, [authenticated, onReleasesPage, refreshReleases]);

  function selectRelease(next: string | null) {
    storeRelease(next);
    setRelease(next);
  }

  /** Covers the browser's back button and links opened from Jira or Confluence. */
  useEffect(() => {
    const onHashChange = () => {
      const next = fromHash(window.location.hash);
      if (toHash(currentPage.current) === toHash(next)) {
        return;
      }
      if (!dirty.current || window.confirm("Nicht gespeicherte Änderungen verwerfen?")) {
        dirty.current = false;
        setPage(next);
      } else {
        window.history.pushState(null, "", toHash(currentPage.current));
      }
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  function confirmLeave() {
    return !dirty.current || window.confirm("Nicht gespeicherte Änderungen verwerfen?");
  }

  function navigate(next: Page) {
    if (confirmLeave()) {
      dirty.current = false;
      setPage(next);
      window.history.pushState(null, "", toHash(next));
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
              ["requirements", "Anforderungen"],
              ["releases", "Releases"],
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
        <label className="release-select">
          <span>Stand</span>
          <select value={release ?? ""} onChange={(event) => selectRelease(event.target.value || null)}>
            <option value="">main</option>
            {releases
              .filter((candidate) => candidate.state === "OPEN")
              .map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
              ))}
          </select>
        </label>
        <span>{me.displayName}</span>
        <button
          type="button"
          onClick={() => {
            if (!confirmLeave()) {
              return;
            }
            dirty.current = false;
            api.logout().finally(() => {
              setMe(null);
              refreshUser();
            });
          }}
        >
          Abmelden
        </button>
      </nav>
      {page.view === "mappings" && <MappingList me={me} release={release} onOpen={(id) => navigate({ view: "mapping", id })} />}
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
      {page.view === "products" && <ProductList me={me} release={release} onOpen={(id) => navigate({ view: "product", id })} />}
      {page.view === "product" && (
        <ProductEditor
          key={page.id}
          id={page.id}
          me={me}
          onBack={() => navigate({ view: "products" })}
          onDirtyChange={onDirtyChange}
        />
      )}
      {page.view === "requirements" && (
        <RequirementTrace
          release={release}
          onOpenMapping={(id) => navigate({ view: "mapping", id })}
          onOpenProduct={(id) => navigate({ view: "product", id })}
        />
      )}
      {page.view === "releases" && (
        <ReleaseList
          me={me}
          releases={releases}
          selected={release}
          onChanged={refreshReleases}
          onSelect={selectRelease}
          onOpenItem={(kind, id) => navigate(kind === "PRODUCT" ? { view: "product", id } : { view: "mapping", id })}
        />
      )}
      {page.view === "graph" && (
        <DependencyGraph
          release={release}
          onOpen={(node) =>
            navigate(node.kind === "PRODUCT" ? { view: "product", id: node.subjectId } : { view: "mapping", id: node.subjectId })
          }
        />
      )}
    </>
  );
}
