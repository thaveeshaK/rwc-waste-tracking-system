import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { getMaterials, previewRecord, submitRecord, updateRecord } from './api'
import { operationalDate } from '../../utils/decimal'
import { PickupForm } from './PickupForm'
import { PickupReview } from './PickupReview'
import type { PickupInput, PickupRecord, MaterialCategory } from './types'

interface Props {
  editing?: PickupRecord
  onSaved: (record: PickupRecord) => void
  onCancel: () => void
  onBusyChange: (busy: boolean) => void
  onUnresolvedChange: (unresolved: boolean) => void
}

export function PickupPage({ editing, onSaved, onCancel, onBusyChange, onUnresolvedChange }: Props) {
  const [materials, setMaterials] = useState<MaterialCategory[]>([])
  const [input, setInput] = useState<PickupInput>(() => ({
    submissionId: editing?.submissionId ?? crypto.randomUUID(),
    version: editing?.version ?? undefined,
    recordDate: editing?.recordDate ?? operationalDate(), pickupLocation: editing?.pickupLocation ?? '',
    netWeight: editing?.netWeight ?? '', materials: [],
  }))
  const [review, setReview] = useState<PickupRecord | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const submitting = useRef(false)
  const pendingInput = useRef<PickupInput | null>(null)

  useEffect(() => {
    let cancelled = false
    getMaterials().then(materials => {
      if (cancelled) return
      if (editing) for (const row of editing.materials) {
        if (!materials.some(material => material.id === row.materialCategoryId)) {
          materials.push({ id: row.materialCategoryId, name: `${row.name} (inactive)` })
        }
      }
      setMaterials(materials)
      setInput(input => ({ ...input, materials: materials.map(material => ({ materialCategoryId: material.id,
        percentage: editing?.materials.find(row => row.materialCategoryId === material.id)?.percentage ?? '0.00' })) }))
    }).catch(error => { if (!cancelled) setError(errorMessage(error)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [editing, loadAttempt])

  async function calculate() {
    setBusy(true); setError('')
    onBusyChange(true)
    try { setReview(await previewRecord(input, editing?.id ?? undefined)) }
    catch (error) {
      setError(errorMessage(error))
      if (error instanceof ApiError && error.status === 409) setConflict(true)
    }
    finally { setBusy(false); onBusyChange(false) }
  }

  async function save() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true); setError('')
    onBusyChange(true)
    pendingInput.current ??= input
    try {
      const payload = pendingInput.current
      const saved = editing?.id ? await updateRecord(editing.id, payload) : await submitRecord(payload)
      onUnresolvedChange(false)
      onSaved(saved)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setConflict(true)
        setUncertain(false)
        pendingInput.current = null
        onUnresolvedChange(false)
        setError(`Conflict: ${errorMessage(error)} Open Records to check the saved record before continuing.`)
        return
      }
      // Preserve both payload and UUID when a response may have been lost.
      const unknownOutcome = uncertain || !(error instanceof ApiError) || error.status >= 500
      setUncertain(unknownOutcome)
      onUnresolvedChange(unknownOutcome)
      if (!unknownOutcome) pendingInput.current = null
      setError(`${errorMessage(error)}${unknownOutcome ? ' Retry this submission or check Records before starting another.' : ''}`)
    } finally { submitting.current = false; setBusy(false); onBusyChange(false) }
  }

  return <section aria-labelledby="pickup-heading">
    <div className="page-heading"><div><p className="eyebrow">COLLECTED MATERIALS</p>
      <h1 id="pickup-heading">{editing ? `Correct record #${editing.id}` : 'Pickup'}</h1>
      <p>{review ? 'Review the calculated weights before confirming.' : 'Record one pickup using its final net material weight.'}</p></div>
      <span className="step-label">{review ? '2 - Review' : '1 - Enter details'}</span></div>
    <div className="panel">
      {error && <p className="notice error" role="alert">{error}</p>}
      {loading ? <p role="status">Loading Pickup materials...</p> : materials.length === 0
        ? <div><p>Reference data could not be loaded.</p><button onClick={() => { setLoading(true); setError(''); setLoadAttempt(loadAttempt + 1) }}>Retry</button></div>
        : conflict ? <p>Open Records and reload the record. This request will not be retried automatically.</p>
        : review ? <><PickupReview record={review} />
          <div className="actions"><button disabled={busy} onClick={save}>{busy ? 'Saving...' : uncertain ? 'Retry submission' : editing ? 'Confirm correction' : 'Confirm and submit'}</button>
            <button className="secondary" disabled={busy || uncertain} onClick={() => { setReview(null); setError('') }}>Back to edit</button>
          </div></>
        : <PickupForm value={input} materials={materials} today={operationalDate()}
          busy={busy} onChange={setInput} onReview={calculate} />}
      <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain ? 'Check Records' : 'Back to Records'}</button>
    </div>
  </section>
}
