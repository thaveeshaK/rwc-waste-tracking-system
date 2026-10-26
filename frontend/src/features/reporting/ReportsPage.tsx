import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { request, query, download } from './api'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import type { Filters, Summary, Details, Options, Row } from './types'
import { workflows } from './types'
import { DetailTable, TotalsTable } from './ReportTables'
function initial(): Filters {
  const today = operationalDate()
  return {from: today.slice(0, 7)+'-01', to: today, workflow: '', materialCategoryId: '', location: '', direction: '', testingCategoryId: ''}
}
function iso(date: Date) { return date.toISOString().slice(0, 10) }
export function ReportsPage({ onOpen }: {onOpen: (row: Row) => void}) {
  const [draft, setDraft] = useState(initial)
  const [applied, setApplied] = useState(initial)
  const [periodName, setPeriodName] = useState('custom')
  const [mode, setMode] = useState('summary')
  const [page, setPage] = useState(0)
  const [options, setOptions] = useState<Options>({materials: [], testingCategories: []})
  const [result, setResult] = useState<{key: string; summary: Summary; details: Details} | null>(null)
  const [error, setError] = useState('')
  const [exporting, setExporting] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [tonnes, setTonnes] = useState(false)
  const key = query(applied)+`:${page}:${attempt}`
  const loading = result?.key !== key
  const dirty = query(draft) !== query(applied)
  useEffect(() => {
    let cancelled = false
    request<Options>('/api/admin/reports/options').then(data => { if (!cancelled) setOptions(data) }).catch(e => { if (!cancelled) setError(errorMessage(e)) })
    return () => { cancelled = true }
  }, [])
  useEffect(() => {
    let cancelled = false
    Promise.all([request<Summary>('/api/admin/reports/summary?'+query(applied)), request<Details>('/api/admin/reports/records?'+query(applied)+`&page=${page}&size=50`)])
      .then(([summary, details]) => { if (!cancelled) { setResult({key, summary, details}); setError('') } })
      .catch(e => { if (!cancelled) setError(errorMessage(e)) })
    return () => { cancelled = true }
  }, [applied, page, key])
  function apply(event: FormEvent) { event.preventDefault(); setApplied({...draft}); setPage(0); setAttempt(n => n+1); setError('') }
  function period(value: string) {
    setPeriodName(value)
    if (!draft.from) return
    const day = new Date(draft.from+'T00:00:00Z')
    let start = new Date(day), end = new Date(day)
    if (value === 'weekly') { start.setUTCDate(day.getUTCDate() - (day.getUTCDay()+6)%7); end = new Date(start); end.setUTCDate(start.getUTCDate()+6) }
    if (value === 'monthly') { start.setUTCDate(1); end = new Date(Date.UTC(day.getUTCFullYear(), day.getUTCMonth()+1, 0)) }
    if (value === 'financial') { const year = day.getUTCFullYear() - (day.getUTCMonth()<6 ? 1 : 0); start = new Date(Date.UTC(year, 6, 1)); end = new Date(Date.UTC(year+1, 5, 30)) }
    if (value !== 'custom') setDraft({...draft, from: iso(start), to: iso(end)})
  }
  async function exportFile(format: string) {
    setExporting(true); setError('')
    try { await download('/api/admin/reports/export?'+query(applied)+`&report=${mode}&format=${format}`) }
    catch (e) { setError(errorMessage(e)) } finally { setExporting(false) }
  }
  const testing = draft.workflow === 'ELECTRICAL_TESTING'
  return <section><div className="page-heading"><div><p className="eyebrow">ADMIN</p><h1>Reports</h1><p>Active submitted records only. Operational totals are not certified diversion figures.</p></div></div>
    <form className="panel" onSubmit={apply}><div className="report-filters">
      <label>Reporting period<select value={periodName} onChange={e => period(e.target.value)}><option value="custom">Custom date range</option><option value="daily">Daily</option><option value="weekly">Weekly (Monday-Sunday)</option><option value="monthly">Monthly</option><option value="financial">Financial year (July-June)</option></select></label>
      <label>From date<input type="date" required min="0001-01-01" max="9998-12-31" value={draft.from} onChange={e => {setPeriodName('custom'); setDraft({...draft, from: e.target.value})}}/></label>
      <label>To date<input type="date" required min={draft.from} max="9998-12-31" value={draft.to} onChange={e => {setPeriodName('custom'); setDraft({...draft, to: e.target.value})}}/></label>
      <label>Report workflow<select value={draft.workflow} onChange={e => {setMode('summary'); setDraft({...draft, workflow: e.target.value, materialCategoryId: '', testingCategoryId: '', location: '', direction: ''})}}><option value="">All implemented workflows</option>{Object.entries(workflows).filter(([w]) => w !== 'GENERAL_INTAKE').map(([w, name]) => <option key={w} value={w}>{name}</option>)}</select></label>
      {!testing && draft.workflow !== 'WHITEGOODS' && <label>Material category<select value={draft.materialCategoryId} onChange={e => setDraft({...draft, materialCategoryId: e.target.value})}><option value="">All applicable materials</option>{options.materials.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>}
      {testing && <label>Electrical Testing category<select value={draft.testingCategoryId} onChange={e => setDraft({...draft, testingCategoryId: e.target.value})}><option value="">All categories and overall diversions</option>{options.testingCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>}
      {draft.workflow === 'PICKUP' && <label>Pickup location contains<input value={draft.location} maxLength={255} onChange={e => setDraft({...draft, location: e.target.value})}/></label>}
      {draft.workflow === 'SALVAGE' && <label>Salvage direction<select value={draft.direction} onChange={e => setDraft({...draft, direction: e.target.value})}><option value="">Up and Down separately</option><option value="UP">Up - landfill</option><option value="DOWN">Down - recovered</option></select></label>}
    </div><div className="actions"><button type="submit">Apply report filters</button><button type="button" className="secondary" onClick={() => setDraft(initial())}>Reset filters</button></div></form>
    {error && <p className="notice error" role="alert">{error}</p>}
    <div className="panel report-section"><p><strong>Applied period:</strong> {applied.from} to {applied.to} (Australia/Hobart)</p><p className="hint">Material filters show matching breakdown rows only. Record rows hold net/estimated weight; material and direction rows must not be added to their parent again. Sales references use the reporting month, including each month touched by this period; missing values are not zero.</p>
      <div className="actions"><label>Report view<select value={mode} onChange={e => setMode(e.target.value)}><option value="summary">Summary</option><option value="details">Detailed records</option><option value="electrical-testing" disabled={applied.workflow !== 'ELECTRICAL_TESTING'}>Electrical Testing KPI and references</option></select></label><label>Weight display<select value={tonnes ? 't' : 'kg'} onChange={e => setTonnes(e.target.value === 't')}><option value="kg">kg</option><option value="t">tonnes (summary)</option></select></label><button className="secondary" disabled={dirty || loading || exporting} onClick={() => exportFile('csv')}>Export CSV</button><button className="secondary" disabled={dirty || loading || exporting} onClick={() => exportFile('xlsx')}>Export Excel</button></div>
      {dirty && <p role="status">Filters changed. Apply report filters before exporting.</p>}
      {loading ? !error && <p role="status">Loading report...</p> : result && <>{mode === 'summary' ? <TotalsTable totals={result.summary.totals} tonnes={tonnes}/> : <>{mode === 'electrical-testing' && <TotalsTable totals={result.summary.totals}/>}<DetailTable rows={result.details.rows} onOpen={onOpen}/><div className="actions"><button className="secondary" disabled={page === 0} onClick={() => setPage(n => n-1)}>Previous report page</button><span>Rows {result.details.totalRows ? page*50+1 : 0}-{Math.min((page+1)*50, result.details.totalRows)} of {result.details.totalRows}</span><button className="secondary" disabled={(page+1)*50 >= result.details.totalRows} onClick={() => setPage(n => n+1)}>Next report page</button></div></>}<p className="hint">Generated {new Date(result.summary.generatedAt).toLocaleString('en-AU', {timeZone: 'Australia/Hobart'})}. Exports include all matching rows, not just this page, and use kg.</p></>}
    </div></section>
}
