import { useEffect, useState } from 'react'
import { errorMessage, request } from '../../api/client'
import type { Workflow } from '../records/types'
import { workflowEntries } from './workflows'

interface RecentSubmission { id: number; recordDate: string; workflow: Workflow; details: string; weightKg: string | null; count: number | null }
interface Props { onNew: (workflow: Workflow) => void; onRecords: () => void; busy: boolean; unresolved: boolean; currentWorkflow: Workflow }
const descriptions: Record<Workflow, string> = {
  SCRAP: 'Record monthly Scrap material weights.',
  EWASTE_PICKUP: 'Record E-waste collected from organisations.',
  CRATE_PALLET: 'Record weighed crate and pallet loads.', SALVAGE: 'Record Salvage Up and Down trip weights.',
  PICKUP: 'Record collected material weights.', ELECTRICAL_CRATE_PALLET: 'Record electrical crate and pallet loads.',
  COLLECTABLES: 'Record collectable tub weights.', WHITEGOODS: 'Record daily Whitegoods counts.',
  ELECTRICAL_TESTING: 'Record testing tallies and diversions.',
}
export function StaffOperationsPage({onNew, onRecords, busy, unresolved, currentWorkflow}: Props) {
  const [recent, setRecent] = useState<RecentSubmission[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let cancelled = false
    request<RecentSubmission[]>('/api/operations/recent-submissions').then(rows => {if (!cancelled) setRecent(rows)})
      .catch(e => {if (!cancelled) setError(errorMessage(e))}).finally(() => {if (!cancelled) setLoading(false)})
    return () => {cancelled = true}
  }, [attempt])
  return <section className="staff-operations"><div className="page-heading"><div><h1>Staff Operations</h1><p>Choose a workflow to start a new record.</p></div></div>
    <div className="workflow-card-grid">{workflowEntries.map(w => <article className="panel workflow-card" key={w.id} data-workflow={w.id}><h2>{w.name}</h2><p>{descriptions[w.id]}</p>
      <button disabled={busy || (unresolved && currentWorkflow !== w.id)} aria-label={`${w.id === 'ELECTRICAL_TESTING' ? 'Open' : 'New entry'}: ${w.name}`} onClick={() => onNew(w.id)}>{unresolved && currentWorkflow === w.id ? 'Resume submission' : w.id === 'ELECTRICAL_TESTING' ? 'Open' : 'New Entry'}</button>
    </article>)}</div>
    <section className="panel report-section staff-recent" aria-label="Recent submissions"><div className="detail-heading"><h2>Recent submissions</h2><button className="secondary" disabled={busy} onClick={onRecords}>View Records</button></div>
      {loading ? <p role="status">Loading recent submissions...</p> : error ? <div><p className="notice error" role="alert">{error}</p><button className="secondary" disabled={busy} onClick={() => {setLoading(true); setError(''); setAttempt(n => n+1)}}>Retry recent submissions</button></div> : recent.length === 0 ? <p>No recent submissions.</p> : <div className="table-scroll"><table><caption className="sr-only">Latest five active operational submissions, across all dates.</caption><thead><tr><th>Date / reporting month</th><th>Workflow</th><th>Details</th><th>Weight / count</th></tr></thead><tbody>{recent.map(r => <tr key={r.workflow+r.id}><td>{r.workflow === 'SCRAP' ? r.recordDate.slice(0,7) : r.recordDate}</td><td>{workflowEntries.find(w => w.id === r.workflow)?.name}</td><td>{r.details}</td><td>{r.count !== null && <>{r.count} {r.workflow === 'WHITEGOODS' ? 'received' : 'tallied'}{r.weightKg !== null && <br/>}</>}{r.weightKg !== null && <>{r.weightKg} kg{r.workflow === 'WHITEGOODS' ? ' estimated' : ''}</>}{r.count === null && r.weightKg === null && '-'}</td></tr>)}</tbody></table></div>}
    </section>
  </section>
}
