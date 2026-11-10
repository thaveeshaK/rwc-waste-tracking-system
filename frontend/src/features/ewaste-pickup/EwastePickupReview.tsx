import type { EwastePickupRecord } from './types'
export function EwastePickupReview({record}: {record:EwastePickupRecord}) {
 return <div className="record-review ewaste-review"><dl className="record-facts"><div><dt>Record date</dt><dd>{record.recordDate}</dd></div><div><dt>Organisation</dt><dd>{record.organisation}</dd></div><div><dt>Entered weight</dt><dd>{record.enteredWeight} kg</dd></div><div><dt>Total tare</dt><dd>{record.totalTareWeight} kg</dd></div><div className="net-weight"><dt>Net E-waste weight</dt><dd>{record.netWeight} kg</dd></div></dl>
 {record.containers.length>0?<div className="table-scroll"><table><caption>Containers</caption><thead><tr><th>Type</th><th>Quantity</th><th>Tare per unit</th><th>Subtotal tare</th></tr></thead><tbody>{record.containers.map(c=><tr key={c.containerTypeId}><td>{c.name}</td><td>{c.quantity}</td><td>{c.tarePerUnit} kg</td><td>{c.tareSubtotal} kg</td></tr>)}</tbody></table></div>:<p>No containers: entered weight is the net material weight.</p>}
 <p className="hint">E-waste collection/intake only. Separate from Electrical Crate/Pallet internal processing.</p>
 </div>
}
