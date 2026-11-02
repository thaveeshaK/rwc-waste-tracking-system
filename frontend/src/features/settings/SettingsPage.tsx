import { useEffect, useState } from 'react'
import { getSettings, getReferences, saveSettings } from './api'
import type { Configuration, Reference } from './types'
import { errorMessage } from '../../api/client'
import { ReferenceSettings } from './ReferenceSettings'
import { ConfigurationForm } from './ConfigurationForm'
interface Data { configuration: Configuration; containers: Reference[]; materials: Reference[] }
export function SettingsPage({onChanged}: {onChanged: () => void}) {
  const [data, setData] = useState<Data | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [attempt, setAttempt] = useState(0)
  async function load() {
    const [configuration, containers, materials] = await Promise.all([getSettings(), getReferences(true), getReferences(false)])
    setData({configuration, containers, materials}); setError('')
  }
  useEffect(() => {
    let cancelled = false
    Promise.all([getSettings(), getReferences(true), getReferences(false)]).then(([configuration, containers, materials]) => { if (!cancelled) {setData({configuration, containers, materials}); setError('')} }).catch(e => { if (!cancelled) setError(errorMessage(e)) })
    return () => { cancelled = true }
  }, [attempt])
  async function save(patch: Partial<Configuration>) {
    if (!data) return
    const configuration = await saveSettings({...data.configuration, ...patch})
    setData({...data, configuration}); setMessage('Settings saved successfully. Other configuration forms have refreshed.'); onChanged()
  }
  return <section><div className="page-heading"><div><p className="eyebrow">ADMIN</p><h1>Settings</h1><p>Future-entry configuration. Historical measurements and material identities remain unchanged. Save one configuration section before editing another; saving refreshes the other forms.</p></div><button className="secondary" onClick={() => {setData(null); setAttempt(n => n+1)}}>Reload settings</button></div>
    {message && <p role="status" className="notice success">{message}</p>}
    {error && <p role="alert" className="notice error">{error}</p>}
    {!data && !error && <p role="status">Loading settings...</p>}
    {data && <><ReferenceSettings container rows={data.containers} onSaved={load}/><ReferenceSettings container={false} rows={data.materials} onSaved={load}/>
      <ConfigurationForm key={'organisation-'+data.configuration.version} kind="organisation" configuration={data.configuration} onSave={save}/><ConfigurationForm key={'reporting-'+data.configuration.version} kind="reporting" configuration={data.configuration} onSave={save}/><ConfigurationForm key={'whitegoods-'+data.configuration.version} kind="whitegoods" configuration={data.configuration} onSave={save}/>
      {data.configuration.updatedBy && <p className="hint">Organisation/reporting/factor last updated by {data.configuration.updatedBy} at {data.configuration.updatedAt}.</p>}</>}
  </section>
}
