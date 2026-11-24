import type { OperationalRecord } from './types'

export type RecordStatus = 'INCOMPLETE' | 'COMPLETED'

export function recordStatus(row: OperationalRecord): RecordStatus {
  return row.workflow === 'CRATE_PALLET' || row.workflow === 'ELECTRICAL_CRATE_PALLET'
    ? row.record.status ?? 'COMPLETED' : 'COMPLETED'
}

export function isIncompleteRecord(row: OperationalRecord): boolean {
  return recordStatus(row) === 'INCOMPLETE'
}
