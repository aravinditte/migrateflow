import { useCallback, useEffect, useState } from 'react';
import Login from './features/auth/Login';
import { CreateProject, ProjectList, ProjectSummary } from './features/projects/Projects';
import Workspace from './features/workspace/Workspace';
import { api, Page, SessionUser } from './lib/api';

export default function App() {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [selected, setSelected] = useState<ProjectSummary | null>(null);
  const [error, setError] = useState('');

  const loadProjects = useCallback(async () => {
    const page = await api.get<Page<ProjectSummary>>('/api/v1/projects?size=50');
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
            <ProjectList projects={projects} onOpen={setSelected} />
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
