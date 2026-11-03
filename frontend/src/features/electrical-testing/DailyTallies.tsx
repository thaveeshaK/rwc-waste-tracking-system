import { useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import { submitTally } from './api'
import { processingTypes, typeLabels } from './types'
import type { Category, TallyInput, TallyType } from './types'

interface Props { categories: Category[]; date: string; busy: boolean; onBusy: (value: boolean) => void; onPending: (value: boolean) => void; onUnresolved: (value: boolean) => void; onSaved: () => void }
interface Cell { categoryId: number | null; tallyType: TallyType; count: string }
interface Submission { input: TallyInput; done: boolean }
const keyOf = (categoryId: number | null, type: TallyType) => `${categoryId ?? 'overall'}:${type}`
const validCount = (value: string) => /^\d+$/.test(value) && BigInt(value) <= 2147483647n
export function DailyTallies({categories,date,busy,onBusy,onPending,onUnresolved,onSaved}: Props) {
  const [cells,setCells]=useState<Record<string,Cell>>({})
  const [locked,setLocked]=useState(false)
  const [uncertain,setUncertain]=useState(false)
  const [conflict,setConflict]=useState(false)
  const [error,setError]=useState('')
  const [message,setMessage]=useState('')
  const [completed,setCompleted]=useState(0)
  const queue=useRef<Submission[]|null>(null)
  const submitting=useRef(false)
  const invalid=Object.values(cells).some(c=>!validCount(c.count))
  const pending=Object.values(cells).reduce((sum,c)=>sum+(validCount(c.count)?BigInt(c.count):0n),0n)
  const validDate=/^\d{4}-\d{2}-\d{2}$/.test(date)&&date>='0001-01-01'&&date<=operationalDate()
  function change(categoryId:number|null,tallyType:TallyType,count:string) {
    if(busy||locked)return
    const next={...cells,[keyOf(categoryId,tallyType)]:{categoryId,tallyType,count}}
    setCells(next);setError('');setMessage('')
    onPending(Object.values(next).some(c=>!validCount(c.count)||BigInt(c.count)>0n))
  }
  function reset() {queue.current=null;setCells({});setLocked(false);setUncertain(false);setConflict(false);setCompleted(0);onPending(false);onUnresolved(false)}
  async function save() {
    if(submitting.current||conflict||invalid||!validDate||pending===0n)return
    submitting.current=true;onBusy(true);setError('');setMessage('');setLocked(true)
    queue.current??=Object.values(cells).filter(c=>BigInt(c.count)>0n).map(c=>({done:false,input:{submissionId:crypto.randomUUID(),recordDate:date,categoryId:c.categoryId,tallyType:c.tallyType,count:Number(c.count)}}))
    try {
      for(const row of queue.current) if(!row.done) {await submitTally(row.input);row.done=true;setCells(current=>({...current,[keyOf(row.input.categoryId,row.input.tallyType)]:{categoryId:row.input.categoryId,tallyType:row.input.tallyType,count:'0'}}));setCompleted(queue.current.filter(r=>r.done).length)}
      reset();setMessage('Tallies saved successfully. Pending counts have been reset to zero.');onSaved()
    } catch(e) {
      const definiteConflict=e instanceof ApiError&&e.status===409
      const unknown=!definiteConflict&&(uncertain||!(e instanceof ApiError)||e.status>=500)
      setUncertain(unknown);setConflict(definiteConflict);onUnresolved(unknown)
      setError(`${definiteConflict?'Conflict: ':''}${errorMessage(e)} ${queue.current.filter(r=>r.done).length} of ${queue.current.length} tally records confirmed saved. ${definiteConflict?'Check Records before clearing the remaining session.':'Retry saves only the remaining records using their original submission IDs.'}`)
    } finally {submitting.current=false;onBusy(false)}
  }
  function counter(categoryId:number|null,type:TallyType) {
    const count=cells[keyOf(categoryId,type)]?.count??'0'
    const valid=validCount(count)
    const label=`${categories.find(c=>c.id===categoryId)?.name??'Overall'} - ${typeLabels[type]}`
    return <div className="quick-tally" data-tally={type}><button type="button" className="secondary" aria-label={`Decrease ${label}`} disabled={busy||locked||!valid||BigInt(count)===0n} onClick={()=>change(categoryId,type,String(BigInt(count)-1n))}>-</button>
      <input aria-label={`Pending ${label}`} type="number" inputMode="numeric" min="0" max="2147483647" step="1" value={count} disabled={busy||locked} aria-invalid={!valid} onChange={e=>change(categoryId,type,e.target.value)}/>
      <button type="button" aria-label={`Increase ${label}`} disabled={busy||locked||!valid||BigInt(count)>=2147483647n} onClick={()=>change(categoryId,type,String(BigInt(count)+1n))}>+</button></div>
  }
  return <div className="daily-tallies"><p className="hint">Pending counts only. Minus adjusts this unsaved session and never subtracts saved tallies.</p>
    {error&&<p role="alert" className="notice error">{error}</p>}{message&&<p role="status" className="notice success">{message}</p>}
    <div className="tally-grid"><div className="tally-grid-heading"><span>Category</span>{processingTypes.map(type=><span key={type}>{typeLabels[type]}</span>)}</div>
      {categories.map(category=><section className="testing-category tally-grid-row" key={category.id} data-category={category.id}><h2>{category.name}</h2>{processingTypes.map(type=><div className="tally-grid-cell" key={type}><span className="tally-mobile-label">{typeLabels[type]}</span>{counter(category.id,type)}</div>)}</section>)}
    </div>
    <section className="testing-diversions"><h2>Overall diversions</h2><p className="hint">Not tied to an electrical item category.</p><div className="quick-diversions">{(['DIVERTED_TO_OLS','DIVERTED_TO_COLL'] as const).map(type=><div key={type}><h3>{typeLabels[type]}</h3>{counter(null,type)}</div>)}</div></section>
    <div className="tally-save"><p aria-live="polite">{pending.toString()} tally items pending{invalid&&' - enter whole numbers from 0 to 2147483647.'}{locked&&` / ${completed} records confirmed saved`}</p>
      <button disabled={busy||conflict||invalid||pending===0n||!validDate||categories.length===0} onClick={save}>{busy?'Saving tallies...':locked?'Retry Save Tallies':'Save Tallies'}</button>
      {locked&&<span className="hint">Already saved records remain saved. This session resumes the remaining records.</span>}
      {locked&&!uncertain&&!busy&&error&&<button className="secondary" onClick={()=>{reset();setError('');setMessage('Remaining session cleared. Previously saved tallies were not changed.');onSaved()}}>Clear remaining session</button>}
    </div>
  </div>
}
