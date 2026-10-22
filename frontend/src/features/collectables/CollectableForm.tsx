import type { FormEvent } from 'react'
import { decimalHundredths, percentageTotal } from '../../utils/decimal'
import type { MaterialCategory, CollectableInput } from './types'
interface Props {
  value: CollectableInput
  materials: MaterialCategory[]
  today: string
  busy: boolean
  onChange: (value: CollectableInput) => void
  onReview: () => void
}
export function CollectableForm({ value, materials, today, busy, onChange, onReview }: Props) {
  const total = percentageTotal(value.materials.map(row => row.percentage))
  const gross = decimalHundredths(value.grossWeight)
  const net = gross === null ? null : gross - 200n
  const valid = net !== null && net > 0n && total === '100.00'
  const displayNet = net === null ? 'Enter gross tub weight' : net < 0n ? 'Gross weight is below tare' : `${net / 100n}.${(net % 100n).toString().padStart(2, '0')} kg`
  function review(event: FormEvent) { event.preventDefault(); if (!busy && valid) onReview() }
  return <form onSubmit={review}><fieldset disabled={busy}><legend>Collectable Tub details</legend>
    <div className="form-grid"><label>Record date (required)<input type="date" required min="0001-01-01" max={today}
      value={value.recordDate} onChange={event => onChange({ ...value, recordDate: event.target.value })} /></label>
      <label>Gross tub weight (kg) (required)<input type="number" inputMode="decimal" required min="2.01" max="99999999.99" step="0.01"
        value={value.grossWeight} onChange={event => onChange({ ...value, grossWeight: event.target.value })} />
        <span className="hint">Include the tub. Gross weight must be greater than 2.00 kg.</span></label></div>
    <dl className="record-facts"><div><dt>Fixed tare</dt><dd>2.00 kg</dd></div>
      <div><dt>Net material weight</dt><dd aria-live="polite">{displayNet}</dd></div></dl>
    <h3>Material breakdown</h3><div className="material-grid">{materials.map(category => <label key={category.id}>{category.name}
      <span className="percentage-input"><input type="number" inputMode="decimal" required min="0" max="100" step="0.01"
        value={value.materials.find(row => row.materialCategoryId === category.id)?.percentage ?? '0.00'}
        onChange={event => onChange({ ...value, materials: value.materials.map(row => row.materialCategoryId === category.id
          ? { ...row, percentage: event.target.value } : row) })} /><span>%</span></span></label>)}</div>
    <p className={`percentage-total ${total === '100.00' ? 'valid' : ''}`} aria-live="polite">Total: {total ?? 'Check entries'}{total === null ? '' : '%'} <span>Must equal 100.00%</span></p>
    <div className="actions"><button disabled={busy || !valid}>{busy ? 'Calculating...' : 'Review collectables'}</button>
      <span className="hint">Nothing is saved until you confirm submission.</span></div>
  </fieldset></form>
}
