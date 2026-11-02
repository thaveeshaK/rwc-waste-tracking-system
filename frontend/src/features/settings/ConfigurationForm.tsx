import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Configuration } from './types'
import { errorMessage } from '../../api/client'
interface Props { kind: 'organisation' | 'reporting' | 'whitegoods'; configuration: Configuration; onSave: (patch: Partial<Configuration>) => Promise<void> }
export function ConfigurationForm({kind, configuration, onSave}: Props) {
  const [draft, setDraft] = useState(configuration)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const title = kind === 'organisation' ? 'Organisation Details' : kind === 'reporting' ? 'Reporting Defaults' : 'Whitegoods Weight Factor'
  async function save(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setMessage('')
    const patch = kind === 'organisation' ? {organisationName: draft.organisationName, siteName: draft.siteName, reportDisplayName: draft.reportDisplayName}
      : kind === 'reporting' ? {displayUnit: draft.displayUnit, reportPeriod: draft.reportPeriod, financialYearStartMonth: draft.financialYearStartMonth}
      : {whitegoodsKgPerItem: draft.whitegoodsKgPerItem}
    try { await onSave(patch); setMessage('Settings saved successfully.') } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <section className="panel report-section" aria-label={title}><h2>{title}</h2><form onSubmit={save}><fieldset disabled={busy}><legend className="sr-only">{title}</legend>
    {kind === 'organisation' ? <div className="form-grid"><label>Organisation name<input required maxLength={100} value={draft.organisationName} onChange={e => setDraft({...draft, organisationName: e.target.value})}/></label><label>Site display name (optional)<input maxLength={100} value={draft.siteName} onChange={e => setDraft({...draft, siteName: e.target.value})}/></label><label>Report/export display name<input required maxLength={100} value={draft.reportDisplayName} onChange={e => setDraft({...draft, reportDisplayName: e.target.value})}/></label></div>
      : kind === 'reporting' ? <><div className="form-grid"><label>Default display unit<select value={draft.displayUnit} onChange={e => setDraft({...draft, displayUnit: e.target.value as Configuration['displayUnit']})}><option value="kg">kg</option><option value="tonnes">tonnes</option></select></label><label>Default report period<select value={draft.reportPeriod} onChange={e => setDraft({...draft, reportPeriod: e.target.value as Configuration['reportPeriod']})}><option value="CURRENT_MONTH">Current month</option><option value="CURRENT_FINANCIAL_YEAR">Current financial year</option></select></label><label>Financial year start month<select value={draft.financialYearStartMonth} onChange={e => setDraft({...draft, financialYearStartMonth: Number(e.target.value)})}>{Array.from({length: 12}, (_, i) => <option key={i+1} value={i+1}>{new Intl.DateTimeFormat('en-AU', {month: 'long', timeZone: 'UTC'}).format(new Date(Date.UTC(2020, i, 1)))}</option>)}</select></label></div><p className="hint">Operational timezone is fixed: Australia/Hobart. Defaults change initial selections only; they never change stored weights or report formulas.</p></>
      : <><label>Estimated kg per Whitegood<input required type="number" inputMode="decimal" min="0.01" max="999.99" step="0.01" value={draft.whitegoodsKgPerItem} onChange={e => setDraft({...draft, whitegoodsKgPerItem: e.target.value})}/></label><p className="hint">Applies to future submissions only. Existing entries and corrections retain their historical applied factor. Monthly estimated weights sum those stored estimates.</p></>}
    <div className="actions"><button>Save {title.toLowerCase()}</button></div></fieldset></form>{error && <p className="notice error" role="alert">{error}</p>}{message && <p role="status" className="notice success">{message}</p>}</section>
}
