import { RecordHistory } from '../../components/RecordHistory'
import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { getContainers, previewRecord, submitRecord, updateRecord } from './api'
import { operationalDate } from '../../utils/decimal'
import { EwastePickupForm } from './EwastePickupForm'
import { EwastePickupReview } from './EwastePickupReview'
import type { EwastePickupInput, EwastePickupRecord, ContainerType } from './types'

interface Props {
  editing?: EwastePickupRecord
  onSaved: (record: EwastePickupRecord) => void
  onCancel: () => void
  onBusyChange: (busy: boolean) => void
  onUnresolvedChange: (unresolved: boolean) => void
}

export function EwastePickupPage({ editing, onSaved, onCancel, onBusyChange, onUnresolvedChange }: Props) {
  const [containers, setContainers] = useState<ContainerType[]>([])
  const [input, setInput] = useState<EwastePickupInput>(() => ({
    submissionId: editing?.submissionId ?? crypto.randomUUID(),
    version: editing?.version ?? undefined,
    recordDate: editing?.recordDate ?? operationalDate(), organisation: editing?.organisation ?? '',
    enteredWeight: editing?.enteredWeight ?? '', containers: editing?.containers.map(c=>({containerTypeId:c.containerTypeId,quantity:c.quantity})) ?? [],
  }))
  const [review, setReview] = useState<EwastePickupRecord | null>(null)
  const [referenceError, setReferenceError] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const submitting = useRef(false)
  const pendingInput = useRef<EwastePickupInput | null>(null)

  useEffect(() => {
    let cancelled = false
    getContainers().then(rows => {
      if(cancelled)return
      for(const line of editing?.containers??[]) {
        const index=rows.findIndex(c=>c.id===line.containerTypeId)
        const snapshot={id:line.containerTypeId,name:line.name,tareWeight:line.tarePerUnit}
        if(index<0)rows.push(snapshot);else rows[index]=snapshot
      }
      setContainers(rows)
    }).catch(error => { if (!cancelled) {setReferenceError(true);setError(errorMessage(error))} })
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

  return <section aria-labelledby="ewaste-pickup-heading">
    <div className="page-heading"><div><p className="eyebrow">E-WASTE PICKUP</p>
      <h1 id="ewaste-pickup-heading">{editing ? `Correct record #${editing.id}` : 'E-waste Pickup'}</h1>
      <p>{review ? 'Review the calculated weights before confirming.' : 'Record E-waste collected from an organisation. This is intake, separate from internal electrical processing.'}</p></div>
      <span className="step-label">{review ? '2 - Review' : '1 - Enter details'}</span></div>
    <div className="panel">
      {error && <p className="notice error" role="alert">{error}</p>}
      {loading ? <p role="status">Loading E-waste Pickup containers...</p> : referenceError
        ? <div><p>Reference data could not be loaded.</p><button onClick={() => { setLoading(true); setReferenceError(false); setError(''); setLoadAttempt(loadAttempt + 1) }}>Retry</button></div>
        : conflict ? <p>Open Records and reload the record. This request will not be retried automatically.</p>
        : review ? <><EwastePickupReview record={review} />
          <div className="actions"><button disabled={busy} onClick={save}>{busy ? 'Saving...' : uncertain ? 'Retry submission' : editing ? 'Confirm correction' : 'Confirm and submit'}</button>
            <button className="secondary" disabled={busy || uncertain} onClick={() => { setReview(null); setError('') }}>Back to edit</button>
          </div></>
        : <EwastePickupForm value={input} containers={containers} today={operationalDate()}
          busy={busy} onChange={setInput} onReview={calculate} />}
      <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain ? 'Check Records' : 'Back to Records'}</button>
    </div>
  <RecordHistory record={editing}/>
  </section>
}
