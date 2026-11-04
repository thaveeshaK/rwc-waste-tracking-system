import {materials} from './types'
import type {ScrapRecord, ScrapPreview} from './types'
export function ScrapReview({record, preview}: {record: ScrapRecord; preview?: ScrapPreview}) {
  return <div className="record-review scrap-review"><dl className="record-facts"><div><dt>Reporting month</dt><dd>{record.reportingMonth}</dd></div><div><dt>This entry</dt><dd>{record.totalWeight} kg</dd></div></dl>
    <div className="table-scroll"><table><caption>Scrap material weights (kg)</caption><thead><tr><th>Material</th>{preview&&<th>Current month</th>}<th>This entry</th>{preview&&<th>After submit</th>}</tr></thead><tbody>{materials.map(m=><tr key={m.key}><td>{m.label}</td>{preview&&<td>{preview.current[m.key]} kg</td>}<td>{record[m.key]} kg</td>{preview&&<td>{preview.projected[m.key]} kg</td>}</tr>)}</tbody></table></div>
    {preview&&<><dl className="record-facts"><div><dt>Current monthly total</dt><dd>{preview.current.totalWeight} kg</dd></div><div><dt>This entry</dt><dd>{record.totalWeight} kg</dd></div><div className="net-weight"><dt>After submit</dt><dd>{preview.projected.totalWeight} kg</dd></div></dl><p className="hint">Totals are a preview of active records now and may change if another submission is made. For corrections, current totals exclude the record being corrected.</p></>}
    {record.createdBy&&<p className="hint">Created by {record.createdBy}. Last updated by {record.updatedBy}.</p>}
  </div>
}
