import { useEffect, useState } from 'react'
import { request, download } from './api'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import type { Monthly } from './types'
import { TotalsTable } from './ReportTables'
export function WasteLevySummaryPage() {
  const [month, setMonth] = useState(() => operationalDate().slice(0, 7))
  const [tonnes, setTonnes] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{key: string; data: Monthly} | null>(null)
  const [error, setError] = useState('')
  const [exporting, setExporting] = useState(false)
  const key = month+':'+attempt
  const loading = result?.key !== key
  useEffect(() => {
    let cancelled = false
    request<Monthly>('/api/admin/waste-levy-summary?month='+month).then(data => { if (!cancelled) {setResult({key, data}); setError('')} }).catch(e => { if (!cancelled) setError(errorMessage(e)) })
    return () => { cancelled = true }
  }, [key, month])
  async function exportFile(format: string) {
    setExporting(true); setError('')
    try { await download(`/api/admin/waste-levy-summary/export?month=${month}&format=${format}`) }
    catch (e) { setError(errorMessage(e)) } finally { setExporting(false) }
  }
  const sections = [
    ['OWF / Salvage Down', (w: string, d: string | null) => w === 'SALVAGE' && d === 'DOWN'],
    ['MSW / general intake', (w: string) => ['CRATE_PALLET', 'COLLECTABLES', 'PICKUP'].includes(w)],
    ['Combined general intake categories', (w: string) => w === 'GENERAL_INTAKE'],
    ['RTL / Salvage Up', (w: string, d: string | null) => w === 'SALVAGE' && d === 'UP'],
    ['Electrical Crate / Pallet - operational', (w: string) => w === 'ELECTRICAL_CRATE_PALLET'],
    ['Whitegoods - estimated', (w: string) => w === 'WHITEGOODS'],
  ] as const
  return <section><div className="page-heading"><div><p className="eyebrow">ADMIN</p><h1>Monthly Waste Levy Summary</h1><p>Monthly figures for manual entry into the existing workbook.</p></div></div>
    <p className="notice">Working aid only. Not a completed Waste Levy return, NRE reconciliation or regulatory/certified report. E-waste Pickup and Scrap are currently excluded. Legacy E-waste reconciliation/carry-over calculations are excluded.</p>
    <div className="panel filter-bar"><label>Reporting month<input type="month" required min="0001-01" max="9998-12" value={month} onChange={e => {if (e.target.value) setMonth(e.target.value)}}/></label><label>Summary units<select value={tonnes ? 't' : 'kg'} onChange={e => setTonnes(e.target.value === 't')}><option value="kg">kg (two decimals)</option><option value="t">tonnes (five decimals)</option></select></label><button className="secondary" onClick={() => setAttempt(n => n+1)}>Refresh monthly figures</button><button className="secondary" disabled={loading || exporting} onClick={() => exportFile('csv')}>Export CSV</button><button className="secondary" disabled={loading || exporting} onClick={() => exportFile('xlsx')}>Export Excel</button></div>
    {error && <p className="notice error" role="alert">{error}</p>}
    {loading ? !error && <p role="status">Loading monthly figures...</p> : result && <><p className="hint">Measured net/direction weights and rounded material-category weights are separate totals. Small rounding differences are retained. Per-container subtotals do not assign Stillage Cage to a legacy Crates/Pallets group. Exports use kg.</p>{sections.map(([title, match]) => <section className="panel report-section" key={title}><h2>{title}</h2><TotalsTable totals={result.data.totals.filter(t => match(t.workflow, t.direction))} tonnes={tonnes}/></section>)}</>}
  </section>
}
