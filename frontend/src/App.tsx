import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from './api/client'
import { currentUser, logout } from './features/auth/api'
import type { User } from './features/auth/api'
import { LoginPage } from './features/auth/LoginPage'
import { CratePalletPage } from './features/crate-pallet/CratePalletPage'
import type { CratePalletRecord } from './features/crate-pallet/types'
import { RecordsPage } from './pages/RecordsPage'

function App() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState<'entry' | 'records'>('records')
  const [editing, setEditing] = useState<CratePalletRecord | undefined>()
  const [saved, setSaved] = useState<CratePalletRecord | undefined>()
  const [entryKey, setEntryKey] = useState(0)
  const [hasEntry, setHasEntry] = useState(false)
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
    setPage('records')
    setSaved(undefined)
  }

  function newRecord() {
    if (busyRef.current) return
    setError('')
    if (!unresolvedRef.current) {
      setEditing(undefined)
      setSaved(undefined)
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

  if (loading) return <main className="app-shell"><p role="status">Loading waste tracking...</p></main>
  if (!user) return <>{error && <p className="notice error" role="alert">{error}</p>}<LoginPage onLogin={user => { setError(''); setUser(user) }} /></>
  return <><header className="app-header"><div className="header-inner">
    <div className="brand"><span className="brand-mark" aria-hidden="true">RWC</span><div><strong>Resource Work Cooperative</strong><span>Waste tracking</span></div></div>
    <div className="account"><span>{user.username} <span className="role-badge">{user.role === 'ADMIN' ? 'Admin' : 'Staff'}</span></span>
      <button className="secondary" disabled={signingOut || busy || unresolved} onClick={signOut}>{signingOut ? 'Signing out...' : 'Sign out'}</button></div>
  </div></header>
    <main className="app-shell"><nav aria-label="Main navigation">
      <button disabled={busy || signingOut} className={page === 'records' ? 'nav-item active' : 'nav-item'} onClick={showRecords}>Records</button>
      <button disabled={busy || signingOut} className={page === 'entry' && !editing ? 'nav-item active' : 'nav-item'} onClick={newRecord}>{unresolved ? 'Resume submission' : 'New Crate / Pallet'}</button>
    </nav>
      {error && <p className="notice error" role="alert">{error}</p>}
      {unresolved && <p className="notice" role="status">A submission is unresolved. Retry it or check Records. Its details stay in memory while you check.</p>}
      {hasEntry && <div hidden={page !== 'entry'}><CratePalletPage key={entryKey} editing={editing}
        onBusyChange={activityChanged} onUnresolvedChange={unresolvedChanged}
        onSaved={record => {
          setSaved(record)
          setEditing(undefined)
          setHasEntry(false)
          setPage('records')
        }} onCancel={showRecords} /></div>}
      {page === 'records' && <RecordsPage isAdmin={user.role === 'ADMIN'} saved={saved}
        onBusyChange={activityChanged} onEdit={record => {
          if (busyRef.current) return
          if (unresolvedRef.current) {
            setError('Resolve the current submission before correcting another record.')
            return
          }
          setEditing(record)
          setEntryKey(key => key + 1)
          setHasEntry(true)
          setPage('entry')
        }} />}
      <footer>Resource Work Cooperative - Operational dates and times use Australia/Hobart.</footer>
    </main></>
}

export default App
