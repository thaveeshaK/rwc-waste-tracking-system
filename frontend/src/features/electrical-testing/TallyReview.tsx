import { typeLabels } from './types'
import type { TallyRecord } from './types'
export function TallyReview({record}:{record:TallyRecord}) {
  return <div><dl className="record-facts"><div><dt>Record date</dt><dd>{record.recordDate}</dd></div>
    <div><dt>Category</dt><dd>{record.categoryName ?? 'Overall diversion (no item category)'}</dd></div>
    <div><dt>Tally type</dt><dd>{typeLabels[record.tallyType]}</dd></div><div><dt>Quantity</dt><dd>{record.count}</dd></div></dl>
    <p className="hint">This is an additive operational tally, not an individual item or a waste weight.</p>
    {record.createdBy && <p className="hint">Created by {record.createdBy}{record.createdAt && <> - {new Date(record.createdAt).toLocaleString('en-AU',{timeZone:'Australia/Hobart'})} (Hobart)</>}
      {record.updatedBy && <>. Last updated by {record.updatedBy}{record.updatedAt && <> - {new Date(record.updatedAt).toLocaleString('en-AU',{timeZone:'Australia/Hobart'})} (Hobart)</>}</>}.</p>}
  </div>
}
