import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { decimalHundredths } from '../../utils/decimal'
import { getOrganisations } from './api'
import type { ContainerType, EwastePickupInput } from './types'
const kg=(n:bigint)=>`${n/100n}.${(n%100n).toString().padStart(2,'0')}`
interface Props {value:EwastePickupInput;containers:ContainerType[];today:string;busy:boolean;onChange:(value:EwastePickupInput)=>void;onReview:()=>void}
export function EwastePickupForm({value,containers,today,busy,onChange,onReview}:Props) {
 const [suggestions,setSuggestions]=useState<string[]>([])
 const [suggestionError,setSuggestionError]=useState('')
 useEffect(()=>{let cancelled=false;const query=value.organisation.trim();if(!query)return;const timer=setTimeout(()=>{getOrganisations(query).then(rows=>{if(!cancelled){setSuggestions(rows);setSuggestionError('')}}).catch(()=>{if(!cancelled)setSuggestionError('Suggestions are unavailable. You can still enter an organisation.')} )},200);return()=>{cancelled=true;clearTimeout(timer)}},[value.organisation])
 let tare:bigint|null=0n
 for(const line of value.containers){const t=decimalHundredths(containers.find(c=>c.id===line.containerTypeId)?.tareWeight??'');if(t===null||!Number.isInteger(line.quantity)||line.quantity<=0||line.quantity>2147483647){tare=null;break}tare+=t*BigInt(line.quantity)}
 const entered=decimalHundredths(value.enteredWeight)
 const net=entered!==null&&tare!==null?entered-tare:null
 const valid=value.organisation.trim().length>0&&entered!==null&&entered>0n&&entered<=9999999999n&&net!==null&&net>0n
 function submit(e:FormEvent){e.preventDefault();if(!busy&&valid)onReview()}
 return <form onSubmit={submit}><fieldset disabled={busy}><legend>Pickup details</legend><div className="form-grid"><label>Record date (required)<input required type="date" min="0001-01-01" max={today} value={value.recordDate} onChange={e=>onChange({...value,recordDate:e.target.value})}/></label>
 <label>Organisation (required)<input required maxLength={255} list="ewaste-organisations" autoComplete="off" value={value.organisation} onChange={e=>{setSuggestions([]);onChange({...value,organisation:e.target.value})}}/><datalist id="ewaste-organisations">{suggestions.map(s=><option key={s} value={s}/>)}</datalist><span className="hint">Choose a previous spelling or type a new organisation.</span>{suggestionError&&<span className="hint">{suggestionError}</span>}</label>
 <label>Entered weight (kg)<input required type="number" inputMode="decimal" min="0.01" step="0.01" max="99999999.99" value={value.enteredWeight} onChange={e=>onChange({...value,enteredWeight:e.target.value})}/><span className="hint">If no containers or pallets were used, enter the material weight directly. It will be treated as the net weight.</span></label></div></fieldset>
 <fieldset disabled={busy}><legend>Optional containers / pallets</legend>{containers.length===0&&<p className="hint">No active E-waste Pickup containers are configured. You may record material weighed without containers.</p>}
 <label>Add container type<select value="" onChange={e=>{if(e.target.value)onChange({...value,containers:[...value.containers,{containerTypeId:Number(e.target.value),quantity:1}]})}}><option value="">Select an optional container</option>{containers.filter(c=>!value.containers.some(l=>l.containerTypeId===c.id)).map(c=><option key={c.id} value={c.id}>{c.name} - {c.tareWeight} kg per unit</option>)}</select></label>
 {value.containers.map(line=>{const c=containers.find(c=>c.id===line.containerTypeId);const t=decimalHundredths(c?.tareWeight??'');const subtotal=t!==null&&Number.isInteger(line.quantity)&&line.quantity>0?t*BigInt(line.quantity):null;return <div className="panel ewaste-container" key={line.containerTypeId} data-container={line.containerTypeId}><h3>{c?.name}</h3><label>Quantity: {c?.name}<input required type="number" inputMode="numeric" min="1" max="2147483647" step="1" value={line.quantity||''} onChange={e=>onChange({...value,containers:value.containers.map(l=>l.containerTypeId===line.containerTypeId?{...l,quantity:Number(e.target.value)}:l)})}/></label><p>Tare per unit: {c?.tareWeight} kg. Subtotal tare: {subtotal===null?'Check quantity':kg(subtotal)+' kg'}.</p><button type="button" className="secondary" onClick={()=>onChange({...value,containers:value.containers.filter(l=>l.containerTypeId!==line.containerTypeId)})}>Remove {c?.name}</button></div>})}</fieldset>
 <dl className="record-facts"><div><dt>Entered weight</dt><dd>{entered===null?'-':kg(entered)+' kg'}</dd></div><div><dt>Container tare</dt><dd>{tare===null?'Check quantities':kg(tare)+' kg'}</dd></div><div className="net-weight"><dt>Net E-waste weight</dt><dd aria-live="polite">{net===null?'-':net<=0n?'Entered weight must exceed tare':kg(net)+' kg'}</dd></div></dl>
 <div className="actions"><button disabled={busy||!valid}>{busy?'Calculating...':'Review record'}</button><span className="hint">Nothing is saved until final confirmation.</span></div></form>
}
