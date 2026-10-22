import { useEffect, useRef, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import { operationalDate } from '../../utils/decimal'
import * as api from './api'
import { TestingBoard } from './TestingBoard'
import { TallyReview } from './TallyReview'
import { typeLabels } from './types'
import type { Category, ReferenceInput, SalesReference, TallyInput, TallyRecord, TallyType } from './types'
type FormState = {kind:'tally'; input:Omit<TallyInput,'count'>&{count:string}; editing?:TallyRecord;categoryName:string|null}
  | {kind:'reference'; input:Omit<ReferenceInput,'soldLastMonthCount'>&{soldLastMonthCount:string};editing?:SalesReference;categoryName:string}
type Pending = {kind:'tally';input:TallyInput;id?:number}|{kind:'reference';input:ReferenceInput;id?:number}
type Review = {kind:'tally';record:TallyRecord}|{kind:'reference';record:SalesReference}
interface Props { editing?:TallyRecord;isAdmin:boolean;onSaved:(record:TallyRecord)=>void;onCancel:()=>void;onBusyChange:(busy:boolean)=>void;onUnresolvedChange:(value:boolean)=>void }
export function ElectricalTestingPage({editing,isAdmin,onSaved,onCancel,onBusyChange,onUnresolvedChange}:Props) {
  const [date,setDate]=useState(()=>editing?.recordDate??operationalDate())
  const [month,setMonth]=useState(()=>date.slice(0,7))
  const [form,setForm]=useState<FormState|null>(()=>editing?{kind:'tally',editing,categoryName:editing.categoryName,input:{submissionId:editing.submissionId!,version:editing.version!,recordDate:editing.recordDate,categoryId:editing.categoryId,tallyType:editing.tallyType,count:String(editing.count)}}:null)
  const [categories,setCategories]=useState<Category[]>([])
  const [categoryAttempt,setCategoryAttempt]=useState(0)
  const [categoryError,setCategoryError]=useState('')
  const [review,setReview]=useState<Review|null>(null)
  const [busy,setBusy]=useState(false)
  const [uncertain,setUncertain]=useState(false)
  const [conflict,setConflict]=useState(false)
  const [error,setError]=useState('')
  const [message,setMessage]=useState('')
  const [refresh,setRefresh]=useState(0)
  const [deleting,setDeleting]=useState<SalesReference|null>(null)
  const pending=useRef<Pending|null>(null)
  const submitting=useRef(false)
  useEffect(()=>{let cancelled=false;api.getCategories().then(rows=>{if(!cancelled)setCategories(rows)}).catch(error=>{if(!cancelled)setCategoryError(errorMessage(error))});return()=>{cancelled=true}},[categoryAttempt])
  function activity(value:boolean){setBusy(value);onBusyChange(value)}
  function openTally(category:Category|null,tallyType:TallyType){setError('');setMessage('');setReview(null);setConflict(false);setForm({kind:'tally',categoryName:category?.name??null,input:{submissionId:crypto.randomUUID(),recordDate:date,categoryId:category?.id??null,tallyType,count:'1'}})}
  function openReference(category:Category,row?:SalesReference){setError('');setMessage('');setReview(null);setConflict(false);setForm({kind:'reference',categoryName:category.name,editing:row,input:{submissionId:row?.submissionId??crypto.randomUUID(),version:row?.version??undefined,reportingMonth:row?.reportingMonth??month,categoryId:category.id,soldLastMonthCount:row?String(row.soldLastMonthCount):''}})}
  function payload():Pending {
    if(!form)throw new Error('No active counter')
    return form.kind==='tally'?{kind:'tally',id:form.editing?.id??undefined,input:{...form.input,count:Number(form.input.count)}}
      :{kind:'reference',id:form.editing?.id??undefined,input:{...form.input,soldLastMonthCount:Number(form.input.soldLastMonthCount)}}
  }
  async function calculate(){activity(true);setError('');try{const request=payload();setReview(request.kind==='tally'?{kind:'tally',record:await api.previewTally(request.input,request.id)}:{kind:'reference',record:await api.previewReference(request.input,request.id)})}catch(error){setError(errorMessage(error));if(error instanceof ApiError&&error.status===409)setConflict(true)}finally{activity(false)}}
  async function save(){if(submitting.current)return;submitting.current=true;activity(true);setError('');pending.current??=payload()
    try{const request=pending.current
      if(request.kind==='tally'){const saved=request.id?await api.updateTally(request.id,request.input):await api.submitTally(request.input);if(editing){onUnresolvedChange(false);onSaved(saved);return}}
      else {if(request.id)await api.updateReference(request.id,request.input);else await api.submitReference(request.input)}
      pending.current=null;setUncertain(false);onUnresolvedChange(false);setForm(null);setReview(null);setMessage('Saved successfully. Counters and reference values have been refreshed.');setRefresh(value=>value+1)
    }catch(error){if(error instanceof ApiError&&error.status===409){setConflict(true);setUncertain(false);pending.current=null;onUnresolvedChange(false);setError(`Conflict: ${errorMessage(error)} Reload saved counters/references before continuing.`)}else{const unknown=uncertain||!(error instanceof ApiError)||error.status>=500;setUncertain(unknown);onUnresolvedChange(unknown);if(!unknown)pending.current=null;setError(`${errorMessage(error)}${unknown?' Retry the same submission or check Records before starting another.':''}`)}}finally{submitting.current=false;activity(false)}}
  async function removeReference(){if(!deleting?.id||busy)return;activity(true);setError('');try{await api.deleteReference(deleting.id);setMessage('Sales reference deleted.');setDeleting(null);setRefresh(value=>value+1)}catch(error){if(error instanceof ApiError&&error.status===404){setMessage('Sales reference is already deleted or no longer available.');setDeleting(null);setRefresh(value=>value+1)}else setError(`${errorMessage(error)} Retry deletion or refresh counters to reconcile.`)}finally{activity(false)}}
  const quantity=form?.kind==='tally'?form.input.count:form?.input.soldLastMonthCount??''
  const valid=/^\d+$/.test(quantity)&&BigInt(quantity)<=2147483647n&&(form?.kind==='reference'||BigInt(quantity)>0n)
  const options=categories.slice();if(form?.kind==='tally'&&form.editing?.categoryId&&!options.some(row=>row.id===form.editing?.categoryId))options.push({id:form.editing.categoryId,name:`${form.editing.categoryName} (inactive)`,displayOrder:99})
  return <section><div className="page-heading"><div><p className="eyebrow">INTERNAL OPERATIONAL COUNTS</p><h1>Electrical Testing</h1><p>Processing tallies and monthly sales references. These counts do not contribute to waste reporting.</p></div></div>
    {error&&<p className="notice error" role="alert">{error}</p>}{message&&<p className="notice success" role="status">{message}</p>}
    {deleting&&<div className="notice" role="alert"><p>Delete the {deleting.reportingMonth} sales reference for {deleting.categoryName}? It will become Not entered.</p><button className="danger" disabled={busy} onClick={removeReference}>Confirm reference deletion</button><button className="secondary" disabled={busy} onClick={()=>setDeleting(null)}>Cancel reference deletion</button></div>}
    {!form?<TestingBoard date={date} month={month} onDate={value=>{setDate(value);setMonth(value.slice(0,7))}} onMonth={setMonth} isAdmin={isAdmin} refresh={refresh} busy={busy||deleting!==null} onTally={openTally} onReference={openReference} onDeleteReference={row=>{setError('');setDeleting(row)}}/>
      :<div className="panel testing-entry"><h2>{form.kind==='tally'?'Processing / diversion tally':'Sold Last Month reference'}</h2>
        {conflict?<><p>Reload saved records before making another change. This conflicting request will not be retried.</p><button disabled={busy} onClick={()=>{if(editing)onCancel();else{setForm(null);setReview(null);setRefresh(value=>value+1)}}}>Reload counters</button></>
          :review?<>{review.kind==='tally'?<TallyReview record={review.record}/>:<dl className="record-facts"><div><dt>Reporting month</dt><dd>{review.record.reportingMonth}</dd></div><div><dt>Category</dt><dd>{review.record.categoryName}</dd></div><div><dt>Sold Last Month</dt><dd>{review.record.soldLastMonthCount}</dd></div></dl>}
            <div className="actions"><button disabled={busy} onClick={save}>{busy?'Saving...':uncertain?'Retry submission':form.editing?'Confirm correction':'Confirm and submit'}</button><button className="secondary" disabled={busy||uncertain} onClick={()=>setReview(null)}>Back to edit</button></div></>
            :<form onSubmit={event=>{event.preventDefault();if(valid)calculate()}}><fieldset disabled={busy}>
              {form.kind==='tally'?<><label>Record date (required)<input type="date" required min="0001-01-01" max={operationalDate()} value={form.input.recordDate} onChange={event=>setForm({...form,input:{...form.input,recordDate:event.target.value}})}/></label>
                {form.editing?<><label>Tally type<select value={form.input.tallyType} onChange={event=>{const type=event.target.value as TallyType;setForm({...form,input:{...form.input,tallyType:type,categoryId:type==='DIVERTED_TO_OLS'||type==='DIVERTED_TO_COLL'?null:form.input.categoryId}})}}>{Object.entries(typeLabels).map(([type,label])=><option key={type} value={type}>{label}</option>)}</select></label>
                  {form.input.tallyType!=='DIVERTED_TO_OLS'&&form.input.tallyType!=='DIVERTED_TO_COLL'&&<label>Electrical item category<select required value={form.input.categoryId??''} onChange={event=>setForm({...form,input:{...form.input,categoryId:Number(event.target.value)}})}><option value="">Select category</option>{options.map(row=><option key={row.id} value={row.id}>{row.name}</option>)}</select></label>}
                  {categoryError&&<p role="alert">{categoryError}<button type="button" onClick={()=>{setCategoryError('');setCategoryAttempt(value=>value+1)}}>Retry categories</button></p>}</>:<p>{form.categoryName??'Overall diversion'} - {typeLabels[form.input.tallyType]}</p>}</>
                :<><label>Reference reporting month<input type="month" required min="0001-01" max={operationalDate().slice(0,7)} value={form.input.reportingMonth} onChange={event=>setForm({...form,input:{...form.input,reportingMonth:event.target.value}})}/></label><p>{form.categoryName}. Enter the previous calendar month's sales for this reporting month. This replaces a reference value; it is not a live tally.</p></>}
              <label>{form.kind==='tally'?'Tally quantity':'Sold Last Month count'}<input type="number" inputMode="numeric" required min={form.kind==='tally'?1:0} max="2147483647" step="1" value={quantity} onChange={event=>setForm(form.kind==='tally'?{...form,input:{...form.input,count:event.target.value}}:{...form,input:{...form.input,soldLastMonthCount:event.target.value}})}/></label>
              {form.kind==='tally'&&<div className="actions"><button type="button" className="secondary" disabled={!valid||BigInt(quantity)<=1n} onClick={()=>setForm({...form,input:{...form.input,count:String(BigInt(quantity)-1n)}})}>Decrease quantity</button><button type="button" className="secondary" disabled={!valid||BigInt(quantity)>=2147483647n} onClick={()=>setForm({...form,input:{...form.input,count:String(BigInt(quantity)+1n)}})}>Increase quantity</button></div>}
              <button disabled={busy||!valid}>Review entry</button></fieldset></form>}
        <button className="text-button" disabled={busy||uncertain} onClick={()=>{if(editing)onCancel();else{setForm(null);setReview(null);setError('')}}}>Back to counters</button>
        <button className="text-button" disabled={busy} onClick={onCancel}>{uncertain?'Check Records':'View tally Records'}</button>
      </div>}
  </section>
}
