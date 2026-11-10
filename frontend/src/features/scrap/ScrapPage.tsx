import { RecordHistory } from '../../components/RecordHistory'
import { useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { previewRecord, submitRecord, updateRecord } from './api'
import { operationalDate } from '../../utils/decimal'
import { ScrapForm } from './ScrapForm'
import { ScrapReview } from './ScrapReview'
import type { ScrapInput, ScrapRecord, ScrapPreview } from './types'

interface Props {
  editing?: ScrapRecord
  onSaved: (record: ScrapRecord) => void
  onCancel: () => void
  onBusyChange: (busy: boolean) => void
  onUnresolvedChange: (unresolved: boolean) => void
}

export function ScrapPage({ editing, onSaved, onCancel, onBusyChange, onUnresolvedChange }: Props) {
  const [input, setInput] = useState<ScrapInput>(() => ({
    submissionId: editing?.submissionId ?? crypto.randomUUID(),
    version: editing?.version ?? undefined,
    reportingMonth: editing?.reportingMonth ?? operationalDate().slice(0,7),
    ironAndSteelWeight: editing?.ironAndSteelWeight ?? '0',
    aluminiumWeight: editing?.aluminiumWeight ?? '0',
    otherNonFerrousWeight: editing?.otherNonFerrousWeight ?? '0',
    mixedMetalsWeight: editing?.mixedMetalsWeight ?? '0',
    ewasteWeight: editing?.ewasteWeight ?? '0',

  }))
  const [review, setReview] = useState<ScrapPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [conflict, setConflict] = useState(false)
  const submitting = useRef(false)
  const pendingInput = useRef<ScrapInput | null>(null)

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

  return <section aria-labelledby="scrap-heading">
    <div className="page-heading"><div><p className="eyebrow">SCRAP</p>
      <h1 id="scrap-heading">{editing ? `Correct record #${editing.id}` : 'Scrap'}</h1>
      <p>{review ? 'Review the monthly breakdown before confirming.' : 'Record monthly Scrap material weights. Each submission adds to the selected month.'}</p></div>
      <span className="step-label">{review ? '2 - Review' : '1 - Enter details'}</span></div>
    <div className="panel">
      {error && <p className="notice error" role="alert">{error}</p>}
      {conflict ? <p>Open Records and reload the record. This request will not be retried automatically.</p>
        : review ? <><ScrapReview record={review.entry} preview={review} />
          <div className="actions"><button disabled={busy} onClick={save}>{busy ? 'Saving...' : uncertain ? 'Retry submission' : editing ? 'Confirm correction' : 'Confirm and submit'}</button>
            <button className="secondary" disabled={busy || uncertain} onClick={() => { setReview(null); setError('') }}>Back to edit</button>
          </div></>
        : <ScrapForm value={input}
          busy={busy} onChange={setInput} onReview={calculate} />}
      <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain ? 'Check Records' : 'Back to Records'}</button>
    </div>
  <RecordHistory record={editing}/>
  </section>
}
