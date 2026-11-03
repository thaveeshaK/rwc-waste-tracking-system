import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { operationalDate, decimalHundredths } from '../../utils/decimal'
import { getMaterials, previewRecord, submitRecord, updateRecord } from './api'
import { SalvageForm } from './SalvageForm'
import { SalvageReview } from './SalvageReview'
import type { MaterialCategory, SalvageInput, SalvageRecord } from './types'

interface Props {
  editing?: SalvageRecord
  onSaved: (record: SalvageRecord) => void
  onCancel: () => void
  onBusyChange: (busy: boolean) => void
  onUnresolvedChange: (unresolved: boolean) => void
}

export function SalvagePage({ editing, onSaved, onCancel, onBusyChange, onUnresolvedChange }: Props) {
  const [input, setInput] = useState<SalvageInput>(() => ({
    submissionId: editing?.submissionId ?? crypto.randomUUID(), version: editing?.version ?? undefined,
    recordDate: editing?.recordDate ?? operationalDate(), crew: editing?.crew ?? '',
    up: { time: editing?.up.time.slice(0, 5) ?? '', kg: editing?.up.kg ?? '', materials: [] },
    down: { time: editing?.down.time.slice(0, 5) ?? '', kg: editing?.down.kg ?? '', materials: [] },
  }))
  const [upMaterials, setUpMaterials] = useState<MaterialCategory[]>([])
  const [downMaterials, setDownMaterials] = useState<MaterialCategory[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [entryStep, setEntryStep] = useState<1 | 2>(1)
  const [review, setReview] = useState<SalvageRecord | null>(null)
  const submitting = useRef(false)
  const pending = useRef<SalvageInput | null>(null)

  useEffect(() => {
    let cancelled = false
    Promise.all([getMaterials('UP'), getMaterials('DOWN')]).then(([up, down]) => {
      if (cancelled) return
      for (const [categories, historical] of [[up, editing?.up.materials], [down, editing?.down.materials]] as const) {
        for (const row of historical ?? []) {
          if (!categories.some(category => category.id === row.materialCategoryId)) categories.push({ id: row.materialCategoryId, name: `${row.name} (inactive)` })
        }
      }
      const rows = (categories: MaterialCategory[], side: 'up' | 'down') => categories.map(category => ({ materialCategoryId: category.id,
        percentage: editing?.[side].materials.find(row => row.materialCategoryId === category.id)?.percentage ?? '0.00' }))
      setUpMaterials(up); setDownMaterials(down)
      setInput(value => ({ ...value, up: { ...value.up, materials: rows(up, 'up') }, down: { ...value.down, materials: rows(down, 'down') } }))
    }).catch(error => { if (!cancelled) setError(errorMessage(error)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [editing, attempt])

  function payload(): SalvageInput {
    return { ...input, crew: input.crew.trim(),
      up: { ...input.up, materials: decimalHundredths(input.up.kg) === 0n ? [] : input.up.materials },
      down: { ...input.down, materials: decimalHundredths(input.down.kg) === 0n ? [] : input.down.materials } }
  }

  async function calculate() {
    setBusy(true); setError(''); onBusyChange(true)
    try { setReview(await previewRecord(payload(), editing?.id ?? undefined)) }
    catch (error) { setError(errorMessage(error)); if (error instanceof ApiError && error.status === 409) setConflict(true) }
    finally { setBusy(false); onBusyChange(false) }
  }

  async function save() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true); setError(''); onBusyChange(true)
    pending.current ??= payload()
    try {
      const saved = editing?.id ? await updateRecord(editing.id, pending.current) : await submitRecord(pending.current)
      onUnresolvedChange(false); onSaved(saved)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setConflict(true); setUncertain(false); pending.current = null; onUnresolvedChange(false)
        setError(`Conflict: ${errorMessage(error)} Open Records to inspect the saved trip before continuing.`)
      } else {
        const unknownOutcome = uncertain || !(error instanceof ApiError) || error.status >= 500
        setUncertain(unknownOutcome); onUnresolvedChange(unknownOutcome)
        if (!unknownOutcome) pending.current = null
        setError(`${errorMessage(error)}${unknownOutcome ? ' Retry this submission or check Records before starting another.' : ''}`)
      }
    } finally { submitting.current = false; setBusy(false); onBusyChange(false) }
  }

  const step = review ? 3 : entryStep
  function backTo(target: 1 | 2) {
    if (busy || uncertain || conflict) return
    setReview(null); setEntryStep(target); setError('')
  }

  return <section className="salvage-entry" aria-labelledby="salvage-heading"><div className="page-heading"><div><p className="eyebrow">SALVAGE TRIP</p>
    <h1 id="salvage-heading">{editing ? `Correct Salvage trip #${editing.id}` : 'Salvage'}</h1>
    <p>{review ? 'Review both directions before confirming.' : 'Record one trip using the final council weighbridge weights.'}</p></div>
    </div>
    <ol className="salvage-steps" aria-label="Salvage trip steps">{['Salvage Up', 'Salvage Down', 'Review'].map((label, index) => <li key={label}>
      {index < 2 && index+1 < step ? <button type="button" className="step-label completed" disabled={busy || uncertain || conflict} onClick={() => backTo(index === 0 ? 1 : 2)}>{index+1} - {label}<span className="sr-only"> (completed)</span></button>
        : <span className={'step-label'+(index+1 === step ? ' current' : '')} aria-current={index+1 === step ? 'step' : undefined}>{index+1} - {label}</span>}
    </li>)}</ol>
    <div className="panel">{error && <p className="notice error" role="alert">{error}</p>}
      {loading ? <p role="status">Loading Salvage material categories...</p>
        : upMaterials.length === 0 || downMaterials.length === 0 ? <><p>Material categories could not be loaded.</p>
          <button onClick={() => { setLoading(true); setError(''); setAttempt(attempt + 1) }}>Retry</button></>
        : conflict ? <p>Open Records and reload the trip. This request will not be retried automatically.</p>
        : review ? <><SalvageReview record={review} showTotals /><div className="actions">
          <button disabled={busy} onClick={save}>{busy ? 'Saving...' : uncertain ? 'Retry submission' : editing ? 'Confirm correction' : 'Confirm and submit'}</button>
          <button className="secondary" disabled={busy || uncertain} onClick={() => backTo(2)}>Back to edit</button></div></>
        : <SalvageForm value={input} upMaterials={upMaterials} downMaterials={downMaterials} today={operationalDate()}
          busy={busy} step={entryStep} onChange={setInput} onContinue={() => {setEntryStep(2); setError('')}} onBack={() => backTo(1)} onReview={calculate} />}
      <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain ? 'Check Records' : 'Back to Records'}</button>
    </div></section>
}
