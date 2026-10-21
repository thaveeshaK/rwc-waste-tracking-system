import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { getContainers, getMaterials, previewRecord, submitRecord, updateRecord } from './api'
import { operationalDate } from './decimal'
import { CratePalletForm } from './CratePalletForm'
import { CratePalletReview } from './CratePalletReview'
import type { ContainerType, CratePalletInput, CratePalletRecord, MaterialCategory } from './types'

interface Props { editing?: CratePalletRecord; onSaved: (record: CratePalletRecord) => void; onCancel: () => void }

export function CratePalletPage({ editing, onSaved, onCancel }: Props) {
  const [containers, setContainers] = useState<ContainerType[]>([])
  const [materials, setMaterials] = useState<MaterialCategory[]>([])
  const [input, setInput] = useState<CratePalletInput>(() => ({
    submissionId: editing?.submissionId ?? crypto.randomUUID(),
    recordDate: editing?.recordDate ?? operationalDate(), containerTypeId: editing?.containerTypeId ?? 0,
    grossWeight: editing?.grossWeight ?? '', materials: [],
  }))
  const [review, setReview] = useState<CratePalletRecord | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const submitting = useRef(false)
  const pendingInput = useRef<CratePalletInput | null>(null)

  useEffect(() => {
    let cancelled = false
    Promise.all([getContainers(), getMaterials()]).then(([containers, materials]) => {
      if (cancelled) return
      // Retain inactive historical references when correcting an existing record.
      if (editing && !containers.some(container => container.id === editing.containerTypeId)) {
        containers.push({ id: editing.containerTypeId, name: `${editing.containerTypeName} (inactive)`, tareWeight: editing.tareWeight })
      } else if (editing) {
        containers = containers.map(container => container.id === editing.containerTypeId
          ? { ...container, tareWeight: editing.tareWeight } : container)
      }
      if (editing) for (const row of editing.materials) {
        if (!materials.some(material => material.id === row.materialCategoryId)) {
          materials.push({ id: row.materialCategoryId, name: `${row.name} (inactive)` })
        }
      }
      setContainers(containers); setMaterials(materials)
      setInput(input => ({ ...input, materials: materials.map(material => ({ materialCategoryId: material.id,
        percentage: editing?.materials.find(row => row.materialCategoryId === material.id)?.percentage ?? '0.00' })) }))
    }).catch(error => { if (!cancelled) setError(errorMessage(error)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [editing, loadAttempt])

  async function calculate() {
    setBusy(true); setError('')
    try { setReview(await previewRecord(input, editing?.id ?? undefined)) }
    catch (error) { setError(errorMessage(error)) }
    finally { setBusy(false) }
  }

  async function save() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true); setError('')
    pendingInput.current ??= input
    try {
      const payload = pendingInput.current
      const saved = editing?.id ? await updateRecord(editing.id, payload) : await submitRecord(payload)
      onSaved(saved)
    } catch (error) {
      // Preserve both payload and UUID when a response may have been lost.
      const unknownOutcome = !(error instanceof ApiError) || error.status >= 500 || error.status === 409
      setUncertain(unknownOutcome)
      if (!unknownOutcome) pendingInput.current = null
      setError(`${errorMessage(error)}${unknownOutcome ? ' Retry this submission or check Records before starting another.' : ''}`)
    } finally { submitting.current = false; setBusy(false) }
  }

  return <section aria-labelledby="entry-heading">
    <div className="page-heading"><div><p className="eyebrow">INCOMING MATERIALS</p>
      <h1 id="entry-heading">{editing ? `Correct record #${editing.id}` : 'Crate / Pallet'}</h1>
      <p>{review ? 'Review the calculated weights before confirming.' : 'Record one weighed container and its material breakdown.'}</p></div>
      <span className="step-label">{review ? '2 · Review' : '1 · Enter details'}</span></div>
    <div className="panel">
      {error && <p className="notice error" role="alert">{error}</p>}
      {loading ? <p role="status">Loading container types and materials…</p> : materials.length === 0 || containers.length === 0
        ? <div><p>Reference data could not be loaded.</p><button onClick={() => { setLoading(true); setError(''); setLoadAttempt(loadAttempt + 1) }}>Retry</button></div>
        : review ? <><CratePalletReview record={review} />
          <div className="actions"><button disabled={busy} onClick={save}>{busy ? 'Saving…' : uncertain ? 'Retry submission' : editing ? 'Confirm correction' : 'Confirm and submit'}</button>
            <button className="secondary" disabled={busy || uncertain} onClick={() => { setReview(null); setError('') }}>Back to edit</button>
          </div></>
        : <CratePalletForm value={input} containers={containers} materials={materials} today={operationalDate()}
          busy={busy} onChange={setInput} onReview={calculate} />}
      <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain ? 'Check Records' : 'Back to Records'}</button>
    </div>
  </section>
}
