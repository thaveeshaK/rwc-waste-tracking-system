import { useEffect, useState } from 'react'
import { errorMessage, request } from '../../api/client'
import { IncompleteIndicator } from '../../components/IncompleteIndicator'
import type { Workflow } from '../records/types'
import { workflowEntries } from './workflows'
import { WorkflowCards } from './WorkflowCards'
import { TodayActivity } from '../../components/TodayActivity'
import type { TodayActivitySummary } from '../reporting/types'

interface RecentSubmission { id: number; recordDate: string; workflow: Workflow; details: string; weightKg: string | null; count: number | null }
interface Props { onIncomplete: () => void; onNew: (workflow: Workflow) => void; onRecords: () => void; busy: boolean; unresolved: boolean; currentWorkflow: Workflow }
export function StaffOperationsPage({onIncomplete, onNew, onRecords, busy, unresolved, currentWorkflow}: Props) {
  const [recent, setRecent] = useState<RecentSubmission[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [todayAttempt, setTodayAttempt] = useState(0)
  const [todayResult, setTodayResult] = useState<{attempt: number; data?: TodayActivitySummary; error?: string} | null>(null)
  const todayLoading=todayResult?.attempt!==todayAttempt
  useEffect(() => {
    let cancelled=false
    request<TodayActivitySummary>('/api/operations/today-activity').then(data=>{if(!cancelled)setTodayResult({attempt:todayAttempt,data})})
      .catch(e=>{if(!cancelled)setTodayResult({attempt:todayAttempt,error:errorMessage(e)})})
    return()=>{cancelled=true}
  }, [todayAttempt])
  useEffect(() => {
    let cancelled = false
    request<RecentSubmission[]>('/api/operations/recent-submissions').then(rows => {if (!cancelled) setRecent(rows)})
      .catch(e => {if (!cancelled) setError(errorMessage(e))}).finally(() => {if (!cancelled) setLoading(false)})
    return () => {cancelled = true}
  }, [attempt])
  return <section className="staff-operations">
    <div className="page-heading"><div><div className="workflows-heading"><h1>Workflows</h1><IncompleteIndicator refreshKey={`${todayAttempt}:${attempt}`} onOpen={onIncomplete}/></div><p>Choose a workflow to start a new record.</p></div></div>
    <WorkflowCards onNew={onNew} busy={busy} unresolved={unresolved} currentWorkflow={currentWorkflow}/>
    {todayLoading?<p role="status">Loading today's activity...</p>:todayResult?.error?<div><p className="notice error" role="alert">Today's activity: {todayResult.error}</p><button className="secondary" disabled={busy} onClick={()=>setTodayAttempt(n=>n+1)}>Retry today's activity</button></div>:todayResult?.data&&<TodayActivity {...todayResult.data}/>}
    <section className="panel report-section staff-recent" aria-label="Recent submissions"><div className="detail-heading"><h2>Recent submissions</h2><button className="secondary" disabled={busy} onClick={onRecords}>View Records</button></div>
      {loading ? <p role="status">Loading recent submissions...</p> : error ? <div><p className="notice error" role="alert">{error}</p><button className="secondary" disabled={busy} onClick={() => {setLoading(true); setError(''); setAttempt(n => n+1)}}>Retry recent submissions</button></div> : recent.length === 0 ? <p>No recent submissions.</p> : <div className="table-scroll"><table><caption className="sr-only">Latest five active operational submissions, across all dates.</caption><thead><tr><th>Date / reporting month</th><th>Workflow</th><th>Details</th><th>Weight / count</th></tr></thead><tbody>{recent.map(r => <tr key={r.workflow+r.id}><td>{r.workflow === 'SCRAP' ? r.recordDate.slice(0,7) : r.recordDate}</td><td>{workflowEntries.find(w => w.id === r.workflow)?.name}</td><td>{r.details}</td><td>{r.count !== null && <>{r.count} {r.workflow === 'WHITEGOODS' ? 'received' : 'tallied'}{r.weightKg !== null && <br/>}</>}{r.weightKg !== null && <>{r.weightKg} kg{r.workflow === 'WHITEGOODS' ? ' estimated' : ''}</>}{r.count === null && r.weightKg === null && '-'}</td></tr>)}</tbody></table></div>}
    </section>
  </section>
}
