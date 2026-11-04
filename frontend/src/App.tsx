import { EwastePickupPage } from './features/ewaste-pickup/EwastePickupPage'
import { settingsSectionFromPath } from './features/settings/sections'
import type { SettingsSection } from './features/settings/sections'
import { StaffOperationsPage } from './features/operations/StaffOperationsPage'
import { WorkflowsPage } from './features/operations/WorkflowsPage'
import { SettingsPage } from './features/settings/SettingsPage'
import { getOperationalConfiguration } from './features/settings/api'
import type { OperationalConfiguration } from './features/settings/types'
import { Navigation, type Page } from './components/navigation/Navigation'
import { OverviewPage } from './features/reporting/OverviewPage'
import { ReportsPage } from './features/reporting/ReportsPage'
import { WasteLevySummaryPage } from './features/reporting/WasteLevySummaryPage'
import type { Row } from './features/reporting/types'
import { request } from './api/client'
import { ElectricalTestingPage } from './features/electrical-testing/ElectricalTestingPage'
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
  const [configuration, setConfiguration] = useState<OperationalConfiguration | null>(null)
  const [configurationRevision, setConfigurationRevision] = useState(0)
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [settingsSection, setSettingsSection] = useState<SettingsSection>(() => settingsSectionFromPath(window.location.pathname) ?? 'container-types')
  const [page, setPage] = useState<Page>('operations')
  const [editing, setEditing] = useState<OperationalRecord | undefined>()
  const [reported, setReported] = useState<OperationalRecord | undefined>()
  const [saved, setSaved] = useState<OperationalRecord | undefined>()
  const [entryKey, setEntryKey] = useState(0)
  const [hasEntry, setHasEntry] = useState(false)
  const [entryWorkflow, setEntryWorkflow] = useState<Workflow>('CRATE_PALLET')
  const [recordWorkflow, setRecordWorkflow] = useState<Workflow>('CRATE_PALLET')
  const [signingOut, setSigningOut] = useState(false)
  const [busy, setBusy] = useState(false)
  const [pendingTallies, setPendingTallies] = useState(false)
  const pendingTalliesRef = useRef(false)
  const [unresolved, setUnresolved] = useState(false)
  const busyRef = useRef(false)
  const unresolvedRef = useRef(false)

  useEffect(() => {
    let cancelled = false
    currentUser().then(user => { if (!cancelled) { setPage(user.role === 'ADMIN' ? (settingsSectionFromPath(window.location.pathname) ? 'settings' : 'overview') : 'operations'); setUser(user) } }).catch(error => {
      if (!cancelled && (!(error instanceof ApiError) || error.status !== 401)) setError(errorMessage(error))
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (!user) return
    let cancelled = false
    getOperationalConfiguration().then(value => {if (!cancelled) setConfiguration(value)}).catch(e => {if (!cancelled) setError(errorMessage(e))})
    return () => {cancelled = true}
  }, [user, configurationRevision])

  useEffect(() => {
    function beforeUnload(event: BeforeUnloadEvent) {
      if (busyRef.current || unresolvedRef.current || pendingTalliesRef.current) {
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

  function pendingTalliesChanged(value: boolean) {
    pendingTalliesRef.current = value
    setPendingTallies(value)
  }

  function showRecords() {
    if (busyRef.current) return
    if (hasEntry) setRecordWorkflow(entryWorkflow)
    setPage('records')
    setReported(undefined)
    setSaved(undefined)
  }

  function newRecord(workflow: Workflow) {
    if (busyRef.current) return
    setError('')
    if (!unresolvedRef.current && !pendingTalliesRef.current) {
      setEditing(undefined)
      setSaved(undefined)
      setEntryWorkflow(workflow)
      setEntryKey(key => key + 1)
      setHasEntry(true)
    }
    setPage('entry')
  }

  async function signOut() {
    if (busyRef.current || unresolvedRef.current || pendingTalliesRef.current) return
    setSigningOut(true)
    setError('')
    try {
      await logout()
      window.history.replaceState(null,'','/')
      setUser(null)
      setConfiguration(null)
      setEditing(undefined)
      setSaved(undefined)
      setHasEntry(false)
      setPage('records')
    } catch (error) { setError(errorMessage(error)) }
    finally { setSigningOut(false) }
  }

  function recordSaved(record: OperationalRecord) {
    setReported(undefined)
    setSaved(record)
    setRecordWorkflow(record.workflow)
    setEditing(undefined)
    setHasEntry(false)
    setPage('records')
  }

  function navigate(target: Page) {
    if (busyRef.current || signingOut) return
    if (['overview', 'reports', 'waste-levy', 'settings'].includes(target) && user?.role !== 'ADMIN') return
    if (target !== 'settings') window.history.replaceState(null,'','/')
    if (target === 'records') { showRecords(); return }
    setError(''); setPage(target)
  }

  function showSettings(section: SettingsSection) {
    if(user?.role!=='ADMIN'||busyRef.current||signingOut)return
    setSettingsSection(section)
    window.history.replaceState(null,'',`/admin/settings/${section}`)
    navigate('settings')
  }

  async function openReportedRecord(row: Row) {
    if (user?.role !== 'ADMIN' || busyRef.current || row.rowType === 'REFERENCE') return
    const paths: Record<string, string> = { EWASTE_PICKUP: '/api/ewaste-pickups', CRATE_PALLET: '/api/crate-pallet-records', SALVAGE: '/api/salvage-records', PICKUP: '/api/pickup-records', ELECTRICAL_CRATE_PALLET: '/api/electrical-crate-pallet-records', COLLECTABLES: '/api/collectable-records', WHITEGOODS: '/api/whitegoods-records', ELECTRICAL_TESTING: '/api/electrical-testing/tallies' }
    activityChanged(true); setError('')
    try {
      const record = await request<OperationalRecord['record']>(paths[row.workflow]+'/'+row.id)
      setRecordWorkflow(row.workflow as Workflow)
      setSaved(undefined)
      setReported({workflow: row.workflow, record} as OperationalRecord)
      setPage('records')
    } catch (e) { setError(errorMessage(e)) }
    finally { activityChanged(false) }
  }

  if (loading) return <main className="app-shell"><p role="status">Loading waste tracking...</p></main>
  if (!user) return <>{error && <p className="notice error" role="alert">{error}</p>}<LoginPage onLogin={user => { setError(''); setPage(user.role === 'ADMIN' ? (settingsSectionFromPath(window.location.pathname) ? 'settings' : 'overview') : 'operations'); setUser(user) }} /></>
  return <><header className="app-header"><div className="header-inner">
    <div className="brand"><span className="brand-mark" aria-hidden="true">RWC</span><div><strong>{configuration?.organisationName ?? 'Resource Work Cooperative'}</strong><span>{configuration?.siteName || 'Waste tracking'}</span></div></div>
    <div className="account"><span>{user.username} <span className="role-badge">{user.role === 'ADMIN' ? 'Admin' : 'Staff'}</span></span>
      <button className="secondary" disabled={signingOut || busy || unresolved || pendingTallies} onClick={signOut}>{signingOut ? 'Signing out...' : 'Sign out'}</button></div>
  </div></header>
    <main className="app-shell" inert={signingOut}><Navigation isAdmin={user.role === 'ADMIN'} page={page} busy={busy || signingOut} section={settingsSection} onSettings={showSettings} onPage={navigate} />
      {error && <p className="notice error" role="alert">{error}</p>}
      {pendingTallies && !unresolved && page !== 'entry' && <p className="notice" role="status">Electrical Testing tallies are pending. Save them before signing out or starting another workflow. They remain in memory while you check Records.</p>}
      {unresolved && <p className="notice" role="status">A submission is unresolved. Retry it or check Records. Its details stay in memory while you check.</p>}
      {page === 'operations' && (user.role === 'ADMIN' ? <WorkflowsPage onNew={newRecord} busy={busy || signingOut} unresolved={unresolved || pendingTallies} currentWorkflow={entryWorkflow}/> : <StaffOperationsPage onNew={newRecord} onRecords={showRecords} busy={busy || signingOut} unresolved={unresolved || pendingTallies} currentWorkflow={entryWorkflow}/>)}
      {user.role === 'ADMIN' && page === 'settings' && <SettingsPage key={settingsSection} section={settingsSection} onChanged={() => setConfigurationRevision(n => n+1)}/>}
      {user.role === 'ADMIN' && page === 'overview' && <OverviewPage onOpen={openReportedRecord} onRecords={showRecords}/>}
      {user.role === 'ADMIN' && page === 'reports' && <ReportsPage onOpen={openReportedRecord}/>}
      {user.role === 'ADMIN' && page === 'waste-levy' && <WasteLevySummaryPage/>}
      {hasEntry && <div hidden={page !== 'entry'}>
        {entryWorkflow === 'EWASTE_PICKUP' ? <EwastePickupPage key={entryKey} editing={editing?.workflow === 'EWASTE_PICKUP' ? editing.record : undefined} onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged} onSaved={record => recordSaved({workflow: 'EWASTE_PICKUP', record})} onCancel={showRecords}/> : entryWorkflow === 'CRATE_PALLET' ? <CratePalletPage key={entryKey}
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
          : entryWorkflow === 'WHITEGOODS' ? <WhitegoodsPage key={entryKey} factor={configuration?.whitegoodsKgPerItem} editing={editing?.workflow === 'WHITEGOODS' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
            onSaved={record => recordSaved({ workflow: 'WHITEGOODS', record })} onCancel={showRecords} />
          : <ElectricalTestingPage key={entryKey} isAdmin={user.role === 'ADMIN'} editing={editing?.workflow === 'ELECTRICAL_TESTING' ? editing.record : undefined}
            onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged} onPendingChange={pendingTalliesChanged}
            onSaved={record => recordSaved({ workflow: 'ELECTRICAL_TESTING', record })} onCancel={showRecords} />}
      </div>}
      {page === 'records' && <RecordsPage isAdmin={user.role === 'ADMIN'} saved={saved} selectedRecord={reported}
        workflow={recordWorkflow} onWorkflowChange={workflow => { setSaved(undefined); setRecordWorkflow(workflow) }}
        onBusyChange={activityChanged} onEdit={record => {
          if (busyRef.current) return
          if (unresolvedRef.current || pendingTalliesRef.current) {
            setError('Resolve the current submission before correcting another record.')
            return
          }
          setEditing(record)
          setEntryWorkflow(record.workflow)
          setEntryKey(key => key + 1)
          setHasEntry(true)
          setPage('entry')
        }} />}
      <footer>{user.role === 'ADMIN' ? 'Resource Work Cooperative - Operational dates and times use Australia/Hobart.' : 'Resource Work Cooperative'}</footer>
    </main></>
}

export default App
