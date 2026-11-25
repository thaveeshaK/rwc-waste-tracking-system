import { useEffect, useState } from 'react'
import { getRecords as getCrates } from '../features/crate-pallet/api'
import { getRecords as getElectrical } from '../features/electrical-crate-pallet/api'

export function IncompleteIndicator({refreshKey, onOpen}: {refreshKey: string; onOpen: () => void}) {
  const [result,setResult]=useState<{key:string;count?:number;failed?:boolean}|null>(null)
  useEffect(()=>{
    let cancelled=false
    // Both endpoints return the complete active list, without pagination or a selected-month restriction.
    Promise.all([getCrates('0001-01-01','9998-12-31'),getElectrical('0001-01-01','9998-12-31')])
      .then(groups=>{if(!cancelled)setResult({key:refreshKey,count:groups.flat().filter(record=>record.status==='INCOMPLETE').length})})
      .catch(()=>{if(!cancelled)setResult({key:refreshKey,failed:true})})
    return()=>{cancelled=true}
  },[refreshKey])
  const current=result?.key===refreshKey?result:null
  const count=current?.count
  const text=<>Incomplete records: {count ?? (current?.failed?'Unavailable':'Loading...')}</>
  if(count!==undefined && count>0)return <button type="button" className="incomplete-indicator warning" onClick={onOpen}>{text}</button>
  return <span className={'incomplete-indicator '+(count===undefined?'neutral':count>0?'warning':'clear')} role="status">
    {text}
  </span>
}
