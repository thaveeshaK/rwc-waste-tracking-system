import {UserManagementPage} from '../users/UserManagementPage'
import { useEffect, useState } from 'react'
import { getSettings, getReferences, saveSettings } from './api'
import type { Configuration, Reference } from './types'
import { errorMessage } from '../../api/client'
import { ReferenceSettings } from './ReferenceSettings'
import { ConfigurationForm } from './ConfigurationForm'
import { settingsSections } from './sections'
import type { SettingsSection } from './sections'
interface Data { configuration?: Configuration; rows?: Reference[] }
export function SettingsPage({section,onChanged}: {section: SettingsSection;onChanged: () => void}) {
  const [data, setData] = useState<Data | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [attempt, setAttempt] = useState(0)
  const title=settingsSections.find(s=>s.id===section)!.label
  async function fetchSection(): Promise<Data> {
    return section==='container-types'||section==='material-categories' ? {rows:await getReferences(section==='container-types')} : {configuration:await getSettings()}
  }
  async function load() {setData(await fetchSection());setError('')}
  useEffect(() => {
    if(section==='users')return
    let cancelled=false
    const request=section==='container-types'||section==='material-categories' ? getReferences(section==='container-types').then(rows=>({rows})) : getSettings().then(configuration=>({configuration}))
    request.then(value=>{if(!cancelled){setData(value);setError('')}}).catch(e=>{if(!cancelled)setError(errorMessage(e))})
    return()=>{cancelled=true}
  }, [section,attempt])
  async function save(patch: Partial<Configuration>) {
    if(!data?.configuration)return
    const configuration=await saveSettings({...data.configuration,...patch})
    setData({configuration});setMessage('Settings saved successfully.');onChanged()
  }
  return <section className="settings-page" data-settings-section={section}><div className="page-heading"><div><p className="eyebrow">Settings</p><h1>{title}</h1></div>{section!=='users'&&<button className="secondary" onClick={()=>{setData(null);setError('');setMessage('');setAttempt(n=>n+1)}}>Reload settings</button>}</div>
    {section==='users'?<UserManagementPage/>:<>
      {message&&<p role="status" className="notice success">{message}</p>}{error&&<p role="alert" className="notice error">{error}</p>}
      {!data&&!error&&<p role="status">Loading settings...</p>}
      {data?.rows&&<ReferenceSettings container={section==='container-types'} rows={data.rows} onSaved={load}/>}
      {data?.configuration&&(section==='organisation'||section==='reporting'||section==='whitegoods')&&<><ConfigurationForm key={data.configuration.version} kind={section} configuration={data.configuration} onSave={save}/>
        {data.configuration.updatedBy&&<p className="hint">Last updated by {data.configuration.updatedBy} at {data.configuration.updatedAt}.</p>}</>}
    </>}
  </section>
}
