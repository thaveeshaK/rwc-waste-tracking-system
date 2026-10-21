import type { DirectionResult, SalvageRecord } from './types'

function DirectionReview({ name, value }: { name: 'Up' | 'Down'; value: DirectionResult }) {
  return <section className="salvage-direction"><h3>Salvage {name} - {name === 'Up' ? 'Waste to landfill' : 'Recovered material'}</h3>
    <dl className="record-facts"><div><dt>Time {name}</dt><dd>{value.time.slice(0, 5)}</dd></div>
      <div><dt>Kg {name}</dt><dd>{value.kg} kg</dd></div></dl>
    {value.materials.length === 0 ? <p className="notice">No material breakdown required: 0 kg.</p>
      : <div className="table-scroll"><table><caption>{name} material breakdown</caption>
        <thead><tr><th>Material</th><th className="numeric">Percentage</th><th className="numeric">Weight</th></tr></thead>
        <tbody>{value.materials.map(row => <tr key={row.materialCategoryId}><td>{row.name}</td>
          <td className="numeric">{row.percentage}%</td><td className="numeric">{row.materialWeight} kg</td></tr>)}</tbody></table></div>}
  </section>
}

export function SalvageReview({ record }: { record: SalvageRecord }) {
  return <div className="salvage-review"><dl className="record-facts"><div><dt>Record date</dt><dd>{record.recordDate}</dd></div>
    <div><dt>Crew</dt><dd>{record.crew}</dd></div></dl>
    <DirectionReview name="Up" value={record.up} /><DirectionReview name="Down" value={record.down} />
    <p className="hint">Material weights use the final council weights and are rounded individually to 0.01 kg. Rounding differences are not redistributed.</p>
    {record.createdBy && <p className="hint">Created by {record.createdBy}
      {record.createdAt && <> - {new Date(record.createdAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}
      {record.updatedBy && <>. Last updated by {record.updatedBy}
        {record.updatedAt && <> - {new Date(record.updatedAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}</>}.</p>}
  </div>
}
