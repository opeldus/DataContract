import { type FormEvent, useEffect, useState } from "react";
import { api, type Me } from "./api";
import { MappingEditor } from "./MappingEditor";
import { MappingList } from "./MappingList";

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
  const [openId, setOpenId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function refreshUser() {
    api.me().then(setMe).catch((cause: Error) => setError(cause.message));
  }

  useEffect(refreshUser, []);

  if (error) {
    return <main className="page"><p role="alert" className="error">{error}</p></main>;
  }
  if (!me) {
    return <main className="page"><p>Lädt…</p></main>;
  }
  if (!me.authenticated) {
    return <Login onLogin={refreshUser} />;
  }

  return (
    <>
      <nav className="topbar">
        <strong>DataContract</strong>
        <span className="spacer" />
        <span>{me.displayName}</span>
        <button
          type="button"
          onClick={() =>
            api.logout().finally(() => {
              setOpenId(null);
              setMe(null);
              refreshUser();
            })
          }
        >
          Abmelden
        </button>
      </nav>
      {openId ? (
        <MappingEditor key={openId} id={openId} me={me} onBack={() => setOpenId(null)} />
      ) : (
        <MappingList me={me} onOpen={setOpenId} />
      )}
    </>
  );
}
