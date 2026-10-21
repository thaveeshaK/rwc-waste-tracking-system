import { useEffect, useState } from 'react'
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
  const [signingOut, setSigningOut] = useState(false)

  useEffect(() => {
    let cancelled = false
    currentUser().then(user => { if (!cancelled) setUser(user) }).catch(error => {
      if (!cancelled && (!(error instanceof ApiError) || error.status !== 401)) setError(errorMessage(error))
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  function newRecord() {
    setEditing(undefined); setSaved(undefined); setEntryKey(key => key + 1); setPage('entry')
  }
  async function signOut() {
    setSigningOut(true); setError('')
    try { await logout(); setUser(null); setEditing(undefined); setSaved(undefined); setPage('records') }
    catch (error) { setError(errorMessage(error)) }
    finally { setSigningOut(false) }
  }

  if (loading) return <main className="app-shell"><p role="status">Loading waste tracking?</p></main>
  if (!user) return <>{error && <p className="notice error" role="alert">{error}</p>}<LoginPage onLogin={user => { setError(''); setUser(user) }} /></>
  return <><header className="app-header"><div className="header-inner">
    <div className="brand"><span className="brand-mark" aria-hidden="true">RWC</span><div><strong>Resource Work Cooperative</strong><span>Waste tracking</span></div></div>
    <div className="account"><span>{user.username} <span className="role-badge">{user.role === 'ADMIN' ? 'Admin' : 'Staff'}</span></span>
      <button className="secondary" disabled={signingOut} onClick={signOut}>{signingOut ? 'Signing out…' : 'Sign out'}</button></div>
  </div></header>
    <main className="app-shell"><nav aria-label="Main navigation">
      <button className={page === 'records' ? 'nav-item active' : 'nav-item'} onClick={() => { setPage('records'); setSaved(undefined) }}>Records</button>
      <button className={page === 'entry' && !editing ? 'nav-item active' : 'nav-item'} onClick={newRecord}>New Crate / Pallet</button>
    </nav>
      {error && <p className="notice error" role="alert">{error}</p>}
      {page === 'entry' ? <CratePalletPage key={entryKey} editing={editing}
        onSaved={record => { setSaved(record); setEditing(undefined); setPage('records') }} onCancel={() => setPage('records')} />
        : <RecordsPage isAdmin={user.role === 'ADMIN'} saved={saved} onEdit={record => {
          setEditing(record); setEntryKey(key => key + 1); setPage('entry')
        }} />}
      <footer>Resource Work Cooperative · Operational dates and times use Australia/Hobart.</footer>
    </main></>
}

export default App
