import { getSettings } from '../settings/api'
import { useEffect, useState, useRef } from 'react'
import { request, download } from './api'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import type { Monthly } from './types'
import { LevyWorksheet } from './LevyWorksheet'
export function WasteLevySummaryPage() {
  const [month, setMonth] = useState(() => operationalDate().slice(0, 7))
  const defaultsApplied = useRef(false)
  const [tonnes, setTonnes] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{key: string; data: Monthly} | null>(null)
  const [error, setError] = useState('')
  const [exporting, setExporting] = useState(false)
  const key = month+':'+attempt
  const loading = result?.key !== key
  useEffect(() => {
    let cancelled = false
    Promise.all([request<Monthly>('/api/admin/waste-levy-summary?month='+month), getSettings()]).then(([data, defaults]) => { if (!cancelled) {setResult({key, data}); setError(''); if (!defaultsApplied.current) {setTonnes(defaults.displayUnit === 'tonnes'); defaultsApplied.current = true}} }).catch(e => { if (!cancelled) setError(errorMessage(e)) })
    return () => { cancelled = true }
  }, [key, month])
  async function exportFile(format: string) {
    setExporting(true); setError('')
    try { await download(`/api/admin/waste-levy-summary/export?month=${month}&format=${format}`) }
    catch (e) { setError(errorMessage(e)) } finally { setExporting(false) }
  }
  return <section><div className="page-heading"><div><p className="eyebrow">ADMIN</p><h1>Monthly Waste Levy Summary</h1><p>Monthly figures for manual entry into the existing workbook.</p></div></div>
    <p className="notice">Monthly working summary for manual entry into the existing Waste Levy workbook. Not a completed Waste Levy return, NRE reconciliation, or regulatory/certified report. E-waste Pickup and legacy E-waste reconciliation/carry-over calculations are excluded.</p>
    <div className="panel filter-bar"><label>Reporting month<input type="month" required min="0001-01" max="9998-12" value={month} onChange={e => {if (e.target.value) setMonth(e.target.value)}}/></label><label>Summary units<select value={tonnes ? 't' : 'kg'} onChange={e => setTonnes(e.target.value === 't')}><option value="kg">kg (two decimals)</option><option value="t">tonnes (five decimals)</option></select></label><button className="secondary" onClick={() => setAttempt(n => n+1)}>Refresh monthly figures</button><button className="secondary" disabled={loading || exporting} onClick={() => exportFile('csv')}>Export CSV</button><button className="secondary" disabled={loading || exporting} onClick={() => exportFile('xlsx')}>Export Excel</button></div>
    {error && <p className="notice error" role="alert">{error}</p>}
    {loading ? !error && <p role="status">Loading monthly figures...</p> : result && <><p className="hint">Material weights retain stored rounding and may differ slightly from measured totals. Exports use kg.</p><LevyWorksheet totals={result.data.totals} containerMaterials={result.data.containerMaterials} tonnes={tonnes}/></>}
  </section>
}
