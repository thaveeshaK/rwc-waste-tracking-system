import { useEffect, useState } from 'react'
import { errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import { getCategories, getReferences, getSummary } from './api'
import { processingTypes, typeLabels } from './types'
import type { Category, MonthlySummary, SalesReference, TallyType } from './types'
interface Props {
  date:string; month:string; onDate:(date:string)=>void; onMonth:(month:string)=>void; isAdmin:boolean; refresh:number
  onTally:(category:Category|null,type:TallyType)=>void; onReference:(category:Category,reference?:SalesReference)=>void
  onDeleteReference:(reference:SalesReference)=>void; busy:boolean
}
export function TestingBoard({date,month,onDate,onMonth,isAdmin,refresh,onTally,onReference,onDeleteReference,busy}:Props) {
  const [attempt,setAttempt]=useState(0)
  const key=`${month}:${refresh}:${attempt}`
  const [result,setResult]=useState<{key:string;categories?:Category[];references?:SalesReference[];summary?:MonthlySummary;error?:string}|null>(null)
  useEffect(()=>{
    let cancelled=false
    Promise.all([getCategories(),getReferences(month),getSummary(month)]).then(([categories,references,summary])=>{
      if(!cancelled)setResult({key,categories,references,summary})
    }).catch(error=>{if(!cancelled)setResult({key,error:errorMessage(error)})})
    return()=>{cancelled=true}
  },[key,month])
  const current=result?.key===key?result:null
  const previousMonth=(()=>{const [year,m]=month.split('-').map(Number);return `${m===1?year-1:year}-${String(m===1?12:m-1).padStart(2,'0')}`})()
  return <div><div className="panel"><div className="form-grid">
    <label>Tally date<input type="date" min="0001-01-01" max={operationalDate()} value={date} disabled={busy} onChange={event=>{if(event.target.value)onDate(event.target.value)}} /></label>
    <label>Reporting month<input type="month" min="0001-01" max={operationalDate().slice(0,7)} value={month} disabled={busy} onChange={event=>{if(event.target.value)onMonth(event.target.value)}} /></label></div>
    <p className="hint">Saved totals below are for {month}. Sold Last Month values describe sales in {previousMonth}. New tallies use {date}.</p>
    <button className="secondary" disabled={busy} onClick={()=>setAttempt(value=>value+1)}>Refresh counters</button></div>
    {!current?<p role="status">Loading Electrical Testing counters...</p>:current.error?<p role="alert" className="notice error">{current.error}</p>:<>
      <div className="form-grid testing-categories">{current.categories?.map(category=>{
        const reference=current.references?.find(row=>row.categoryId===category.id)
        const totals=current.summary?.categories.find(row=>row.categoryId===category.id)
        const quantities=[totals?.untestedBrokenFailed??0,totals?.testedAndPriced??0,totals?.stockCull??0]
        return <section className="panel testing-category" data-category={category.id} key={category.id}><h2>{category.name}</h2>
          <p className="sales-reference">Sold Last Month: <strong>{reference?reference.soldLastMonthCount:'Not entered'}</strong></p>
          {!reference?<button className="secondary" disabled={busy} onClick={()=>onReference(category)}>Enter sales reference</button>:isAdmin?<div className="actions">
            <button className="secondary" disabled={busy} onClick={()=>onReference(category,reference)}>Correct sales reference</button>
            <button className="danger secondary" disabled={busy} onClick={()=>onDeleteReference(reference)}>Delete sales reference</button></div>:<p className="hint">Submitted sales reference is read-only for Staff.</p>}
          <div className="testing-counters">{processingTypes.map((type,index)=><div key={type}><p>{typeLabels[type]}: <strong>{quantities[index]}</strong></p>
            <button disabled={busy} onClick={()=>onTally(category,type)}>+ {typeLabels[type]}</button></div>)}</div>
        </section>
      })}</div>
      <section className="panel testing-diversions"><h2>Overall diversions</h2><p className="hint">Not tied to an electrical item category.</p>
        <p>Diverted to OLS: <strong>{current.summary?.divertedToOls??0}</strong></p><button disabled={busy} onClick={()=>onTally(null,'DIVERTED_TO_OLS')}>+ Diverted to OLS</button>
        <p>Diverted to Coll: <strong>{current.summary?.divertedToColl??0}</strong></p><button disabled={busy} onClick={()=>onTally(null,'DIVERTED_TO_COLL')}>+ Diverted to Coll</button></section>
    </>}
  </div>
}
