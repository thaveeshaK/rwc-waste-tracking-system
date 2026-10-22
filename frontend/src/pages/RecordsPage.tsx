import * as testing from '../features/electrical-testing/api'
import { TallyReview } from '../features/electrical-testing/TallyReview'
import { typeLabels } from '../features/electrical-testing/types'
import * as whitegoods from '../features/whitegoods/api'
import { WhitegoodsReview } from '../features/whitegoods/WhitegoodsReview'
import { WhitegoodsSummary } from '../features/whitegoods/WhitegoodsSummary'
import * as collectables from '../features/collectables/api'
import { CollectableReview } from '../features/collectables/CollectableReview'
import * as electrical from '../features/electrical-crate-pallet/api'
import { ElectricalCratePalletReview } from '../features/electrical-crate-pallet/ElectricalCratePalletReview'
import * as pickup from '../features/pickup/api'
import { PickupReview } from '../features/pickup/PickupReview'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, errorMessage } from '../api/client'
import * as crate from '../features/crate-pallet/api'
import * as salvage from '../features/salvage/api'
import { CratePalletReview } from '../features/crate-pallet/CratePalletReview'
import { SalvageReview } from '../features/salvage/SalvageReview'
import type { OperationalRecord, Workflow } from '../features/records/types'

interface Props {
  isAdmin: boolean
  workflow: Workflow
  saved?: OperationalRecord
  onWorkflowChange: (workflow: Workflow) => void
  onEdit: (record: OperationalRecord) => void
  onBusyChange: (busy: boolean) => void
}

