import { useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../../api/client'
import { login } from './api'
import type { User } from './api'

export function LoginPage({ onLogin }: { onLogin: (user: User) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try { onLogin(await login(username, password)); setPassword('') }
    catch (error) { setError(errorMessage(error)) }
    finally { setBusy(false) }
  }
  return <main className="login-layout">
    <div className="login-intro"><p className="eyebrow">RESOURCE WORK COOPERATIVE</p>
      <h1>Waste tracking</h1><p>Record incoming materials and keep your operational records together.</p></div>
    <form className="panel login-panel" onSubmit={submit}>
      <h2>Sign in</h2><p>Use the shared Staff account or your individual Admin account.</p>
      {error && <p className="notice error" role="alert">{error}</p>}
      <label>Username<input autoComplete="username" required maxLength={100} value={username}
        onChange={event => setUsername(event.target.value)} /></label>
      <label>Password<input type="password" autoComplete="current-password" required value={password}
        onChange={event => setPassword(event.target.value)} /></label>
      <button disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
    </form>
  </main>
}
