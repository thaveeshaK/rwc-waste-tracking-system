import { lazy, Suspense, useEffect, useState } from 'react'
import { request } from './api'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import { TodayActivity } from '../../components/TodayActivity'
import type { Overview, Row } from './types'
import { label } from './types'
import { TotalsTable } from './ReportTables'
import { typeLabels } from '../electrical-testing/types'
import type { TallyType } from '../electrical-testing/types'
const DashboardCharts = lazy(() => import('./DashboardCharts'))
export function OverviewPage({onOpen, onRecords}: {onOpen: (row: Row) => void; onRecords: () => void}) {
  const [month, setMonth] = useState(() => operationalDate().slice(0, 7))
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{key: string; data?: Overview; error?: string} | null>(null)
  const key = month+':'+attempt
  const loading = result?.key !== key
  const data = loading ? undefined : result?.data
  const error = loading ? undefined : result?.error
  useEffect(() => {
    let cancelled = false
    request<Overview>('/api/admin/overview?month='+month).then(data => {if (!cancelled) setResult({key, data})})
      .catch(e => {if (!cancelled) setResult({key, error: errorMessage(e)})})
    return () => {cancelled = true}
  }, [month, key])
  const monthName = new Intl.DateTimeFormat('en-AU', {month: 'long', year: 'numeric', timeZone: 'UTC'}).format(new Date(month+'-01T00:00:00Z'))
  const current = month === operationalDate().slice(0, 7)
  return <section className="dashboard"><div className="page-heading dashboard-heading"><div><p className="eyebrow">ADMIN</p><h1>Overview</h1><p>Operational overview for {monthName}</p></div><div className="dashboard-period"><label>Overview month<input type="month" required min="0001-01" max={operationalDate().slice(0, 7)} value={month} onChange={e => {if (e.target.value) setMonth(e.target.value)}}/></label><button className="secondary" disabled={loading} onClick={() => setAttempt(n => n+1)}>Refresh</button></div></div>
    {loading && <p role="status">Loading overview...</p>}{error && <p className="notice error" role="alert">{error} Use Refresh to try again.</p>}
    {data && <><div className="dashboard-kpis">
      <article className="panel dashboard-kpi" data-kpi="records" data-value={data.recordsThisMonth}><h2>{current ? 'Records this month' : 'Records in period'}</h2><strong>{data.recordsThisMonth}</strong><p>Operational entries</p></article>
      <article className="panel dashboard-kpi" data-kpi="recovered" data-value={data.metrics.salvageRecoveredKg}><h2>{current ? 'Salvage recovered this month' : 'Salvage recovered'}</h2><strong>{data.metrics.salvageRecoveredKg}<small> kg</small></strong><p>Council-provided Down weight</p></article>
      <article className="panel dashboard-kpi" data-kpi="landfill" data-value={data.metrics.landfillKg}><h2>{current ? 'Landfill recorded this month' : 'Landfill recorded'}</h2><strong>{data.metrics.landfillKg}<small> kg</small></strong><p>Council-provided Up weight</p></article>
      <article className="panel dashboard-kpi" data-kpi="whitegoods" data-value={data.metrics.whitegoodsCount}><h2>{current ? 'Whitegoods processed this month' : 'Whitegoods processed'}</h2><strong>{data.metrics.whitegoodsCount}</strong><p>{data.metrics.whitegoodsEstimatedKg} kg estimated from received counts</p></article>
    </div><p className="dashboard-secondary">Submitted today: <strong>{data.submittedToday}</strong><span> Active entries submitted on {data.today}, across all operational dates.</span></p>
      <TodayActivity today={data.today} todayActivity={data.todayActivity}/>
      <Suspense fallback={<p role="status">Loading charts...</p>}><DashboardCharts data={data}/></Suspense>
      <section className="panel report-section dashboard-recent" aria-label="Recent submissions"><div className="detail-heading"><div><h2>Recent submissions</h2><p className="hint">Latest five active submissions, across all dates.</p></div><button className="secondary" onClick={onRecords}>View all records</button></div>
        {!data.recent.length ? <p>No submissions yet.</p> : <div className="table-scroll"><table><thead><tr><th>Date / reporting month</th><th>Workflow</th><th>Details</th><th>Weight / count</th><th>Submitted by</th></tr></thead><tbody>{data.recent.map(r => <tr key={r.workflow+r.id}><td>{r.workflow === 'SCRAP' ? r.date.slice(0,7) : r.date}</td><td>{label(r.workflow)}</td><td><button className="text-button" onClick={() => onOpen(r)}>#{r.id}</button> {[r.container, r.organisation ?? r.location, r.crew, r.category, r.status ? typeLabels[r.status as TallyType] : null].filter(Boolean).join(' / ')}</td><td>{r.kg !== null ? `${r.kg} kg${r.workflow === 'WHITEGOODS' ? ' estimated' : ''}` : r.count !== null ? `${r.count} tallied` : '-'}</td><td>{r.createdBy}</td></tr>)}</tbody></table></div>}
      </section>
      <section className="panel report-section"><details><summary>Monthly quantities by workflow and material</summary><p className="hint">Net weights, material breakdowns and estimated weights are separate measures.</p><TotalsTable totals={data.monthly.totals}/></details></section>
    </>}
  </section>
}
