import { useEffect, useState } from 'react'
import { request } from './api'
import { errorMessage } from '../../api/client'
import type { Overview, Row } from './types'
import { label } from './types'
import { DetailTable, TotalsTable } from './ReportTables'
export function OverviewPage({ onOpen }: {onOpen: (row: Row) => void}) {
  const [result, setResult] = useState<{data?: Overview; error?: string}>({})
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let cancelled = false
    request<Overview>('/api/admin/overview').then(data => { if (!cancelled) setResult({data}) })
      .catch(error => { if (!cancelled) setResult({error: errorMessage(error)}) })
    return () => { cancelled = true }
  }, [attempt])
  const data = result.data
  function metric(workflow: string, metric: string, direction?: string): string {
    return data?.monthly.totals.find(t => t.workflow === workflow && t.metric === metric && (!direction || t.direction === direction))?.value ?? (metric === 'Whitegoods count' ? '0' : '0.00')
  }
  function tally(type: string): string {
    return (data?.monthly.totals.filter(t => t.workflow === 'ELECTRICAL_TESTING' && t.metric === type).reduce((sum, t) => sum + BigInt(t.value), 0n) ?? 0n).toString()
  }
  return <section><div className="page-heading"><div><p className="eyebrow">ADMIN</p><h1>Overview</h1><p>Operational activity in Australia/Hobart. Quantities remain separated by workflow.</p></div><button className="secondary" onClick={() => setAttempt(n => n + 1)}>Refresh overview</button></div>
    {result.error && <p role="alert" className="notice error">{result.error}</p>}
    {!data && !result.error && <p role="status">Loading overview...</p>}
    {data && <><div className="metric-grid"><article className="panel"><h2>Submitted today</h2><strong>{data.submittedToday}</strong><p>Active operational records submitted on {data.today}.</p></article><article className="panel"><h2>Records this month</h2><strong>{data.recordsThisMonth}</strong><p>By operational record date; excludes sales references.</p></article></div>
      <section className="panel report-section"><h2>Workflow activity this month</h2><ul>{data.activity.map(a => <li key={a.workflow}>{label(a.workflow)}: {a.count}</li>)}</ul>{!data.activity.length && <p>No records this month.</p>}</section>
      <div className="metric-grid report-section"><article className="panel"><h2>Salvage recovered this month</h2><strong>{metric('SALVAGE', 'Net weight', 'DOWN')} kg</strong><p>Council-provided Down weight.</p></article><article className="panel"><h2>Landfill recorded this month</h2><strong>{metric('SALVAGE', 'Net weight', 'UP')} kg</strong><p>Council-provided Up weight.</p></article><article className="panel"><h2>Whitegoods this month</h2><strong>{metric('WHITEGOODS', 'Whitegoods count')}</strong><p>{metric('WHITEGOODS', 'Estimated weight')} kg estimated.</p></article></div>
      <section className="panel report-section"><h2>Electrical Testing this month</h2><p>Tally activity, not unique items or stock balances.</p><dl className="record-facts">{['UNTESTED_BROKEN_FAILED', 'TESTED_AND_PRICED', 'STOCK_CULL', 'DIVERTED_TO_OLS', 'DIVERTED_TO_COLL'].map(type => <div key={type}><dt>{label(type)}</dt><dd>{tally(type)}</dd></div>)}</dl></section>
      <section className="panel report-section"><details><summary>Monthly quantities by workflow and material</summary><p>Measured weights, category estimates and Whitegoods estimates are separate.</p><TotalsTable totals={data.monthly.totals}/></details></section>
      <section className="panel report-section"><h2>Recent submissions</h2><DetailTable rows={data.recent} onOpen={onOpen}/></section>
      <section className="panel report-section"><h2>Monthly workflow activity - six months</h2><div className="table-scroll"><table><thead><tr><th>Month</th><th>Workflow</th><th>Records</th></tr></thead><tbody>{data.trend.map(t => <tr key={t.month+t.workflow}><td>{t.month}</td><td>{label(t.workflow)}</td><td>{t.count}</td></tr>)}</tbody></table></div><p className="hint">Current month is partial. E-waste Pickup and Scrap are excluded.</p></section></>}
  </section>
}
