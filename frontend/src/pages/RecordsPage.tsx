import {Pagination} from '../components/Pagination'
import {getPage,getOperationalRecord} from '../features/records/api'
import type {RecordsView} from '../features/records/api'
import {workflowEntries} from '../features/operations/workflows'
import * as scrap from '../features/scrap/api'
import { ScrapReview } from '../features/scrap/ScrapReview'
import * as ewaste from '../features/ewaste-pickup/api'
import { EwastePickupReview } from '../features/ewaste-pickup/EwastePickupReview'
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
  workflow: Workflow | ''
  view: RecordsView
  onViewChange:(view:RecordsView)=>void
  saved?: OperationalRecord
  postSaveOrigin?: 'new' | 'correction'
  onNew: (workflow: Workflow) => void
  selectedRecord?: OperationalRecord
  onWorkflowChange: (workflow: Workflow | '') => void
  onEdit: (record: OperationalRecord) => void
  onBusyChange: (busy: boolean) => void
}

export function RecordsPage({ isAdmin, workflow, view, onViewChange, saved, postSaveOrigin, onNew, selectedRecord, onWorkflowChange, onEdit, onBusyChange }: Props) {
  const [records, setRecords] = useState<OperationalRecord[]>([])
  const [selected, setSelected] = useState<OperationalRecord | null>(selectedRecord ?? saved ?? null)
  const [openedFromRecords, setOpenedFromRecords] = useState(!!selectedRecord)
  const [from, setFrom] = useState(view.from)
  const [to, setTo] = useState(view.to)
  const [error, setError] = useState('')
  const [message, setMessage] = useState(saved ? `Record #${saved.record.id} saved successfully.${saved.workflow === 'COLLECTABLES' ? ' Mark the physical tub label as recorded.' : ''}` : '')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [totalItems,setTotalItems]=useState(0)
  const anotherWorkflow=postSaveOrigin==='new' && saved && saved.workflow!=='ELECTRICAL_TESTING' && !openedFromRecords && selected?.workflow===saved.workflow && selected.record.id===saved.record.id ? saved.workflow : undefined
  const anotherLabel=anotherWorkflow?newEntryLabels[anotherWorkflow]:undefined

  useEffect(() => {
    let cancelled = false
    getPage(workflow,view).then(result => {
      if(cancelled)return
      setRecords(result.items);setTotalItems(result.totalItems);setError('')
      if(result.page!==view.page)onViewChange({...view,page:result.page})
    }).catch(error=>{if(!cancelled)setError(errorMessage(error))})
      .finally(()=>{if(!cancelled)setLoading(false)})
    return () => { cancelled = true }
  }, [view,attempt,workflow,onViewChange])

  const selectedDate=selected?.workflow==='SCRAP'?selected.record.reportingMonth+'-01':selected?.record.recordDate
  const selectedStart=selected?.workflow==='SCRAP'&&view.from?view.from.slice(0,7)+'-01':view.from
  const selectedMatches=!!selectedDate&&(!workflow||workflow===selected?.workflow)&&(!selectedStart||selectedDate>=selectedStart)&&(!view.to||selectedDate<=view.to)
  useEffect(()=>{
    if(!selected?.record.id)return
    let cancelled=false
    getOperationalRecord(selected.workflow,selected.record.id).then(record=>{if(!cancelled)setSelected(selectedMatches?record:null)}).catch(error=>{if(!cancelled){if(error instanceof ApiError&&error.status===404)setSelected(null);else setError(errorMessage(error))}})
    return()=>{cancelled=true}
  },[selected?.workflow,selected?.record.id,selectedMatches,view,attempt,workflow])

  function changeWorkflow(value: Workflow | '') {
    if (busy || value === workflow) return
    setSelected(null); setRecords([]); setConfirmDelete(false); setMessage(''); setError(''); setLoading(true)
    onViewChange({...view,page:0});onWorkflowChange(value)
  }

  function applyFilter(event: FormEvent) {
    event.preventDefault(); setError(''); setLoading(true); setConfirmDelete(false)
    onViewChange({...view,from,to,page:0});setAttempt(n=>n+1)
  }

  async function open(row:OperationalRecord) {
    setBusy(true);setError('');setConfirmDelete(false)
    try {setSelected(await getOperationalRecord(row.workflow,row.record.id!));setOpenedFromRecords(true)
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
      setLoading(true);setAttempt(n=>n+1)
    }
    try {
      await (selectedWorkflow === 'SCRAP' ? scrap.deleteRecord(id) : selectedWorkflow === 'EWASTE_PICKUP' ? ewaste.deleteRecord(id) : selectedWorkflow === 'CRATE_PALLET' ? crate.deleteRecord(id) : selectedWorkflow === 'SALVAGE' ? salvage.deleteRecord(id) : selectedWorkflow === 'PICKUP' ? pickup.deleteRecord(id) : selectedWorkflow === 'ELECTRICAL_CRATE_PALLET' ? electrical.deleteRecord(id) : selectedWorkflow === 'COLLECTABLES' ? collectables.deleteRecord(id) : selectedWorkflow === 'WHITEGOODS' ? whitegoods.deleteRecord(id) : testing.deleteRecord(id))
      reconcile(`Record #${id} deleted.`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) reconcile(`Record #${id} is already deleted or no longer available.`)
      else setError(`${errorMessage(error)} Retry deletion or refresh Records to check its current state.`)
    } finally { setBusy(false); onBusyChange(false) }
  }

  return <section aria-labelledby="records-heading">
    <div className="page-heading"><div><p className="eyebrow">{workflow === '' ? 'ALL WORKFLOWS' : workflow === 'SCRAP' ? 'SCRAP' : workflow === 'EWASTE_PICKUP' ? 'E-WASTE PICKUP' : workflow === 'CRATE_PALLET' ? 'CRATE / PALLET' : workflow === 'SALVAGE' ? 'SALVAGE TRIPS' : workflow === 'PICKUP' ? 'PICKUPS' : workflow === 'ELECTRICAL_CRATE_PALLET' ? 'ELECTRICAL CRATE / PALLET' : workflow === 'COLLECTABLES' ? 'COLLECTABLES' : workflow === 'WHITEGOODS' ? 'WHITEGOODS' : 'ELECTRICAL TESTING'}</p><h1 id="records-heading">Records</h1>
      <p>View submitted loads{isAdmin ? ', correct entries, or delete an incorrect record.' : '. Submitted records are read-only for Staff.'}</p></div></div>
    {workflow === 'SCRAP' && <p className="hint">Scrap uses reporting months. Date filters include each month touched by the selected range.</p>}
    {message && <p className="notice success" role="status">{message}</p>}
    {anotherWorkflow && anotherLabel && <div className="actions"><button disabled={busy} onClick={()=>onNew(anotherWorkflow)}>Enter another {anotherLabel}</button></div>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {workflow === 'WHITEGOODS' && <WhitegoodsSummary refreshToken={attempt} busy={busy} />}
    <div className="panel"><form className="filter-bar" onSubmit={applyFilter}>
      <label>Workflow<select value={workflow} disabled={busy} onChange={event => changeWorkflow(event.target.value as Workflow | '')}>
        <option value="">All workflows</option><option value="SCRAP">Scrap</option><option value="EWASTE_PICKUP">E-waste Pickup</option><option value="CRATE_PALLET">Crate / Pallet</option><option value="SALVAGE">Salvage</option><option value="PICKUP">Pickup</option><option value="ELECTRICAL_CRATE_PALLET">Electrical Crate / Pallet</option><option value="COLLECTABLES">Collectables</option><option value="WHITEGOODS">Whitegoods</option><option value="ELECTRICAL_TESTING">Electrical Testing</option></select></label>
      <label>From<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>To<input type="date" min={from || undefined} value={to} onChange={event => setTo(event.target.value)} /></label>
      <button className="secondary" disabled={loading || busy}>Apply dates / refresh</button>
    </form>
      {loading ? <p role="status">Loading records...</p> : records.length === 0 ? <p>No records found.</p>
        : <div className="table-scroll"><table><caption className="sr-only">Submitted {workflow === '' ? 'operational' : workflow === 'SCRAP' ? 'Scrap' : workflow === 'EWASTE_PICKUP' ? 'E-waste Pickup' : workflow === 'SALVAGE' ? 'Salvage' : workflow === 'PICKUP' ? 'Pickup' : workflow === 'ELECTRICAL_CRATE_PALLET' ? 'Electrical Crate/Pallet' : workflow === 'COLLECTABLES' ? 'Collectables' : workflow === 'WHITEGOODS' ? 'Whitegoods' : workflow === 'ELECTRICAL_TESTING' ? 'Electrical Testing' : 'Crate/Pallet'} records</caption>
          <thead><tr><th>Record</th><th>Date / reporting month</th>{workflow === '' ? <><th>Workflow</th><th>Details</th><th>Weight / count</th></> : workflow === 'SCRAP' ? <th>Total Scrap weight</th> : workflow === 'EWASTE_PICKUP' ? <><th>Organisation</th><th className="numeric">Net E-waste weight</th></> : workflow === 'CRATE_PALLET' || workflow === 'ELECTRICAL_CRATE_PALLET' ? <><th>Container</th><th className="numeric">Net weight</th></>
            : workflow === 'PICKUP' ? <><th>Pickup location</th><th className="numeric">Net weight</th></> : workflow === 'COLLECTABLES' ? <th className="numeric">Net weight</th> : workflow === 'WHITEGOODS' ? <><th className="numeric">Count</th><th className="numeric">Estimated weight</th></> : workflow === 'ELECTRICAL_TESTING' ? <><th>Category / destination</th><th>Tally type</th><th className="numeric">Quantity</th></> : <><th>Crew</th><th>Time Up / Down</th><th className="numeric">Kg Up</th><th className="numeric">Kg Down</th></>}<th>Submitted by</th></tr></thead>
          <tbody>{records.map(row => <tr key={`${row.workflow}:${row.record.id}`} className={`selectable-record ${selected?.workflow === row.workflow && selected.record.id === row.record.id ? 'selected-row' : ''}`}
            tabIndex={busy ? -1 : 0} aria-selected={selected?.workflow === row.workflow && selected.record.id === row.record.id}
            onClick={event => { if (!busy && !(event.target as HTMLElement).closest('button')) void open(row) }}
            onKeyDown={event => { if (!busy && event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); void open(row) } }}>
            <td><button className="text-button" disabled={busy} onClick={() => open(row)}>#{row.record.id}</button></td>
            <td>{row.workflow === 'SCRAP' ? row.record.reportingMonth : row.record.recordDate}</td>{workflow === '' ? <><td>{workflowEntries.find(w=>w.id===row.workflow)?.name}</td><UnifiedCells row={row}/></> : row.workflow === 'SCRAP' ? <td>{row.record.totalWeight} kg</td> : row.workflow === 'EWASTE_PICKUP' ? <><td>{row.record.organisation}</td><td className="numeric">{row.record.netWeight} kg</td></> : row.workflow === 'CRATE_PALLET' || row.workflow === 'ELECTRICAL_CRATE_PALLET' ? <><td>{row.record.containerTypeName}</td><td className="numeric">{row.record.netWeight} kg</td></>
              : row.workflow === 'PICKUP' ? <><td>{row.record.pickupLocation}</td><td className="numeric">{row.record.netWeight} kg</td></> : row.workflow === 'COLLECTABLES' ? <td className="numeric">{row.record.netWeight} kg</td> : row.workflow === 'WHITEGOODS' ? <><td className="numeric">{row.record.count}</td><td className="numeric">{row.record.estimatedWeight} kg</td></> : row.workflow === 'ELECTRICAL_TESTING' ? <><td>{row.record.categoryName??'Overall diversion'}</td><td>{typeLabels[row.record.tallyType]}</td><td className="numeric">{row.record.count}</td></> : <><td>{row.record.crew}</td><td>{row.record.up.time.slice(0, 5)} / {row.record.down.time.slice(0, 5)}</td>
                <td className="numeric">{row.record.up.kg} kg</td><td className="numeric">{row.record.down.kg} kg</td></>}
            <td>{row.record.createdBy}</td></tr>)}</tbody></table></div>}
      <Pagination page={view.page} size={view.size} totalItems={totalItems} busy={busy||loading} onPage={page=>{setLoading(true);onViewChange({...view,page})}} onSize={size=>{setLoading(true);onViewChange({...view,size,page:0})}}/>
    </div>
    {selected && <div className="panel detail-panel"><div className="detail-heading"><h2>Record #{selected.record.id}</h2>
      <button className="text-button" disabled={busy} onClick={() => { setSelected(null); setConfirmDelete(false) }}>Close details</button></div>
      {selected.workflow === 'SCRAP' ? <ScrapReview record={selected.record}/> : selected.workflow === 'EWASTE_PICKUP' ? <EwastePickupReview record={selected.record}/> : selected.workflow === 'CRATE_PALLET' ? <CratePalletReview record={selected.record} /> : selected.workflow === 'SALVAGE' ? <SalvageReview record={selected.record} /> : selected.workflow === 'PICKUP' ? <PickupReview record={selected.record} /> : selected.workflow === 'ELECTRICAL_CRATE_PALLET' ? <ElectricalCratePalletReview record={selected.record} /> : selected.workflow === 'COLLECTABLES' ? <CollectableReview record={selected.record} /> : selected.workflow === 'WHITEGOODS' ? <WhitegoodsReview record={selected.record} /> : <TallyReview record={selected.record} />}
      {isAdmin && (!saved || openedFromRecords) && <div className="actions"><button className="secondary" disabled={busy} onClick={() => onEdit(selected)}>Correct record</button>
        <button className="danger secondary" disabled={busy} onClick={() => setConfirmDelete(true)}>Delete record</button></div>}
      {confirmDelete && <div className="notice" role="alert"><p>Delete {selected.workflow === 'SALVAGE' ? 'the whole Salvage trip' : 'record'} #{selected.record.id}? It will be removed from Records and retained for traceability.</p>
        <div className="actions"><button className="danger" disabled={busy} onClick={remove}>{busy ? 'Deleting...' : 'Confirm deletion'}</button>
          <button className="secondary" disabled={busy} onClick={() => setConfirmDelete(false)}>Cancel</button></div></div>}
    </div>}
  </section>
}

