import {request} from '../../api/client'
import type {OperationalRecord,Workflow} from './types'
export interface RecordsView {page:number;size:number;from:string;to:string}
export interface RecordPage {items:OperationalRecord[];page:number;size:number;totalItems:number;totalPages:number}
export const initialRecordsView: RecordsView={page:0,size:10,from:'',to:''}
export function getPage(workflow:Workflow|'',view:RecordsView) {
  return request<RecordPage>('/api/records?'+new URLSearchParams({workflow,from:view.from,to:view.to,page:String(view.page),size:String(view.size)}))
}
const paths:Record<Workflow,string>={CRATE_PALLET:'/api/crate-pallet-records',SALVAGE:'/api/salvage-records',PICKUP:'/api/pickup-records',ELECTRICAL_CRATE_PALLET:'/api/electrical-crate-pallet-records',COLLECTABLES:'/api/collectable-records',WHITEGOODS:'/api/whitegoods-records',ELECTRICAL_TESTING:'/api/electrical-testing/tallies',EWASTE_PICKUP:'/api/ewaste-pickups',SCRAP:'/api/scrap-records'}
export async function getOperationalRecord(workflow:Workflow,id:number):Promise<OperationalRecord> {
  return {workflow,record:await request<OperationalRecord['record']>(paths[workflow]+'/'+id)} as OperationalRecord
}
