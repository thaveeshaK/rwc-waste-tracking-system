import { useEffect, useState } from 'react'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import { getMonthlySummary } from './api'
import type { MonthlySummary } from './types'
export function WhitegoodsSummary({ refreshToken, busy }: { refreshToken: number; busy: boolean }) {
  const [month, setMonth] = useState(() => operationalDate().slice(0, 7))
  const [result, setResult] = useState<{ key: string; summary?: MonthlySummary; error?: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const key = `${month}:${refreshToken}:${attempt}`
  const loading = result?.key !== key
  const summary = loading ? undefined : result?.summary
  const error = loading ? undefined : result?.error
  useEffect(() => {
    let cancelled = false
    getMonthlySummary(month).then(summary => { if (!cancelled) setResult({ key, summary }) })
      .catch(error => { if (!cancelled) setResult({ key, error: errorMessage(error) }) })
    return () => { cancelled = true }
  }, [month, key])
  return <section className="panel whitegoods-summary" aria-labelledby="whitegoods-summary-heading">
    <h2 id="whitegoods-summary-heading">Whitegoods monthly summary</h2>
    <p className="hint">Calendar-month totals include all active daily entries, independently of the record list date filter.</p>
    <label>Summary month<input type="month" required min="0001-01" max={operationalDate().slice(0, 7)} value={month} disabled={busy}
      onChange={event => { if (event.target.value) setMonth(event.target.value) }} /></label>
    {loading ? <p role="status">Loading monthly summary...</p> : error ? <><p className="notice error" role="alert">{error}</p>
      <button className="secondary" disabled={busy} onClick={() => setAttempt(value => value + 1)}>Retry monthly summary</button></>
      : summary && <dl className="record-facts" aria-live="polite"><div><dt>Calendar month</dt><dd>{summary.month}</dd></div>
        <div><dt>Total Whitegoods count</dt><dd>{summary.totalCount}</dd></div><div><dt>Monthly estimated weight</dt><dd>{summary.estimatedWeight} kg</dd></div></dl>}
  </section>
}
