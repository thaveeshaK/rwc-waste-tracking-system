import type { WhitegoodsRecord } from './types'
export function WhitegoodsReview({ record }: { record: WhitegoodsRecord }) {
  return <div><dl className="record-facts"><div><dt>Record date</dt><dd>{record.recordDate}</dd></div>
    <div><dt>Whitegoods count</dt><dd>{record.count}</dd></div><div><dt>Applied conversion factor</dt><dd>{record.kgPerItem} kg per whitegood</dd></div>
    <div><dt>Estimated weight</dt><dd>{record.estimatedWeight} kg</dd></div></dl>
    <p className="hint">{record.count} x {record.kgPerItem} kg = {record.estimatedWeight} kg. Monthly totals are calculated from active daily entries.</p>
    {record.createdBy && <p className="hint">Created by {record.createdBy}
      {record.createdAt && <> - {new Date(record.createdAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}
      {record.updatedBy && <>. Last updated by {record.updatedBy}
        {record.updatedAt && <> - {new Date(record.updatedAt).toLocaleString('en-AU', { timeZone: 'Australia/Hobart' })} (Hobart)</>}</>}.</p>}
  </div>
}
