import { useEffect, useRef, useState } from 'react';
import { api, Page } from '../../lib/api';
import { shortHash } from '../../lib/format';
import { message, SectionProps } from '../workspace/flow';

interface ProfileFile { fileId: string; kind: string; filename: string; sha256: string; rowCount: number; bytes: number }
interface SourceRow { rowId: string; dataRowNumber: number; physicalLineStart: number; values: string }

export default function Intake({ projectId, flow, patch, setError }: SectionProps) {
  const customersRef = useRef<HTMLInputElement>(null);
  const ordersRef = useRef<HTMLInputElement>(null);
  const [exportedAt, setExportedAt] = useState('');
  const [profile, setProfile] = useState<{ files: ProfileFile[] } | null>(null);
  const [busy, setBusy] = useState(false);
  const [previewFile, setPreviewFile] = useState<ProfileFile | null>(null);

  const upload = async () => {
    const customers = customersRef.current?.files?.[0];
    const orders = ordersRef.current?.files?.[0];
    if (!customers || !orders) { setError('Choose both the customers and orders CSV files.'); return; }
    setBusy(true); setError('');
    try {
      const form = new FormData();
      form.append('customers', customers);
      form.append('orders', orders);
      if (exportedAt) form.append('exportedAt', new Date(exportedAt).toISOString());
      const result = await api.postForm<{ bundleId: string }>(`/api/v1/projects/${projectId}/bundles`, form);
      patch({ bundleId: result.bundleId });
      const prof = await api.get(`/api/v1/bundles/${result.bundleId}/profile`);
      setProfile(prof as typeof profile);
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };

  return (
    <div className="card">
      <h2>Intake — paired CSV upload</h2>
      <p className="muted">Files are stored privately and immutably; SHA-256 and row provenance are recorded. A malformed file fails the whole upload.</p>
      <label>Customers CSV<input ref={customersRef} type="file" accept=".csv" /></label>
      <label>Orders CSV<input ref={ordersRef} type="file" accept=".csv" /></label>
      <label>Declared export timestamp<input type="datetime-local" value={exportedAt} onChange={(e) => setExportedAt(e.target.value)} /></label>
      <p><button onClick={upload} disabled={busy}>{busy ? 'Uploading…' : 'Upload bundle'}</button></p>
      {flow.bundleId && <p className="muted">Bundle <span className="mono">{flow.bundleId}</span></p>}
      {profile && (
        <table><thead><tr><th>Kind</th><th>File</th><th>Rows</th><th>Bytes</th><th>SHA-256</th><th>Inspect</th></tr></thead>
          <tbody>{profile.files.map((f) => (
            <tr key={f.kind}><td>{f.kind}</td><td>{f.filename}</td><td>{f.rowCount}</td><td>{f.bytes}</td>
              <td className="mono">{shortHash(f.sha256)}…</td>
              <td><button className="secondary" onClick={() => setPreviewFile(previewFile?.fileId === f.fileId ? null : f)}>
                    {previewFile?.fileId === f.fileId ? 'Hide rows' : 'Preview rows'}</button>{' '}
                <a href={`/api/v1/files/${f.fileId}/download`}>Download</a>
                <span className="muted"> (ENGINEER/AUDITOR)</span></td></tr>
          ))}</tbody></table>
      )}
      {previewFile && <RowPreview key={previewFile.fileId} file={previewFile} setError={setError} />}
    </div>
  );
}

/** Paginated raw-row preview: immutable staged values with the physical
 *  line offset each row started on (spec 10.2 / 11). */
function RowPreview({ file, setError }: { file: ProfileFile; setError: (e: string) => void }) {
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<SourceRow[]>([]);
  const size = 10;
  useEffect(() => {
    api.get<Page<SourceRow>>(`/api/v1/files/${file.fileId}/rows?page=${page}&size=${size}`)
      .then((p) => setRows(p.content))
      .catch((e) => setError(message(e)));
  }, [file.fileId, page, setError]);

  const renderValues = (raw: string): string => {
    try {
      const parsed = JSON.parse(raw) as Record<string, string>;
      return Object.entries(parsed).map(([k, v]) => `${k}=${v}`).join(' · ');
    } catch {
      return raw;
    }
  };

  return (
    <div>
      <h3>Raw rows — {file.filename}</h3>
      <table><thead><tr><th>Data row</th><th>Physical line</th><th>Values (as exported)</th></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.rowId}><td>{r.dataRowNumber}</td><td>{r.physicalLineStart}</td><td className="mono">{renderValues(r.values)}</td></tr>
        ))}</tbody></table>
      <p><button className="secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← Prev</button>{' '}
        <span className="muted">page {page + 1}</span>{' '}
        <button className="secondary" disabled={rows.length < size} onClick={() => setPage((p) => p + 1)}>Next →</button></p>
    </div>
  );
}
