import { useEffect, useState } from 'react'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import { getReferences, getSummary } from './api'
import { DailyTallies } from './DailyTallies'
import type { Category, MonthlySummary, SalesReference } from './types'
interface Props {
  categories:Category[];date:string;month:string;onDate:(date:string)=>void;onMonth:(month:string)=>void;isAdmin:boolean;refresh:number
  onReference:(category:Category,reference?:SalesReference)=>void;onDeleteReference:(reference:SalesReference)=>void;busy:boolean
  onBusy:(value:boolean)=>void;onPending:(value:boolean)=>void;onUnresolved:(value:boolean)=>void;onSaved:()=>void
}
export function TestingBoard({categories,date,month,onDate,onMonth,isAdmin,refresh,onReference,onDeleteReference,busy,onBusy,onPending,onUnresolved,onSaved}:Props) {
  const [tab,setTab]=useState<'daily'|'sales'>('daily')
  const [hasPending,setHasPending]=useState(false)
  const [attempt,setAttempt]=useState(0)
  const tallyMonth=date.slice(0,7)
  const summaryMonth=tab==='daily'?tallyMonth:month
  const key=`${month}:${summaryMonth}:${refresh}:${attempt}`
  const [result,setResult]=useState<{key:string;references?:SalesReference[];summary?:MonthlySummary;error?:string}|null>(null)
  useEffect(()=>{let cancelled=false;Promise.all([getReferences(month),getSummary(summaryMonth)]).then(([references,summary])=>{if(!cancelled)setResult({key,references,summary})}).catch(e=>{if(!cancelled)setResult({key,error:errorMessage(e)})});return()=>{cancelled=true}},[key,month,summaryMonth])
  const current=result?.key===key?result:null
  return <div className="testing-board"><div className="testing-tabs" aria-label="Electrical Testing sections"><button className={tab==='daily'?'':'secondary'} aria-pressed={tab==='daily'} disabled={busy} onClick={()=>setTab('daily')}>Daily Tallies</button><button className={tab==='sales'?'':'secondary'} aria-pressed={tab==='sales'} disabled={busy} onClick={()=>setTab('sales')}>Monthly Sales Reference</button></div>
    <div hidden={tab!=='daily'}><div className="tally-period"><label className="tally-date">Tally month<input type="month" required min="0001-01" max={operationalDate().slice(0,7)} value={tallyMonth} disabled={busy||hasPending} onChange={e=>{const value=e.target.value;if(value&&value<=operationalDate().slice(0,7))onDate(value===operationalDate().slice(0,7)?operationalDate():`${value}-01`)}}/></label>
      <label className="tally-date">Tally date<input type="date" required min="0001-01-01" max={operationalDate()} value={date} disabled={busy||hasPending} onChange={e=>{if(e.target.value&&e.target.value<=operationalDate())onDate(e.target.value)}}/></label></div>
      {hasPending&&<p className="hint">This session uses {date}. Save its tallies before changing the month or date.</p>}
      {categories.length===0&&<p role="status">Loading Electrical Testing categories...</p>}
      <DailyTallies key={date} categories={categories} date={date} month={tallyMonth} isAdmin={isAdmin} refresh={refresh+attempt} busy={busy} onBusy={onBusy} onPending={value=>{setHasPending(value);onPending(value)}} onUnresolved={onUnresolved} onSaved={onSaved}/>
    </div>
    <div hidden={tab!=='sales'} className="panel"><h2>Monthly Sales Reference</h2><label>Reporting month<input type="month" required min="0001-01" max={operationalDate().slice(0,7)} value={month} disabled={busy} onChange={e=>{if(e.target.value)onMonth(e.target.value)}}/></label>
      <p className="hint">Previous calendar month's sales for {month}. Missing means Not entered; an explicit 0 means zero sales. These values are separate from live tallies.</p>
      {hasPending&&<p className="notice">Save pending Daily Tallies before submitting or correcting sales references.</p>}
      {!current?<p role="status">Loading sales references...</p>:current.error?<p role="alert" className="notice error">{current.error}</p>:<div className="table-scroll"><table><thead><tr><th>Category</th><th>Sold Last Month</th><th>Action</th></tr></thead><tbody>{categories.map(category=>{const reference=current.references?.find(r=>r.categoryId===category.id);return <tr key={category.id} data-reference-category={category.id}><td>{category.name}</td><td className="sales-reference"><strong>{reference?reference.soldLastMonthCount:'Not entered'}</strong></td><td>{!reference?<button className="secondary" disabled={busy||hasPending} onClick={()=>onReference(category)}>Enter sales reference</button>:isAdmin?<div className="actions"><button className="secondary" disabled={busy||hasPending} onClick={()=>onReference(category,reference)}>Correct sales reference</button><button className="danger secondary" disabled={busy||hasPending} onClick={()=>onDeleteReference(reference)}>Delete sales reference</button></div>:<span className="hint">Read-only for Staff</span>}</td></tr>})}</tbody></table></div>}
    </div>
    <details className="panel testing-summary"><summary>Saved monthly operational totals</summary><p className="hint">Saved additive counts for {summaryMonth}; not pending counts or stock balances. Sold Last Month is excluded.</p>{current?.summary?<><div className="table-scroll"><table><thead><tr><th>Category</th><th>Untested / Broken / Failed</th><th>Tested and Priced</th><th>Stock Cull</th></tr></thead><tbody>{current.summary.categories.map(c=><tr key={c.categoryId} data-summary-category={c.categoryId}><td>{c.categoryName}</td><td>{c.untestedBrokenFailed}</td><td>{c.testedAndPriced}</td><td>{c.stockCull}</td></tr>)}</tbody></table></div><p>Diverted to OLS: {current.summary.divertedToOls}. Diverted to Coll: {current.summary.divertedToColl}.</p></>:<p>{current?.error??'Loading saved totals...'}</p>}</details>
    <button className="secondary" disabled={busy||hasPending} onClick={()=>setAttempt(n=>n+1)}>Refresh counters</button>
  </div>
}
