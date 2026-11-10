import type { PickupRecord } from './types'
export function PickupReview({ record }: { record: PickupRecord }) {
  return <div><dl className="record-facts"><div><dt>Record date</dt><dd>{record.recordDate}</dd></div>
    <div><dt>Pickup location</dt><dd>{record.pickupLocation}</dd></div>
    <div><dt>Final net weight</dt><dd>{record.netWeight} kg</dd></div></dl>
    <div className="table-scroll"><table><caption>Pickup material breakdown</caption>
      <thead><tr><th>Material</th><th className="numeric">Percentage</th><th className="numeric">Weight</th></tr></thead>
      <tbody>{record.materials.map(row => <tr key={row.materialCategoryId}><td>{row.name}</td>
        <td className="numeric">{row.percentage}%</td><td className="numeric">{row.materialWeight} kg</td></tr>)}</tbody></table></div>
    <p className="hint">Material weights use the final net Pickup weight and are rounded individually to 0.01 kg. Rounding differences are not redistributed.</p>
    {record.createdBy && <p className="hint">Created by {record.createdBy}
      {record.createdAt && <> - {new Date(record.createdAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}
      {record.updatedBy && <>. Last updated by {record.updatedBy}
        {record.updatedAt && <> - {new Date(record.updatedAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}</>}.</p>}
  </div>
}