const newEntryLabels: Partial<Record<Workflow,string>> = {
  CRATE_PALLET: 'Crate / Pallet', SALVAGE: 'Salvage trip', PICKUP: 'Pickup',
  ELECTRICAL_CRATE_PALLET: 'Electrical Crate / Pallet', COLLECTABLES: 'Collectables entry',
  WHITEGOODS: 'Whitegoods entry', EWASTE_PICKUP: 'E-waste Pickup', SCRAP: 'Scrap entry',
}

function UnifiedCells({row}:{row:OperationalRecord}) {
  switch(row.workflow) {
    case 'SCRAP':return <><td>Monthly Scrap</td><td>{row.record.totalWeight} kg</td></>
    case 'SALVAGE':return <><td>{row.record.crew}</td><td>Up {row.record.up.kg} kg / Down {row.record.down.kg} kg</td></>
    case 'ELECTRICAL_TESTING':return <><td>{row.record.categoryName??'Overall diversion'} / {typeLabels[row.record.tallyType]}</td><td>{row.record.count} tallied</td></>
    case 'WHITEGOODS':return <><td>Whitegoods</td><td>{row.record.count} / {row.record.estimatedWeight} kg estimated</td></>
    case 'EWASTE_PICKUP':return <><td>{row.record.organisation}</td><td>{row.record.netWeight} kg</td></>
    case 'PICKUP':return <><td>{row.record.pickupLocation}</td><td>{row.record.netWeight} kg</td></>
    case 'COLLECTABLES':return <><td>Collectable Tub</td><td>{row.record.netWeight} kg</td></>
    default:return <><td>{row.record.containerTypeName}</td><td>{row.record.netWeight} kg</td></>
  }
}
