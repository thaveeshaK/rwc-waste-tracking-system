import type { FormEvent } from 'react'
import { decimalHundredths, percentageTotal } from '../../utils/decimal'
import type { MaterialCategory, PickupInput } from './types'

interface Props {
  value: PickupInput
  materials: MaterialCategory[]
  today: string
  busy: boolean
  onChange: (value: PickupInput) => void
  onReview: () => void
}
export function PickupForm({ value, materials, today, busy, onChange, onReview }: Props) {
  const total = percentageTotal(value.materials.map(row => row.percentage))
  const weight = decimalHundredths(value.netWeight)
  const valid = weight !== null && weight > 0n && total === '100.00'
  function review(event: FormEvent) { event.preventDefault(); if (!busy && valid) onReview() }
  return <form onSubmit={review}><fieldset disabled={busy}><legend>Pickup details</legend>
    <div className="form-grid"><label>Record date (required)<input type="date" required min="0001-01-01" max={today}
      value={value.recordDate} onChange={event => onChange({ ...value, recordDate: event.target.value })} /></label>
      <label>Pickup location (required)<input required maxLength={255} value={value.pickupLocation}
        onChange={event => onChange({ ...value, pickupLocation: event.target.value })} /></label>
      <label>Final net weight (kg) (required)<input type="number" inputMode="decimal" required min="0.01" max="99999999.99" step="0.01"
        value={value.netWeight} onChange={event => onChange({ ...value, netWeight: event.target.value })} />
        <span className="hint">Enter the final material weight. It must be greater than 0 kg.</span></label></div>
    <h3>Material breakdown</h3><div className="material-grid">{materials.map(category => <label key={category.id}>{category.name}
      <span className="percentage-input"><input type="number" inputMode="decimal" required min="0" max="100" step="0.01"
        value={value.materials.find(row => row.materialCategoryId === category.id)?.percentage ?? '0.00'}
        onChange={event => onChange({ ...value, materials: value.materials.map(row => row.materialCategoryId === category.id
          ? { ...row, percentage: event.target.value } : row) })} /><span>%</span></span></label>)}</div>
    <p className={`percentage-total ${total === '100.00' ? 'valid' : ''}`} aria-live="polite">Total: {total ?? 'Check entries'}{total === null ? '' : '%'} <span>Must equal 100.00%</span></p>
    <div className="actions"><button disabled={busy || !valid}>{busy ? 'Calculating...' : 'Review pickup'}</button>
      <span className="hint">Nothing is saved until you confirm submission.</span></div>
  </fieldset></form>
}
