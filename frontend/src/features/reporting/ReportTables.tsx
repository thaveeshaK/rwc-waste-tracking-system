import {useState} from 'react'
import {Pagination} from '../../components/Pagination'
import type { Row, Total } from './types'
import { label } from './types'
import { amount } from './format'
export function TotalsTable({ totals, tonnes = false, emptyMessage='No activity for the selected period.' }: {totals: Total[]; tonnes?: boolean;emptyMessage?:string}) {
  if (!totals.length) return <p>{emptyMessage}</p>
  return <div className="table-scroll"><table><thead><tr><th>Workflow / direction</th><th>Container</th><th>Category</th><th>Metric</th><th>Value</th><th>Unit</th></tr></thead>
    <tbody>{totals.map((t, i) => <tr key={i}><td>{label(t.workflow)}{t.direction && ` / ${t.direction}`}</td><td>{t.container ?? '-'}</td><td>{t.category ?? '-'}</td><td>{label(t.metric)}</td><td>{t.unit === 'kg' ? amount(t.value, tonnes) : t.value}</td><td>{tonnes && t.unit === 'kg' ? 'tonnes' : t.unit}</td></tr>)}</tbody></table></div>
}
export function DetailTable({ rows, onOpen }: {rows: Row[]; onOpen?: (row: Row) => void}) {
  if (!rows.length) return <p>No matching records.</p>
  return <div className="table-scroll"><table><thead><tr><th>Date / reference month</th><th>Workflow</th><th>Record / row type</th><th>Details</th><th>Weight (kg)</th><th>Count</th><th>Submitted by</th></tr></thead><tbody>
    {rows.map((r, i) => <tr key={i}><td>{r.rowType === 'REFERENCE' || r.workflow === 'SCRAP' ? r.date.slice(0, 7) : r.date}</td><td>{label(r.workflow)}</td><td>{onOpen && r.rowType !== 'REFERENCE' ? <button className="text-button" onClick={() => onOpen(r)}>#{r.id}</button> : `#${r.id}`} / {label(r.rowType)}</td><td>{[r.container, r.organisation ?? r.location, r.containerLines, r.enteredWeight && `Entered ${r.enteredWeight} kg / tare ${r.tareKg} kg`, r.crew, r.direction, r.category, r.status && label(r.status)].filter(Boolean).join(' / ') || '-'}{r.percentage && ` (${r.percentage}%)`}</td><td>{r.kg ?? '-'}{r.workflow === 'WHITEGOODS' && ' estimated'}</td><td>{r.count ?? '-'}</td><td>{r.createdBy}</td></tr>)}
  </tbody></table></div>
}

// Monthly aggregate sections are bounded by configured categories/containers, not record volume.
export function SectionTotalsTable({totals,tonnes,label}:{totals:Total[];tonnes:boolean;label:string}) {
  const [page,setPage]=useState(0),[size,setSize]=useState(10)
  const actual=Math.min(page,Math.max(0,Math.ceil(totals.length/size)-1))
  return <><TotalsTable totals={totals.slice(actual*size,(actual+1)*size)} tonnes={tonnes} emptyMessage="No records for this period."/>{totals.length>0&&<Pagination label={label} page={actual} size={size} totalItems={totals.length} onPage={setPage} onSize={size=>{setSize(size);setPage(0)}}/>}</>
}
