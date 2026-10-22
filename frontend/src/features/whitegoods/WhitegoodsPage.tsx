import { useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { previewRecord, submitRecord, updateRecord } from './api'
import { operationalDate } from '../../utils/decimal'
import { WhitegoodsForm } from './WhitegoodsForm'
import { WhitegoodsReview } from './WhitegoodsReview'
import type { WhitegoodsInput, WhitegoodsRecord } from './types'

interface Props {
  editing?: WhitegoodsRecord
  onSaved: (record: WhitegoodsRecord) => void
  onCancel: () => void
  onBusyChange: (busy: boolean) => void
  onUnresolvedChange: (unresolved: boolean) => void
}

export function WhitegoodsPage({ editing, onSaved, onCancel, onBusyChange, onUnresolvedChange }: Props) {
  const [input, setInput] = useState<Omit<WhitegoodsInput, 'count'> & { count: string }>(() => ({
    submissionId: editing?.submissionId ?? crypto.randomUUID(), version: editing?.version ?? undefined,
    recordDate: editing?.recordDate ?? operationalDate(), count: editing ? String(editing.count) : '',
  }))
  const [review, setReview] = useState<WhitegoodsRecord | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [conflict, setConflict] = useState(false)
  const submitting = useRef(false)
  const pendingInput = useRef<WhitegoodsInput | null>(null)

  function payload(): WhitegoodsInput { return { ...input, count: Number(input.count) } }

  async function calculate() {
    setBusy(true); setError('')
    onBusyChange(true)
    try { setReview(await previewRecord(payload(), editing?.id ?? undefined)) }
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
    pendingInput.current ??= payload()
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

  return <section aria-labelledby="whitegoods-heading">
    <div className="page-heading"><div><p className="eyebrow">WHITEGOODS DAILY ENTRY</p>
      <h1 id="whitegoods-heading">{editing ? `Correct record #${editing.id}` : 'Whitegoods'}</h1>
      <p>{review ? 'Review the calculated weights before confirming.' : 'Record the Whitegoods received for this entry. Multiple entries on the same date are allowed.'}</p></div>
      <span className="step-label">{review ? '2 - Review' : '1 - Enter details'}</span></div>
    <div className="panel">
      {error && <p className="notice error" role="alert">{error}</p>}
      {conflict ? <p>Open Records and reload the record. This request will not be retried automatically.</p>
        : review ? <><WhitegoodsReview record={review} />
          <div className="actions"><button disabled={busy} onClick={save}>{busy ? 'Saving...' : uncertain ? 'Retry submission' : editing ? 'Confirm correction' : 'Confirm and submit'}</button>
            <button className="secondary" disabled={busy || uncertain} onClick={() => { setReview(null); setError('') }}>Back to edit</button>
          </div></>
        : <WhitegoodsForm value={input} today={operationalDate()}
          busy={busy} onChange={setInput} onReview={calculate} />}
      <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain ? 'Check Records' : 'Back to Records'}</button>
    </div>
  </section>
}
