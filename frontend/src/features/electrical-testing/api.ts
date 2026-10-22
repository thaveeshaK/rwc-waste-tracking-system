import { jsonRequest, request } from '../../api/client'
import type { Category, TallyInput, TallyRecord, ReferenceInput, SalesReference, MonthlySummary } from './types'
const base='/api/electrical-testing'
export const getCategories=()=>request<Category[]>(`${base}/categories`)
export const getSummary=(month:string)=>request<MonthlySummary>(`${base}/monthly-summary?month=${encodeURIComponent(month)}`)
export const getReferences=(month:string)=>request<SalesReference[]>(`${base}/sales-references?month=${encodeURIComponent(month)}`)
export const previewTally=(input:TallyInput,id?:number)=>jsonRequest<TallyRecord>(`${base}/tallies/preview${id?`?recordId=${id}`:''}`,'POST',input)
export const submitTally=(input:TallyInput)=>jsonRequest<TallyRecord>(`${base}/tallies`,'POST',input)
export const updateTally=(id:number,input:TallyInput)=>jsonRequest<TallyRecord>(`${base}/tallies/${id}`,'PUT',input)
export const getRecord=(id:number)=>request<TallyRecord>(`${base}/tallies/${id}`)
export const deleteRecord=(id:number)=>request<void>(`${base}/tallies/${id}`,{method:'DELETE'})
export function getRecords(from='',to='') { const params=new URLSearchParams();if(from)params.set('from',from);if(to)params.set('to',to);return request<TallyRecord[]>(`${base}/tallies?${params}`) }
export const previewReference=(input:ReferenceInput,id?:number)=>jsonRequest<SalesReference>(`${base}/sales-references/preview${id?`?recordId=${id}`:''}`,'POST',input)
export const submitReference=(input:ReferenceInput)=>jsonRequest<SalesReference>(`${base}/sales-references`,'POST',input)
export const updateReference=(id:number,input:ReferenceInput)=>jsonRequest<SalesReference>(`${base}/sales-references/${id}`,'PUT',input)
export const deleteReference=(id:number)=>request<void>(`${base}/sales-references/${id}`,{method:'DELETE'})
