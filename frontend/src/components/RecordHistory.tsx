import { formatAuditTimestamp, isUnmodified } from '../utils/recordHistory'
import type { RecordAudit } from '../utils/recordHistory'

export function RecordHistory({ record }: { record?: RecordAudit | null }) {
  if (!record?.id) return null
  const untouched = isUnmodified(record)
  return <section className="record-history" aria-label="Record history">
    <h3>Record history</h3>
    <dl><div><dt>Created by</dt><dd>{record.createdBy || 'Not recorded'}</dd></div>
      <div><dt>Created at</dt><dd>{formatAuditTimestamp(record.createdAt)}</dd></div>
      {untouched ? <div><dt>Last updated</dt><dd>Not modified</dd></div> : <>
        <div><dt>Last updated by</dt><dd>{record.updatedBy || 'Not recorded'}</dd></div>
        <div><dt>Last updated at</dt><dd>{formatAuditTimestamp(record.updatedAt)}</dd></div>
      </>}
    </dl>
  </section>
}
