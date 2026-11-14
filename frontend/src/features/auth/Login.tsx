import { useState } from 'react';
import { api, SessionUser } from '../../lib/api';

export default function Login({ onLogin }: { onLogin: (user: SessionUser) => Promise<void> }) {
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
