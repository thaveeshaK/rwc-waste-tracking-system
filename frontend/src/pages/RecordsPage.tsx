import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, errorMessage } from '../api/client'
import { deleteRecord, getRecord, getRecords } from '../features/crate-pallet/api'
import { CratePalletReview } from '../features/crate-pallet/CratePalletReview'
import type { CratePalletRecord } from '../features/crate-pallet/types'

interface Props {
  isAdmin: boolean
  saved?: CratePalletRecord
  onEdit: (record: CratePalletRecord) => void
  onBusyChange: (busy: boolean) => void
}

export function RecordsPage({ isAdmin, saved, onEdit, onBusyChange }: Props) {
  const [records, setRecords] = useState<CratePalletRecord[]>([])
  const [selected, setSelected] = useState<CratePalletRecord | null>(saved ?? null)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState(saved ? `Record #${saved.id} saved successfully.` : '')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [filter, setFilter] = useState({ from: '', to: '', attempt: 0 })
  useEffect(() => {
    let cancelled = false
    getRecords(filter.from, filter.to).then(records => {
      if (cancelled) return
      setRecords(records)
      setSelected(current => current ? records.find(record => record.id === current.id) ?? null : null)
      setConfirmDelete(false)
    })
      .catch(error => { if (!cancelled) setError(errorMessage(error)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filter])

  function applyFilter(event: FormEvent) {
    event.preventDefault(); setError(''); setLoading(true)
    setFilter({ from, to, attempt: filter.attempt + 1 })
  }
  async function open(id: number) {
    setBusy(true); setError(''); setConfirmDelete(false)
    try { setSelected(await getRecord(id)) }
    catch (error) { setError(errorMessage(error)) }
    finally { setBusy(false) }
  }
  async function remove() {
    if (!selected?.id || busy) return
    setBusy(true); setError('')
    onBusyChange(true)
    const id = selected.id
    function reconcile(message: string) {
      setRecords(records => records.filter(record => record.id !== id))
      setMessage(message)
      setSelected(null)
      setConfirmDelete(false)
      setFilter(filter => ({ ...filter, attempt: filter.attempt + 1 }))
    }
    try {
      await deleteRecord(id)
      reconcile(`Record #${id} deleted.`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        reconcile(`Record #${id} is already deleted or no longer available.`)
      } else {
        setError(`${errorMessage(error)} Retry deletion or refresh Records to check its current state.`)
      }
    }
    finally { setBusy(false); onBusyChange(false) }
  }
  return <section aria-labelledby="records-heading">
    <div className="page-heading"><div><p className="eyebrow">CRATE / PALLET</p><h1 id="records-heading">Records</h1>
      <p>View submitted loads{isAdmin ? ', correct entries, or delete an incorrect record.' : '. Submitted records are read-only for Staff.'}</p></div></div>
    {message && <p className="notice success" role="status">{message}</p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    <div className="panel"><form className="filter-bar" onSubmit={applyFilter}>
      <label>From<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>To<input type="date" min={from || undefined} value={to} onChange={event => setTo(event.target.value)} /></label>
      <button className="secondary" disabled={loading || busy}>Apply dates / refresh</button>
    </form>
      {loading ? <p role="status">Loading records…</p> : records.length === 0 ? <p>No records found.</p>
        : <div className="table-scroll"><table><caption className="sr-only">Submitted Crate/Pallet records</caption>
          <thead><tr><th>Record</th><th>Date</th><th>Container</th><th className="numeric">Net weight</th><th>Submitted by</th></tr></thead>
          <tbody>{records.map(record => <tr key={record.id} className={selected?.id === record.id ? 'selected-row' : ''}>
            <td><button className="text-button" disabled={busy} onClick={() => open(record.id!)}>#{record.id}</button></td>
            <td>{record.recordDate}</td><td>{record.containerTypeName}</td><td className="numeric">{record.netWeight} kg</td><td>{record.createdBy}</td>
          </tr>)}</tbody></table></div>}
    </div>
    {selected && <div className="panel detail-panel"><div className="detail-heading"><h2>Record #{selected.id}</h2>
      <button className="text-button" disabled={busy} onClick={() => { setSelected(null); setConfirmDelete(false) }}>Close details</button></div>
      <CratePalletReview record={selected} />
      {isAdmin && <div className="actions"><button className="secondary" disabled={busy} onClick={() => onEdit(selected)}>Correct record</button>
        <button className="danger secondary" disabled={busy} onClick={() => setConfirmDelete(true)}>Delete record</button></div>}
      {confirmDelete && <div className="notice" role="alert"><p>Delete record #{selected.id}? It will be removed from the Records list and retained for traceability.</p>
        <div className="actions"><button className="danger" disabled={busy} onClick={remove}>{busy ? 'Deleting…' : 'Confirm deletion'}</button>
          <button className="secondary" disabled={busy} onClick={() => setConfirmDelete(false)}>Cancel</button></div></div>}
    </div>}
  </section>
}
