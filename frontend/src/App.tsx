import { WhitegoodsPage } from './features/whitegoods/WhitegoodsPage'
import { CollectablePage } from './features/collectables/CollectablePage'
import { ElectricalCratePalletPage } from './features/electrical-crate-pallet/ElectricalCratePalletPage'
import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from './api/client'
import { currentUser, logout } from './features/auth/api'
import type { User } from './features/auth/api'
import { LoginPage } from './features/auth/LoginPage'
import { CratePalletPage } from './features/crate-pallet/CratePalletPage'
import { PickupPage } from './features/pickup/PickupPage'
import { SalvagePage } from './features/salvage/SalvagePage'
import type { OperationalRecord, Workflow } from './features/records/types'
import { RecordsPage } from './pages/RecordsPage'

function App() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState<'entry' | 'records'>('records')
  const [editing, setEditing] = useState<OperationalRecord | undefined>()
  const [saved, setSaved] = useState<OperationalRecord | undefined>()
  const [entryKey, setEntryKey] = useState(0)
  const [hasEntry, setHasEntry] = useState(false)
  const [entryWorkflow, setEntryWorkflow] = useState<Workflow>('CRATE_PALLET')
  const [recordWorkflow, setRecordWorkflow] = useState<Workflow>('CRATE_PALLET')
  const [signingOut, setSigningOut] = useState(false)
  const [busy, setBusy] = useState(false)
  const [unresolved, setUnresolved] = useState(false)
  const busyRef = useRef(false)
  const unresolvedRef = useRef(false)

  useEffect(() => {
    let cancelled = false
    currentUser().then(user => { if (!cancelled) setUser(user) }).catch(error => {
      if (!cancelled && (!(error instanceof ApiError) || error.status !== 401)) setError(errorMessage(error))
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    function beforeUnload(event: BeforeUnloadEvent) {
      if (busyRef.current || unresolvedRef.current) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [])

  function activityChanged(value: boolean) {
    busyRef.current = value
    setBusy(value)
  }

  function unresolvedChanged(value: boolean) {
    unresolvedRef.current = value
    setUnresolved(value)
  }

  function showRecords() {
    if (busyRef.current) return
    if (hasEntry) setRecordWorkflow(entryWorkflow)
    setPage('records')
    setSaved(undefined)
  }

  function newRecord(workflow: Workflow) {
    if (busyRef.current) return
    setError('')
    if (!unresolvedRef.current) {
      setEditing(undefined)
      setSaved(undefined)
      setEntryWorkflow(workflow)
      setEntryKey(key => key + 1)
      setHasEntry(true)
    }
    setPage('entry')
  }

  async function signOut() {
    if (busyRef.current || unresolvedRef.current) return
    setSigningOut(true)
    setError('')
    try {
      await logout()
      setUser(null)
      setEditing(undefined)
      setSaved(undefined)
      setHasEntry(false)
      setPage('records')
    } catch (error) { setError(errorMessage(error)) }
    finally { setSigningOut(false) }
  }

  function recordSaved(record: OperationalRecord) {
    setSaved(record)
    setRecordWorkflow(record.workflow)
    setEditing(undefined)
    setHasEntry(false)
    setPage('records')
  }

  if (loading) return <main className="app-shell"><p role="status">Loading waste tracking...</p></main>
  if (!user) return <>{error && <p className="notice error" role="alert">{error}</p>}<LoginPage onLogin={user => { setError(''); setUser(user) }} /></>
  return <><header className="app-header"><div className="header-inner">
    <div className="brand"><span className="brand-mark" aria-hidden="true">RWC</span><div><strong>Resource Work Cooperative</strong><span>Waste tracking</span></div></div>
    <div className="account"><span>{user.username} <span className="role-badge">{user.role === 'ADMIN' ? 'Admin' : 'Staff'}</span></span>
      <button className="secondary" disabled={signingOut || busy || unresolved} onClick={signOut}>{signingOut ? 'Signing out...' : 'Sign out'}</button></div>
  </div></header>
    <main className="app-shell" inert={signingOut}><nav aria-label="Main navigation">
      <button disabled={busy || signingOut} className={page === 'records' ? 'nav-item active' : 'nav-item'} onClick={showRecords}>Records</button>
      <button disabled={busy || signingOut || (unresolved && entryWorkflow !== 'CRATE_PALLET')}
        className={page === 'entry' && entryWorkflow === 'CRATE_PALLET' && !editing ? 'nav-item active' : 'nav-item'}
        onClick={() => newRecord('CRATE_PALLET')}>{unresolved && entryWorkflow === 'CRATE_PALLET' ? 'Resume submission' : 'New Crate / Pallet'}</button>
      <button disabled={busy || signingOut || (unresolved && entryWorkflow !== 'SALVAGE')}
        className={page === 'entry' && entryWorkflow === 'SALVAGE' && !editing ? 'nav-item active' : 'nav-item'}
        onClick={() => newRecord('SALVAGE')}>{unresolved && entryWorkflow === 'SALVAGE' ? 'Resume submission' : 'New Salvage'}</button>
      <button disabled={busy || signingOut || (unresolved && entryWorkflow !== 'PICKUP')}
        className={page === 'entry' && entryWorkflow === 'PICKUP' && !editing ? 'nav-item active' : 'nav-item'}
        onClick={() => newRecord('PICKUP')}>{unresolved && entryWorkflow === 'PICKUP' ? 'Resume submission' : 'New Pickup'}</button>
      <button disabled={busy || signingOut || (unresolved && entryWorkflow !== 'ELECTRICAL_CRATE_PALLET')}
        className={page === 'entry' && entryWorkflow === 'ELECTRICAL_CRATE_PALLET' && !editing ? 'nav-item active' : 'nav-item'}
        onClick={() => newRecord('ELECTRICAL_CRATE_PALLET')}>{unresolved && entryWorkflow === 'ELECTRICAL_CRATE_PALLET' ? 'Resume submission' : 'New Electrical Crate / Pallet'}</button>
      <button disabled={busy || signingOut || (unresolved && entryWorkflow !== 'COLLECTABLES')}
        className={page === 'entry' && entryWorkflow === 'COLLECTABLES' && !editing ? 'nav-item active' : 'nav-item'}
        onClick={() => newRecord('COLLECTABLES')}>{unresolved && entryWorkflow === 'COLLECTABLES' ? 'Resume submission' : 'New Collectables'}</button>
      <button disabled={busy || signingOut || (unresolved && entryWorkflow !== 'WHITEGOODS')}
        className={page === 'entry' && entryWorkflow === 'WHITEGOODS' && !editing ? 'nav-item active' : 'nav-item'}
        onClick={() => newRecord('WHITEGOODS')}>{unresolved && entryWorkflow === 'WHITEGOODS' ? 'Resume submission' : 'New Whitegoods'}</button>
    </nav>
      {error && <p className="notice error" role="alert">{error}</p>}
      {unresolved && <p className="notice" role="status">A submission is unresolved. Retry it or check Records. Its details stay in memory while you check.</p>}
      {hasEntry && <div hidden={page !== 'entry'}>
        {entryWorkflow === 'CRATE_PALLET' ? <CratePalletPage key={entryKey}
          editing={editing?.workflow === 'CRATE_PALLET' ? editing.record : undefined}
          onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
          onSaved={record => recordSaved({ workflow: 'CRATE_PALLET', record })} onCancel={showRecords} />
          : entryWorkflow === 'SALVAGE' ? <SalvagePage key={entryKey} editing={editing?.workflow === 'SALVAGE' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
            onSaved={record => recordSaved({ workflow: 'SALVAGE', record })} onCancel={showRecords} />
          : entryWorkflow === 'PICKUP' ? <PickupPage key={entryKey} editing={editing?.workflow === 'PICKUP' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
            onSaved={record => recordSaved({ workflow: 'PICKUP', record })} onCancel={showRecords} />
          : entryWorkflow === 'ELECTRICAL_CRATE_PALLET' ? <ElectricalCratePalletPage key={entryKey} editing={editing?.workflow === 'ELECTRICAL_CRATE_PALLET' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
            onSaved={record => recordSaved({ workflow: 'ELECTRICAL_CRATE_PALLET', record })} onCancel={showRecords} />
          : entryWorkflow === 'COLLECTABLES' ? <CollectablePage key={entryKey} editing={editing?.workflow === 'COLLECTABLES' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
            onSaved={record => recordSaved({ workflow: 'COLLECTABLES', record })} onCancel={showRecords} />
          : <WhitegoodsPage key={entryKey} editing={editing?.workflow === 'WHITEGOODS' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
            onSaved={record => recordSaved({ workflow: 'WHITEGOODS', record })} onCancel={showRecords} />}
      </div>}
      {page === 'records' && <RecordsPage isAdmin={user.role === 'ADMIN'} saved={saved}
        workflow={recordWorkflow} onWorkflowChange={workflow => { setSaved(undefined); setRecordWorkflow(workflow) }}
        onBusyChange={activityChanged} onEdit={record => {
          if (busyRef.current) return
          if (unresolvedRef.current) {
            setError('Resolve the current submission before correcting another record.')
            return
          }
          setEditing(record)
          setEntryWorkflow(record.workflow)
          setEntryKey(key => key + 1)
          setHasEntry(true)
          setPage('entry')
        }} />}
      <footer>Resource Work Cooperative - Operational dates and times use Australia/Hobart.</footer>
    </main></>
}

export default App
