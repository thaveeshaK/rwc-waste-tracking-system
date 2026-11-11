import { useState } from 'react'
import type { FormEvent } from 'react'
import { saveReference } from './api'
import type { Reference, ReferenceInput } from './types'
import { containerContexts, materialContexts } from './types'
import { errorMessage } from '../../api/client'
interface Props { container: boolean; rows: Reference[]; onSaved: () => Promise<void> }
export function ReferenceSettings({container, rows, onSaved}: Props) {
  const [editing, setEditing] = useState<{id?: number; input: ReferenceInput} | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const title = container ? 'Container Types' : 'Material Categories'
  const contexts = container ? containerContexts : materialContexts
  function edit(row?: Reference) {
    setError(''); setMessage(''); setEditing({id: row?.id, input: row ? {...row, contexts: [...row.contexts]} : {name: '', tareWeight: container ? '0.00' : null, active: true, contexts: [], summaryGroup: null}})
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (!editing) return
    setBusy(true); setError('')
    try { await saveReference(container, editing.input, editing.id); await onSaved(); setEditing(null); setMessage('Configuration saved. Historical records are unchanged.') }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  function patch(input: Partial<ReferenceInput>) { if (editing) setEditing({...editing, input: {...editing.input, ...input}}) }
  return <section className="panel report-section" aria-label={title}><div className="detail-heading"><h2>{title}</h2><button className="secondary" disabled={busy} onClick={() => edit()}>Add {container ? 'container type' : 'material category'}</button></div>
    <p className="hint">{container ? 'Changes to tare and display names apply to future entries. Historical container names and tares are retained. Summary group changes apply to future entries. Historical records retain their original summary group.' : 'Category names are immutable. Add a replacement and deactivate the old category if its meaning changes. The two electrical weight categories are fixed; Electrical Testing categories are not managed here.'} Deactivation replaces deletion.</p>
    {error && <p className="notice error" role="alert">{error}</p>}{message && <p className="notice success" role="status">{message}</p>}
    {editing && <form className="settings-editor" onSubmit={save}><fieldset disabled={busy}><legend>{editing.id ? 'Edit configuration' : 'New configuration'}</legend><div className="form-grid">
      <label>{container ? 'Container name' : 'Category name'}<input required maxLength={100} value={editing.input.name} readOnly={!container && !!editing.id} onChange={e => patch({name: e.target.value})}/></label>
      {container && <label>Tare weight (kg)<input type="number" inputMode="decimal" required min="0" max="99999999.99" step="0.01" value={editing.input.tareWeight ?? ''} onChange={e => patch({tareWeight: e.target.value})}/></label>}
      {container && <label>Waste Levy summary group<select required={editing.input.contexts.includes('CRATE_PALLET') || editing.input.contexts.includes('ELECTRICAL_CRATE_PALLET')} value={editing.input.summaryGroup ?? ''} onChange={e => patch({summaryGroup: (e.target.value || null) as ReferenceInput['summaryGroup']})}><option value="">Select group</option><option value="CRATE">Crate</option><option value="PALLET">Pallet</option><option value="OTHER">Other</option></select></label>}
      <label>Status<select value={editing.input.active ? 'active' : 'inactive'} onChange={e => patch({active: e.target.value === 'active'})}><option value="active">Active</option><option value="inactive">Inactive</option></select></label>
    </div><fieldset className="settings-contexts"><legend>Workflow applicability</legend>{contexts.map(c => <label key={c}><input type="checkbox" checked={editing.input.contexts.includes(c)} onChange={e => patch({contexts: e.target.checked ? [...editing.input.contexts, c] : editing.input.contexts.filter(v => v !== c)})}/>{c.replaceAll('_', ' ')}</label>)}</fieldset>
    {editing.input.active && !editing.input.contexts.length && <p className="hint">Select at least one workflow for an active reference.</p>}
    <div className="actions"><button disabled={busy || (editing.input.active && !editing.input.contexts.length)}>Save {container ? 'container type' : 'material category'}</button><button type="button" className="secondary" onClick={() => setEditing(null)}>Cancel edit</button></div></fieldset></form>}
    <div className="table-scroll"><table><thead><tr><th>Name</th>{container && <><th>Tare (kg)</th><th>Summary group</th></>}<th>Status</th><th>Applicable workflows</th><th>Action</th></tr></thead><tbody>{rows.map(row => <tr key={row.id}><td>{row.name}</td>{container && <><td>{row.tareWeight}</td><td>{row.summaryGroup ? {CRATE: 'Crate', PALLET: 'Pallet', OTHER: 'Other'}[row.summaryGroup] : '?'}</td></>}<td>{row.active ? 'Active' : 'Inactive'}</td><td>{row.contexts.join(', ') || 'None'}</td><td>{row.restricted ? 'Fixed electrical categories' : <button className="text-button" disabled={busy} onClick={() => edit(row)}>Edit {row.name}</button>}</td></tr>)}</tbody></table></div>
  </section>
}
