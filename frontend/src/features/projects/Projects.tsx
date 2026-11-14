import { useState } from 'react';
import { api } from '../../lib/api';

export interface ProjectSummary { id: string; name: string; description: string; status: string; windowNote: string; createdAt: string }

export function CreateProject({ onCreated, setError }: { onCreated: (p: ProjectSummary) => Promise<void>; setError: (e: string) => void }) {
  const [name, setName] = useState('Hinata Trading — legacy sales migration');
  const [description, setDescription] = useState('One-time CSV migration of customers and historical orders to the replacement order-management system.');
  const [windowNote, setWindowNote] = useState('Planned maintenance window');
  const create = async () => {
    setError('');
    try {
      const project = await api.post<ProjectSummary>('/api/v1/projects', { name, description, windowNote });
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

export function ProjectList({ projects, onOpen }: { projects: ProjectSummary[]; onOpen: (p: ProjectSummary) => void }) {
  return (
    <div className="card">
      <h2>Projects</h2>
      {projects.length === 0 && <p className="muted">No projects yet. Create one to begin a migration.</p>}
      <table>
        <thead><tr><th>Name</th><th>Status</th><th>Description</th><th></th></tr></thead>
        <tbody>{projects.map((p) => (
          <tr key={p.id}>
            <td>{p.name}</td><td><span className="badge">{p.status}</span></td>
            <td className="muted">{p.description}</td>
            <td><button className="secondary" onClick={() => onOpen(p)}>Open</button></td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}
