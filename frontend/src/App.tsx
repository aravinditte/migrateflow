import { useCallback, useEffect, useState } from 'react';
import { api, Page, SessionUser } from './lib/api';
import Workspace from './features/Workspace';

interface Project { id: string; name: string; description: string; status: string; windowNote: string; createdAt: string }

export default function App() {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selected, setSelected] = useState<Project | null>(null);
  const [error, setError] = useState('');

  const loadProjects = useCallback(async () => {
    const page = await api.get<Page<Project>>('/api/v1/projects?size=50');
    setProjects(page.content);
  }, []);

  useEffect(() => {
    api.get<SessionUser>('/api/v1/auth/me')
      .then(async (me) => {
        setUser(me);
        await loadProjects();
      })
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, [loadProjects]);

  const logout = async () => {
    await api.post('/api/v1/auth/logout');
    setUser(null);
    setSelected(null);
  };

  if (loading) return <main><p className="muted">Loading…</p></main>;
  if (!user) return <Login onLogin={async (me) => { setUser(me); await loadProjects(); }} />;

  return (
    <div>
      <header className="top">
        <h1>Migrate<span>Flow</span></h1>
        <div className="muted">
          {user.displayName} · {user.role} · {user.email}{' '}
          <button className="secondary" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main>
        {error && <div className="error">{error}</div>}
        {!selected && (
          <>
            <CreateProject onCreated={async (p) => { await loadProjects(); setSelected(p); }} setError={setError} />
            <div className="card">
              <h2>Projects</h2>
              {projects.length === 0 && <p className="muted">No projects yet. Create one to begin a migration.</p>}
              <table>
                <thead><tr><th>Name</th><th>Status</th><th>Description</th><th></th></tr></thead>
                <tbody>{projects.map((p) => (
                  <tr key={p.id}>
                    <td>{p.name}</td><td><span className="badge">{p.status}</span></td>
                    <td className="muted">{p.description}</td>
                    <td><button className="secondary" onClick={() => setSelected(p)}>Open</button></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </>
        )}
        {selected && (
          <>
            <p><button className="secondary" onClick={() => setSelected(null)}>← All projects</button></p>
            <Workspace project={selected} role={user.role} />
          </>
        )}
      </main>
    </div>
  );
}

function Login({ onLogin }: { onLogin: (user: SessionUser) => Promise<void> }) {
  const [email, setEmail] = useState('engineer@example.test');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      const me = await api.post<SessionUser>('/api/v1/auth/login', { email, password });
      await onLogin(me);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    }
  };
  return (
    <main>
      <div className="card login">
        <h2>MigrateFlow sign in</h2>
        <p className="muted">Demo accounts are created only by the dev-only seed. Failures are deliberately non-specific.</p>
        {error && <div className="error">{error}</div>}
        <form onSubmit={submit}>
          <label>Email<input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" /></label>
          <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></label>
          <p><button type="submit" style={{ width: '100%' }}>Sign in</button></p>
        </form>
      </div>
    </main>
  );
}

function CreateProject({ onCreated, setError }: { onCreated: (p: Project) => Promise<void>; setError: (e: string) => void }) {
  const [name, setName] = useState('Hinata Trading — legacy sales migration');
  const [description, setDescription] = useState('One-time CSV migration of customers and historical orders to the replacement order-management system.');
  const [windowNote, setWindowNote] = useState('Planned maintenance window');
  const create = async () => {
    setError('');
    try {
      const project = await api.post<Project>('/api/v1/projects', { name, description, windowNote });
      await onCreated(project);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not create the project');
    }
  };
  return (
    <div className="card">
      <h2>Create project (ENGINEER)</h2>
      <div className="row">
        <div><label>Name<input value={name} onChange={(e) => setName(e.target.value)} /></label></div>
        <div><label>Maintenance window<input value={windowNote} onChange={(e) => setWindowNote(e.target.value)} /></label></div>
        <button onClick={create}>Create project</button>
      </div>
      <label>Description<input value={description} onChange={(e) => setDescription(e.target.value)} /></label>
    </div>
  );
}
