import {useEffect,useState} from 'react'
import type {FormEvent} from 'react'
import {decimalHundredths,operationalDate} from '../../utils/decimal'
import {errorMessage} from '../../api/client'
import {monthlySummary} from './api'
import {materials} from './types'
import type {ScrapInput,ScrapTotals} from './types'
interface Props {value: ScrapInput; busy: boolean; onChange:(value: ScrapInput)=>void; onReview:()=>void}
export function ScrapForm({value,busy,onChange,onReview}: Props) {
  const [summary,setSummary]=useState<ScrapTotals|null>(null)
  const [error,setError]=useState('')
  const [attempt,setAttempt]=useState(0)
  useEffect(()=>{let cancelled=false;monthlySummary(value.reportingMonth).then(r=>{if(!cancelled){setSummary(r);setError('')}}).catch(e=>{if(!cancelled)setError(errorMessage(e))});return()=>{cancelled=true}},[value.reportingMonth,attempt])
  const amounts=materials.map(m=>decimalHundredths(value[m.key]))
  const currentMonth=operationalDate().slice(0,7)
  const validMonth=/^\d{4}-(0[1-9]|1[0-2])$/.test(value.reportingMonth)&&value.reportingMonth>='0001-01'&&value.reportingMonth<=currentMonth
  const valid=validMonth&&amounts.every(v=>v!==null&&v<=9999999999n)&&amounts.some(v=>v!==null&&v>0n)
  function submit(e: FormEvent){e.preventDefault();if(valid&&!busy)onReview()}
  return <><form onSubmit={submit}><fieldset disabled={busy}><legend>Monthly Scrap entry</legend><div className="form-grid"><label>Reporting month<input type="month" required min="0001-01" max={currentMonth} value={value.reportingMonth} onChange={e=>onChange({...value,reportingMonth:e.target.value})}/></label>{materials.map(m=><label key={m.key}>{m.label} (kg)<input required type="number" inputMode="decimal" min="0" max="99999999.99" step="0.01" value={value[m.key]} onChange={e=>onChange({...value,[m.key]:e.target.value})}/></label>)}</div><p className="hint">Zero is valid for individual materials. At least one weight must be greater than zero. Multiple entries for a month are added together.</p><button disabled={busy||!valid}>{busy?'Calculating...':'Review record'}</button></fieldset></form>
    <details className="report-section"><summary>Selected month totals</summary>{error?<><p role="alert">{error}</p><button className="secondary" disabled={busy} onClick={()=>setAttempt(n=>n+1)}>Refresh monthly totals</button></>:summary?.reportingMonth===value.reportingMonth?<dl className="record-facts">{materials.map(m=><div key={m.key}><dt>{m.label}</dt><dd>{summary[m.key]} kg</dd></div>)}<div><dt>Monthly total</dt><dd>{summary.totalWeight} kg</dd></div></dl>:<p>Loading monthly totals...</p>}</details></>
}
