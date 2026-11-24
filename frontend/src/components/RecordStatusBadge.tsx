import type { RecordStatus } from '../features/records/status'

export function RecordStatusBadge({status}: {status: RecordStatus}) {
  return <span className={'record-status '+(status === 'INCOMPLETE' ? 'incomplete' : 'completed')}>
    {status === 'INCOMPLETE' ? 'Incomplete' : 'Completed'}
  </span>
}
