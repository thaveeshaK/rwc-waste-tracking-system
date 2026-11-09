import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import { getRecords, getSummary, reduceTally, submitTally } from './api'
import { processingTypes, typeLabels } from './types'
import type { Category, MonthlySummary, TallyInput, TallyRecord, TallyType } from './types'

interface Props { categories: Category[]; date: string; month: string; isAdmin: boolean; refresh: number; busy: boolean; onBusy: (value: boolean) => void; onPending: (value: boolean) => void; onUnresolved: (value: boolean) => void; onSaved: () => void }
interface Cell { categoryId: number | null; tallyType: TallyType; count: string }
type Submission = { done: boolean; quantity: number } & ({ kind: 'add'; input: TallyInput } | { kind: 'reduce'; id: number; input: {recordDate:string;version:number;count:number} })
const keyOf = (categoryId: number | null, type: TallyType) => `${categoryId ?? 'overall'}:${type}`
const validCount = (value: string) => /^\d+$/.test(value) && BigInt(value) <= BigInt(Number.MAX_SAFE_INTEGER)
const abs = (value:bigint) => value<0n?-value:value
function monthCount(summary:MonthlySummary, category:number|null, type:TallyType):bigint {
  if(type==='DIVERTED_TO_OLS')return BigInt(summary.divertedToOls)
  if(type==='DIVERTED_TO_COLL')return BigInt(summary.divertedToColl)
  const row=summary.categories.find(c=>c.categoryId===category)
  return BigInt(row?(type==='UNTESTED_BROKEN_FAILED'?row.untestedBrokenFailed:type==='TESTED_AND_PRICED'?row.testedAndPriced:row.stockCull):0)
}
export function DailyTallies({categories,date,month,isAdmin,refresh,busy,onBusy,onPending,onUnresolved,onSaved}: Props) {
  // Values are selected-date targets. Opening a month/date never writes records.
  const [cells,setCells]=useState<Record<string,Cell>>({})
  const [locked,setLocked]=useState(false)
  const [uncertain,setUncertain]=useState(false)
  const [conflict,setConflict]=useState(false)
  const [error,setError]=useState('')
  const [message,setMessage]=useState('')
  const [completed,setCompleted]=useState(0)
  const [remaining,setRemaining]=useState<bigint|null>(null)
  const [attempt,setAttempt]=useState(0)
  const key=`${month}:${date}:${refresh}:${attempt}`
  const [result,setResult]=useState<{key:string;summary?:MonthlySummary;records?:TallyRecord[];error?:string}|null>(null)
  const current=result?.key===key?result:null
  const queue=useRef<Submission[]|null>(null)
  const submitting=useRef(false)
  useEffect(()=>{
    let cancelled=false
    Promise.all([getSummary(month),getRecords(date,date)]).then(([summary,records])=>{if(!cancelled)setResult({key,summary,records})})
      .catch(e=>{if(!cancelled)setResult({key,error:errorMessage(e)})})
    return()=>{cancelled=true}
  },[key,month,date])
  const records=current?.records??[]
  const dateCount=(category:number|null,type:TallyType)=>records.filter(r=>r.categoryId===category&&r.tallyType===type).reduce((sum,r)=>sum+BigInt(r.count),0n)
  const invalid=Object.values(cells).some(c=>!validCount(c.count)||abs(BigInt(c.count)-dateCount(c.categoryId,c.tallyType))>2147483647n)
  const pending=remaining??Object.values(cells).reduce((sum,c)=>sum+(validCount(c.count)?abs(BigInt(c.count)-dateCount(c.categoryId,c.tallyType)):0n),0n)
  const validDate=/^\d{4}-\d{2}-\d{2}$/.test(date)&&date>='0001-01-01'&&date<=operationalDate()&&date.slice(0,7)===month
  const editable=validDate&&(isAdmin||date===operationalDate())
  const ready=!!current?.summary&&!!current.records
  const dateLabel=date===operationalDate()?'Today':new Intl.DateTimeFormat('en-AU',{day:'2-digit',month:'short',timeZone:'Australia/Hobart'}).format(new Date(`${date}T12:00:00+10:00`))
  function change(categoryId:number|null,tallyType:TallyType,count:string) {
    if(busy||locked||!editable||!ready)return
    const next={...cells,[keyOf(categoryId,tallyType)]:{categoryId,tallyType,count}}
    // A cancelled adjustment must not become a correction after a later refresh.
    if(validCount(count)&&BigInt(count)===dateCount(categoryId,tallyType))delete next[keyOf(categoryId,tallyType)]
    setCells(next);setError('');setMessage('')
    onPending(Object.values(next).some(c=>!validCount(c.count)||BigInt(c.count)!==dateCount(c.categoryId,c.tallyType)))
  }
  function reset() {queue.current=null;setCells({});setLocked(false);setUncertain(false);setConflict(false);setCompleted(0);setRemaining(null);onPending(false);onUnresolved(false)}
  function prepare():Submission[] {
    const requests:Submission[]=[]
    for(const cell of Object.values(cells)) {
      const delta=BigInt(cell.count)-dateCount(cell.categoryId,cell.tallyType)
      if(delta>0n) {
        requests.push({kind:'add',done:false,quantity:Number(delta),input:{submissionId:crypto.randomUUID(),recordDate:date,categoryId:cell.categoryId,tallyType:cell.tallyType,count:Number(delta)}})
      } else if(delta<0n) {
        let remaining=-delta
        for(const row of records.filter(r=>r.categoryId===cell.categoryId&&r.tallyType===cell.tallyType).sort((a,b)=>b.id!-a.id!)) {
          if(remaining===0n)break
          const reduction=remaining>BigInt(row.count)?BigInt(row.count):remaining
          requests.push({kind:'reduce',done:false,quantity:Number(reduction),id:row.id!,input:{recordDate:date,version:row.version!,count:row.count-Number(reduction)}})
          remaining-=reduction
        }
      }
    }
    return requests
  }
  async function save() {
    if(submitting.current||conflict||invalid||!editable||!ready||pending===0n)return
    submitting.current=true;onBusy(true);setError('');setMessage('');setLocked(true)
    queue.current??=prepare()
    setRemaining(queue.current.filter(row=>!row.done).reduce((sum,row)=>sum+BigInt(row.quantity),0n))
    try {
      for(const row of queue.current) if(!row.done) {
        if(row.kind==='add')await submitTally(row.input);else await reduceTally(row.id,row.input)
        row.done=true;setCompleted(queue.current.filter(r=>r.done).length)
        setRemaining(queue.current.filter(r=>!r.done).reduce((sum,r)=>sum+BigInt(r.quantity),0n))
      }
      reset();setMessage('Tallies saved successfully. Monthly totals and selected-date contributions have been refreshed.');onSaved()
    } catch(e) {
      const definiteConflict=e instanceof ApiError&&e.status===409
      const unknown=!definiteConflict&&(uncertain||!(e instanceof ApiError)||e.status>=500)
      setUncertain(unknown);setConflict(definiteConflict);onUnresolved(unknown)
      setError(`${definiteConflict?'Conflict: ':''}${errorMessage(e)} ${queue.current.filter(r=>r.done).length} of ${queue.current.length} tally records confirmed saved. ${definiteConflict?'Check Records before clearing the remaining session.':'Retry saves only remaining operations using their original IDs and versions.'}`)
    } finally {submitting.current=false;onBusy(false)}
  }
  function counter(categoryId:number|null,type:TallyType) {
    const savedDay=dateCount(categoryId,type)
    const count=cells[keyOf(categoryId,type)]?.count??savedDay.toString()
    const valid=validCount(count)
    const savedMonth=current?.summary?monthCount(current.summary,categoryId,type):0n
    const display=valid?savedMonth-savedDay+BigInt(count):savedMonth
    const label=`${categories.find(c=>c.id===categoryId)?.name??'Overall'} - ${typeLabels[type]}`
    const disabled=busy||locked||!editable||!ready
    return <div className="tally-counter"><div className="quick-tally" data-tally={type}>
      <button type="button" className="secondary" aria-label={`Decrease ${label}`} disabled={disabled||!valid||BigInt(count)===0n} onClick={()=>change(categoryId,type,String(BigInt(count)-1n))}>-</button>
      <input aria-label={`Monthly total ${label}`} type="text" inputMode="numeric" value={valid?display.toString():count} disabled={disabled} aria-invalid={!valid} onChange={e=>{
        const value=e.target.value
        change(categoryId,type,/^\d+$/.test(value)?(BigInt(value)-savedMonth+savedDay).toString():value)
      }}/>
      <button type="button" aria-label={`Increase ${label}`} disabled={disabled||!valid||BigInt(count)>=BigInt(Number.MAX_SAFE_INTEGER)} onClick={()=>change(categoryId,type,String(BigInt(count)+1n))}>+</button>
    </div><small className="tally-date-contribution">{dateLabel}: {valid?count:'—'}</small></div>
  }
  return <div className="daily-tallies"><p className="hint">Counters show selected-month totals. Changes apply only to the selected date and are not saved until Save Tallies.</p>
    {!isAdmin&&!editable&&<p className="notice">Historical tallies are read-only for Staff. Only today's tally can be changed.</p>}
    {!current&&<p role="status">Loading tally totals...</p>}
    {current?.error&&<p role="alert" className="notice error">{current.error} <button disabled={busy||pending>0n||locked} onClick={()=>setAttempt(n=>n+1)}>Retry totals</button></p>}
    {error&&<p role="alert" className="notice error">{error}</p>}{message&&<p role="status" className="notice success">{message}</p>}
    <div className="tally-grid"><div className="tally-grid-heading"><span>Category</span>{processingTypes.map(type=><span key={type}>{typeLabels[type]}</span>)}</div>
      {categories.map(category=><section className="testing-category tally-grid-row" key={category.id} data-category={category.id}><h2>{category.name}</h2>{processingTypes.map(type=><div className="tally-grid-cell" key={type}><span className="tally-mobile-label">{typeLabels[type]}</span>{counter(category.id,type)}</div>)}</section>)}
    </div>
    <section className="testing-diversions"><h2>Overall diversions</h2><p className="hint">Not tied to an electrical item category.</p><div className="quick-diversions">{(['DIVERTED_TO_OLS','DIVERTED_TO_COLL'] as const).map(type=><div key={type}><h3>{typeLabels[type]}</h3>{counter(null,type)}</div>)}</div></section>
    <div className="tally-save"><p aria-live="polite">{pending.toString()} tally adjustments pending{invalid&&' - use whole totals no lower than other dates combined; each cell adjustment must be at most 2147483647.'}{locked&&` / ${completed} records confirmed saved`}</p>
      <button disabled={busy||conflict||invalid||pending===0n||!editable||!ready||categories.length===0} onClick={save}>{busy?'Saving tallies...':locked?'Retry Save Tallies':'Save Tallies'}</button>
      {locked&&<span className="hint">Already saved records remain saved. This session resumes the remaining operations.</span>}
      {locked&&!uncertain&&!busy&&error&&<button className="secondary" onClick={()=>{reset();setError('');setMessage('Remaining session cleared. Previously saved tallies were not changed.');onSaved()}}>Clear remaining session</button>}
    </div>
  </div>
}
