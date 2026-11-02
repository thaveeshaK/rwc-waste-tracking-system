import { decimalHundredths } from '../../utils/decimal'
import type { FormEvent } from 'react'
interface Value { submissionId: string; version?: number; recordDate: string; count: string }
interface Props { factor?: string; value: Value; today: string; busy: boolean; onChange: (value: Value) => void; onReview: () => void }
export function WhitegoodsForm({ factor, value, today, busy, onChange, onReview }: Props) {
  const valid = /^\d+$/.test(value.count) && BigInt(value.count) <= 2147483647n
  const hundredths = factor ? decimalHundredths(factor) : null
  const total = valid && hundredths !== null ? BigInt(value.count) * hundredths : null
  const weight = total === null ? 'Enter a whole-number count; factor loads from configuration' : `${total / 100n}.${(total % 100n).toString().padStart(2, '0')} kg`
  function review(event: FormEvent) { event.preventDefault(); if (!busy && valid) onReview() }
  return <form onSubmit={review}><fieldset disabled={busy}><legend>Whitegoods entry</legend>
    <p className="hint">Enter the quantity for this entry, not a monthly total. Zero-count entries are valid.</p>
    <div className="form-grid"><label>Record date (required)<input type="date" required min="0001-01-01" max={today}
      value={value.recordDate} onChange={event => onChange({ ...value, recordDate: event.target.value })} /></label>
      <label>Whitegoods count (required)<input type="number" inputMode="numeric" required min="0" max="2147483647" step="1"
        value={value.count} onChange={event => onChange({ ...value, count: event.target.value })} /></label></div>
    <dl className="record-facts"><div><dt>Conversion factor</dt><dd>{factor ?? 'Loading...'} kg per whitegood</dd></div>
      <div><dt>Estimated weight</dt><dd aria-live="polite">{weight}</dd></div></dl>
    <div className="actions"><button disabled={busy || !valid || hundredths === null}>{busy ? 'Calculating...' : 'Review Whitegoods'}</button>
      <span className="hint">Nothing is saved until you confirm submission.</span></div>
  </fieldset></form>
}
