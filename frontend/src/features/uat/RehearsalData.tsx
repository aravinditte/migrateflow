import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { formatYen } from '../../lib/format';
import { message } from '../workspace/flow';

interface RehearsalCustomer { id: string; externalCustomerId: string; fullName: string; email: string; joinedOn: string; sourceRowNumber: number }
interface RehearsalOrder { id: string; externalOrderId: string; externalCustomerId: string; orderDate: string; amountYen: number; status: string; sourceRowNumber: number }

/**
 * Read-only browser over the isolated rehearsal tables (spec 10.4 / 11):
 * reviewers spot-check the actual migrated rows before recording UAT
 * results, instead of trusting counts alone.
 */
export default function RehearsalData({ runId, setError }: { runId: string; setError: (e: string) => void }) {
  const [mode, setMode] = useState<'customers' | 'orders'>('customers');
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [customers, setCustomers] = useState<RehearsalCustomer[]>([]);
  const [orders, setOrders] = useState<RehearsalOrder[]>([]);
  const size = 20;

  useEffect(() => {
    if (!runId) return;
    const path = `/api/v1/rehearsals/${runId}/${mode}?page=${page}&size=${size}${query ? `&q=${encodeURIComponent(query)}` : ''}`;
    if (mode === 'customers') {
      api.get<{ content: RehearsalCustomer[] }>(path).then((p) => setCustomers(p.content)).catch((e) => setError(message(e)));
    } else {
      api.get<{ content: RehearsalOrder[] }>(path).then((p) => setOrders(p.content)).catch((e) => setError(message(e)));
    }
  }, [runId, mode, query, page, setError]);

  if (!runId) return null;
  const rowCount = mode === 'customers' ? customers.length : orders.length;
  return (
    <div>
      <h3>Rehearsal data — spot-check the migrated rows</h3>
      <div className="row">
        <div><label>Dataset
          <select value={mode} onChange={(e) => { setMode(e.target.value as 'customers' | 'orders'); setPage(0); }}>
            <option value="customers">Customers</option>
            <option value="orders">Orders</option>
          </select></label></div>
        <div><label>Search by external ID<input value={input} onChange={(e) => setInput(e.target.value)} placeholder="e.g. C0001" /></label></div>
        <button className="secondary" onClick={() => { setQuery(input.trim()); setPage(0); }}>Search</button>
      </div>
      {mode === 'customers' ? (
        <table><thead><tr><th>Customer ID</th><th>Name</th><th>Email</th><th>Joined</th><th>Source row</th></tr></thead>
          <tbody>{customers.map((c) => (
            <tr key={c.id}><td>{c.externalCustomerId}</td><td>{c.fullName}</td><td>{c.email}</td><td>{c.joinedOn}</td><td>{c.sourceRowNumber}</td></tr>
          ))}</tbody></table>
      ) : (
        <table><thead><tr><th>Order ID</th><th>Customer</th><th>Date</th><th>Amount</th><th>Status</th><th>Source row</th></tr></thead>
          <tbody>{orders.map((o) => (
            <tr key={o.id}><td>{o.externalOrderId}</td><td>{o.externalCustomerId}</td><td>{o.orderDate}</td>
              <td>{formatYen(o.amountYen)}</td><td>{o.status}</td><td>{o.sourceRowNumber}</td></tr>
          ))}</tbody></table>
      )}
      <p><button className="secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← Prev</button>{' '}
        <span className="muted">page {page + 1}</span>{' '}
        <button className="secondary" disabled={rowCount < size} onClick={() => setPage((p) => p + 1)}>Next →</button></p>
    </div>
  );
}
