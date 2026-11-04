import {jsonRequest, request} from '../../api/client'
import type {ScrapInput, ScrapRecord, ScrapTotals, ScrapPreview} from './types'
const base='/api/scrap-records'
export const previewRecord=(input: ScrapInput, id?: number)=>jsonRequest<ScrapPreview>(base+'/preview'+(id?`?recordId=${id}`:''),'POST',input)
export const submitRecord=(input: ScrapInput)=>jsonRequest<ScrapRecord>(base,'POST',input)
export const updateRecord=(id: number,input: ScrapInput)=>jsonRequest<ScrapRecord>(base+'/'+id,'PUT',input)
export const getRecord=(id: number)=>request<ScrapRecord>(base+'/'+id)
export const deleteRecord=(id: number)=>request<void>(base+'/'+id,{method:'DELETE'})
export const getRecords=(from='',to='')=>request<ScrapRecord[]>(base+'?'+new URLSearchParams({from,to}))
export const monthlySummary=(month: string)=>request<ScrapTotals>(base+'/monthly-summary?month='+month)
