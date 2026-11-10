import type { CratePalletRecord } from './types'

export function CratePalletReview({ record }: { record: CratePalletRecord }) {
  return <div className="record-review">
    <dl className="record-facts">
      <div><dt>Record date</dt><dd>{record.recordDate}</dd></div>
      <div><dt>Container</dt><dd>{record.containerTypeName}</dd></div>
      <div><dt>Gross weight</dt><dd>{record.grossWeight} kg</dd></div>
      <div><dt>Tare deducted</dt><dd>{record.tareWeight} kg</dd></div>
      <div className="net-weight"><dt>Net material weight</dt><dd>{record.netWeight} kg</dd></div>
    </dl>
    <div className="table-scroll"><table><caption>Material breakdown</caption>
      <thead><tr><th>Material</th><th className="numeric">Percentage</th><th className="numeric">Weight</th></tr></thead>
      <tbody>{record.materials.map(material => <tr key={material.materialCategoryId}>
        <td>{material.name}</td><td className="numeric">{material.percentage}%</td>
        <td className="numeric">{material.materialWeight} kg</td></tr>)}</tbody>
    </table></div>
    <p className="hint">Material weights are rounded individually to 0.01 kg. Their sum may differ slightly from net weight.</p>
    {record.createdBy && <p className="hint">Created by {record.createdBy}
      {record.createdAt && <> · {new Date(record.createdAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}
      {record.updatedBy && <>. Last updated by {record.updatedBy}
        {record.updatedAt && <> · {new Date(record.updatedAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}</>}.</p>}
  </div>
}
