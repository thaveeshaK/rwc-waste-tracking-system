import type { Row, Total } from './types'
import { label } from './types'
import { amount } from './format'
export function TotalsTable({ totals, tonnes = false }: {totals: Total[]; tonnes?: boolean}) {
  if (!totals.length) return <p>No activity for the selected period.</p>
  return <div className="table-scroll"><table><thead><tr><th>Workflow / direction</th><th>Container</th><th>Category</th><th>Metric</th><th>Value</th><th>Unit</th></tr></thead>
    <tbody>{totals.map((t, i) => <tr key={i}><td>{label(t.workflow)}{t.direction && ` / ${t.direction}`}</td><td>{t.container ?? '-'}</td><td>{t.category ?? '-'}</td><td>{label(t.metric)}</td><td>{t.unit === 'kg' ? amount(t.value, tonnes) : t.value}</td><td>{tonnes && t.unit === 'kg' ? 'tonnes' : t.unit}</td></tr>)}</tbody></table></div>
}
export function DetailTable({ rows, onOpen }: {rows: Row[]; onOpen?: (row: Row) => void}) {
  if (!rows.length) return <p>No matching records.</p>
  return <div className="table-scroll"><table><thead><tr><th>Date / reference month</th><th>Workflow</th><th>Record / row type</th><th>Details</th><th>Weight (kg)</th><th>Count</th><th>Submitted by</th></tr></thead><tbody>
    {rows.map((r, i) => <tr key={i}><td>{r.rowType === 'REFERENCE' ? r.date.slice(0, 7) : r.date}</td><td>{label(r.workflow)}</td><td>{onOpen && r.rowType !== 'REFERENCE' ? <button className="text-button" onClick={() => onOpen(r)}>#{r.id}</button> : `#${r.id}`} / {label(r.rowType)}</td><td>{[r.container, r.location, r.crew, r.direction, r.category, r.status && label(r.status)].filter(Boolean).join(' / ') || '-'}{r.percentage && ` (${r.percentage}%)`}</td><td>{r.kg ?? '-'}{r.workflow === 'WHITEGOODS' && ' estimated'}</td><td>{r.count ?? '-'}</td><td>{r.createdBy}</td></tr>)}
  </tbody></table></div>
}