export function RecordsPage({ isAdmin, workflow, saved, onWorkflowChange, onEdit, onBusyChange }: Props) {
  const [records, setRecords] = useState<OperationalRecord[]>([])
  const [selected, setSelected] = useState<OperationalRecord | null>(saved ?? null)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState(saved ? `Record #${saved.record.id} saved successfully.${saved.workflow === 'COLLECTABLES' ? ' Mark the physical tub label as recorded.' : ''}` : '')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [filter, setFilter] = useState({ from: '', to: '', attempt: 0 })

  useEffect(() => {
    let cancelled = false
    const load = async (): Promise<OperationalRecord[]> => workflow === 'CRATE_PALLET'
      ? (await crate.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'CRATE_PALLET', record }))
      : workflow === 'SALVAGE' ? (await salvage.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'SALVAGE', record }))
      : workflow === 'PICKUP' ? (await pickup.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'PICKUP', record }))
      : workflow === 'ELECTRICAL_CRATE_PALLET' ? (await electrical.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'ELECTRICAL_CRATE_PALLET', record }))
      : workflow === 'COLLECTABLES' ? (await collectables.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'COLLECTABLES', record }))
      : workflow === 'WHITEGOODS' ? (await whitegoods.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'WHITEGOODS', record }))
      : (await testing.getRecords(filter.from, filter.to)).map(record => ({ workflow: 'ELECTRICAL_TESTING', record }))
    load().then(records => {
      if (cancelled) return
      setRecords(records)
      setSelected(current => current ? records.find(row => row.workflow === current.workflow && row.record.id === current.record.id) ?? null : null)
    }).catch(error => { if (!cancelled) setError(errorMessage(error)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filter, workflow])

  function changeWorkflow(value: Workflow) {
    if (busy || value === workflow) return
    setSelected(null); setRecords([]); setConfirmDelete(false); setMessage(''); setError(''); setLoading(true)
    onWorkflowChange(value)
  }

  function applyFilter(event: FormEvent) {
    event.preventDefault(); setError(''); setLoading(true); setConfirmDelete(false)
    setFilter({ from, to, attempt: filter.attempt + 1 })
  }

  async function open(id: number) {
    setBusy(true); setError(''); setConfirmDelete(false)
    try {
      setSelected(workflow === 'CRATE_PALLET' ? { workflow, record: await crate.getRecord(id) }
        : workflow === 'SALVAGE' ? { workflow, record: await salvage.getRecord(id) } : workflow === 'PICKUP' ? { workflow, record: await pickup.getRecord(id) } : workflow === 'ELECTRICAL_CRATE_PALLET' ? { workflow, record: await electrical.getRecord(id) } : workflow === 'COLLECTABLES' ? { workflow, record: await collectables.getRecord(id) } : workflow === 'WHITEGOODS' ? { workflow, record: await whitegoods.getRecord(id) } : { workflow, record: await testing.getRecord(id) })
    } catch (error) { setError(errorMessage(error)) }
    finally { setBusy(false) }
  }

  async function remove() {
    if (!selected?.record.id || busy) return
    setBusy(true); setError(''); onBusyChange(true)
    const id = selected.record.id
    const selectedWorkflow = selected.workflow
    function reconcile(message: string) {
      setRecords(records => records.filter(row => row.workflow !== selectedWorkflow || row.record.id !== id))
      setMessage(message); setSelected(null); setConfirmDelete(false)
      setFilter(filter => ({ ...filter, attempt: filter.attempt + 1 }))
    }
    try {
      await (selectedWorkflow === 'CRATE_PALLET' ? crate.deleteRecord(id) : selectedWorkflow === 'SALVAGE' ? salvage.deleteRecord(id) : selectedWorkflow === 'PICKUP' ? pickup.deleteRecord(id) : selectedWorkflow === 'ELECTRICAL_CRATE_PALLET' ? electrical.deleteRecord(id) : selectedWorkflow === 'COLLECTABLES' ? collectables.deleteRecord(id) : selectedWorkflow === 'WHITEGOODS' ? whitegoods.deleteRecord(id) : testing.deleteRecord(id))
      reconcile(`Record #${id} deleted.`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) reconcile(`Record #${id} is already deleted or no longer available.`)
      else setError(`${errorMessage(error)} Retry deletion or refresh Records to check its current state.`)
    } finally { setBusy(false); onBusyChange(false) }
  }

  return <section aria-labelledby="records-heading">
    <div className="page-heading"><div><p className="eyebrow">{workflow === 'CRATE_PALLET' ? 'CRATE / PALLET' : workflow === 'SALVAGE' ? 'SALVAGE TRIPS' : workflow === 'PICKUP' ? 'PICKUPS' : workflow === 'ELECTRICAL_CRATE_PALLET' ? 'ELECTRICAL CRATE / PALLET' : workflow === 'COLLECTABLES' ? 'COLLECTABLES' : workflow === 'WHITEGOODS' ? 'WHITEGOODS' : 'ELECTRICAL TESTING'}</p><h1 id="records-heading">Records</h1>
      <p>View submitted loads{isAdmin ? ', correct entries, or delete an incorrect record.' : '. Submitted records are read-only for Staff.'}</p></div></div>
    {message && <p className="notice success" role="status">{message}</p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {workflow === 'WHITEGOODS' && <WhitegoodsSummary refreshToken={filter.attempt} busy={busy} />}
    <div className="panel"><form className="filter-bar" onSubmit={applyFilter}>
      <label>Workflow<select value={workflow} disabled={busy} onChange={event => changeWorkflow(event.target.value as Workflow)}>
        <option value="CRATE_PALLET">Crate / Pallet</option><option value="SALVAGE">Salvage</option><option value="PICKUP">Pickup</option><option value="ELECTRICAL_CRATE_PALLET">Electrical Crate / Pallet</option><option value="COLLECTABLES">Collectables</option><option value="WHITEGOODS">Whitegoods</option><option value="ELECTRICAL_TESTING">Electrical Testing</option></select></label>
      <label>From<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>To<input type="date" min={from || undefined} value={to} onChange={event => setTo(event.target.value)} /></label>
      <button className="secondary" disabled={loading || busy}>Apply dates / refresh</button>
    </form>
      {loading ? <p role="status">Loading records...</p> : records.length === 0 ? <p>No records found.</p>
        : <div className="table-scroll"><table><caption className="sr-only">Submitted {workflow === 'SALVAGE' ? 'Salvage' : workflow === 'PICKUP' ? 'Pickup' : workflow === 'ELECTRICAL_CRATE_PALLET' ? 'Electrical Crate/Pallet' : workflow === 'COLLECTABLES' ? 'Collectables' : workflow === 'WHITEGOODS' ? 'Whitegoods' : workflow === 'ELECTRICAL_TESTING' ? 'Electrical Testing' : 'Crate/Pallet'} records</caption>
          <thead><tr><th>Record</th><th>Date</th>{workflow === 'CRATE_PALLET' || workflow === 'ELECTRICAL_CRATE_PALLET' ? <><th>Container</th><th className="numeric">Net weight</th></>
            : workflow === 'PICKUP' ? <><th>Pickup location</th><th className="numeric">Net weight</th></> : workflow === 'COLLECTABLES' ? <th className="numeric">Net weight</th> : workflow === 'WHITEGOODS' ? <><th className="numeric">Count</th><th className="numeric">Estimated weight</th></> : workflow === 'ELECTRICAL_TESTING' ? <><th>Category / destination</th><th>Tally type</th><th className="numeric">Quantity</th></> : <><th>Crew</th><th>Time Up / Down</th><th className="numeric">Kg Up</th><th className="numeric">Kg Down</th></>}<th>Submitted by</th></tr></thead>
          <tbody>{records.map(row => <tr key={`${row.workflow}:${row.record.id}`} className={selected?.workflow === row.workflow && selected.record.id === row.record.id ? 'selected-row' : ''}>
            <td><button className="text-button" disabled={busy} onClick={() => open(row.record.id!)}>#{row.record.id}</button></td>
            <td>{row.record.recordDate}</td>{row.workflow === 'CRATE_PALLET' || row.workflow === 'ELECTRICAL_CRATE_PALLET' ? <><td>{row.record.containerTypeName}</td><td className="numeric">{row.record.netWeight} kg</td></>
              : row.workflow === 'PICKUP' ? <><td>{row.record.pickupLocation}</td><td className="numeric">{row.record.netWeight} kg</td></> : row.workflow === 'COLLECTABLES' ? <td className="numeric">{row.record.netWeight} kg</td> : row.workflow === 'WHITEGOODS' ? <><td className="numeric">{row.record.count}</td><td className="numeric">{row.record.estimatedWeight} kg</td></> : row.workflow === 'ELECTRICAL_TESTING' ? <><td>{row.record.categoryName??'Overall diversion'}</td><td>{typeLabels[row.record.tallyType]}</td><td className="numeric">{row.record.count}</td></> : <><td>{row.record.crew}</td><td>{row.record.up.time.slice(0, 5)} / {row.record.down.time.slice(0, 5)}</td>
                <td className="numeric">{row.record.up.kg} kg</td><td className="numeric">{row.record.down.kg} kg</td></>}
            <td>{row.record.createdBy}</td></tr>)}</tbody></table></div>}
    </div>
    {selected && <div className="panel detail-panel"><div className="detail-heading"><h2>Record #{selected.record.id}</h2>
      <button className="text-button" disabled={busy} onClick={() => { setSelected(null); setConfirmDelete(false) }}>Close details</button></div>
      {selected.workflow === 'CRATE_PALLET' ? <CratePalletReview record={selected.record} /> : selected.workflow === 'SALVAGE' ? <SalvageReview record={selected.record} /> : selected.workflow === 'PICKUP' ? <PickupReview record={selected.record} /> : selected.workflow === 'ELECTRICAL_CRATE_PALLET' ? <ElectricalCratePalletReview record={selected.record} /> : selected.workflow === 'COLLECTABLES' ? <CollectableReview record={selected.record} /> : selected.workflow === 'WHITEGOODS' ? <WhitegoodsReview record={selected.record} /> : <TallyReview record={selected.record} />}
      {isAdmin && <div className="actions"><button className="secondary" disabled={busy} onClick={() => onEdit(selected)}>Correct record</button>
        <button className="danger secondary" disabled={busy} onClick={() => setConfirmDelete(true)}>Delete record</button></div>}
      {confirmDelete && <div className="notice" role="alert"><p>Delete {selected.workflow === 'SALVAGE' ? 'the whole Salvage trip' : 'record'} #{selected.record.id}? It will be removed from Records and retained for traceability.</p>
        <div className="actions"><button className="danger" disabled={busy} onClick={remove}>{busy ? 'Deleting...' : 'Confirm deletion'}</button>
          <button className="secondary" disabled={busy} onClick={() => setConfirmDelete(false)}>Cancel</button></div></div>}
    </div>}
  </section>
}
