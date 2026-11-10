export interface RecordAudit {
  id: number | null
  version?: number | null
  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}

const timestamp = new Intl.DateTimeFormat('en-AU', {
  timeZone: 'Australia/Hobart', day: 'numeric', month: 'short', year: 'numeric',
  hour: 'numeric', minute: '2-digit', hour12: true,
})

export function formatAuditTimestamp(value: string | null): string {
  if (!value) return 'Not recorded'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Not recorded' : timestamp.format(date)
}

// Initial material collection persistence can increment the version at creation.
// Complete identical metadata, not the version or displayed minute, marks an untouched record.
export function isUnmodified(record: RecordAudit): boolean {
  return !!record.createdBy && !!record.createdAt
    && record.createdBy === record.updatedBy && record.createdAt === record.updatedAt
    && formatAuditTimestamp(record.createdAt) !== 'Not recorded'
}
