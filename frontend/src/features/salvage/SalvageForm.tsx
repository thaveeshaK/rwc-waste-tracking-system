import type { FormEvent } from 'react'
import { decimalHundredths, percentageTotal } from '../../utils/decimal'
import type { DirectionInput, MaterialCategory, SalvageInput } from './types'

interface Props {
  value: SalvageInput
  upMaterials: MaterialCategory[]
  downMaterials: MaterialCategory[]
  today: string
  busy: boolean
  onChange: (value: SalvageInput) => void
  step: 1 | 2
  onContinue: () => void
  onBack: () => void
  onReview: () => void
}

function DirectionFields({ name, value, categories, onChange }: {
  name: 'Up' | 'Down'; value: DirectionInput; categories: MaterialCategory[]; onChange: (value: DirectionInput) => void
}) {
  const zero = decimalHundredths(value.kg) === 0n
  const total = percentageTotal(value.materials.map(row => row.percentage))
  function weightChanged(kg: string) {
    onChange({ ...value, kg, materials: decimalHundredths(kg) === 0n
      ? value.materials.map(row => ({ ...row, percentage: '0.00' })) : value.materials })
  }
  return <fieldset className="salvage-direction" data-direction={name.toUpperCase()}><legend>Salvage {name} - {name === 'Up' ? 'Waste to landfill' : 'Recovered material'}</legend>
    <div className="form-grid">
      <label>Time {name} (required)<input type="time" required value={value.time}
        onChange={event => onChange({ ...value, time: event.target.value })} /></label>
      <label>Kg {name} (required)<input type="number" inputMode="decimal" required min="0" max="99999999.99" step="0.01"
        value={value.kg} onChange={event => weightChanged(event.target.value)} />
        <span className="hint">Enter the final weight supplied by the council weighbridge.</span></label>
    </div>
    {zero ? <p className="notice" role="status">No breakdown is required for Salvage {name}: weight is 0 kg. The time is still required.</p>
      : <><p className="hint">Estimate this direction's material percentages independently.</p>
        <div className="material-grid">{categories.map(category => <label key={category.id}>{category.name}
          <span className="percentage-input"><input type="number" inputMode="decimal" required min="0" max="100" step="0.01"
            value={value.materials.find(row => row.materialCategoryId === category.id)?.percentage ?? '0.00'}
            onChange={event => onChange({ ...value, materials: value.materials.map(row => row.materialCategoryId === category.id
              ? { ...row, percentage: event.target.value } : row) })} /><span>%</span></span></label>)}</div>
        <p className={`percentage-total ${total === '100.00' ? 'valid' : ''}`} aria-live="polite">{name} total: {total ?? 'Check entries'}{total === null ? '' : '%'} <span>Must equal 100.00% for a positive weight</span></p></>}
  </fieldset>
}

export function SalvageForm({ value, upMaterials, downMaterials, today, busy, step, onChange, onContinue, onBack, onReview }: Props) {
  const valid = (direction: DirectionInput) => {
    const kg = decimalHundredths(direction.kg)
    return /^([01]\d|2[0-3]):[0-5]\d$/.test(direction.time) && kg !== null && kg <= 9999999999n &&
      (kg === 0n || percentageTotal(direction.materials.map(row => row.percentage)) === '100.00')
  }
  const tripValid = /^\d{4}-\d{2}-\d{2}$/.test(value.recordDate) && value.recordDate >= '0001-01-01' && value.recordDate <= today && value.crew.trim().length > 0 && value.crew.length <= 255
  const canContinue = tripValid && valid(value.up)
  const canReview = canContinue && valid(value.down)
  function proceed(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (step === 1 && canContinue) onContinue()
    if (step === 2 && canReview) onReview()
  }
  return <form onSubmit={proceed}><fieldset disabled={busy}><legend className="sr-only">Salvage {step === 1 ? 'Up' : 'Down'} entry</legend>
    {step === 1 && <><h2>Trip details</h2><div className="form-grid"><label>Record date (required)<input type="date" required min="0001-01-01" max={today}
      value={value.recordDate} onChange={event => onChange({ ...value, recordDate: event.target.value })} /></label>
      <label>Crew (required)<input required maxLength={255} value={value.crew}
        onChange={event => onChange({ ...value, crew: event.target.value })} /><span className="hint">The same crew performs the complete trip.</span></label></div></>}
    {step === 1 ? <DirectionFields name="Up" value={value.up} categories={upMaterials} onChange={up => onChange({ ...value, up })} />
      : <DirectionFields name="Down" value={value.down} categories={downMaterials} onChange={down => onChange({ ...value, down })} />}
    <div className="actions">{step === 1 ? <button disabled={busy || !canContinue}>Continue to Salvage Down</button>
      : <><button type="button" className="secondary" disabled={busy} onClick={onBack}>Back to Salvage Up</button>
        <button disabled={busy || !canReview}>{busy ? 'Calculating...' : 'Review Trip'}</button></>}
      <span className="hint">Nothing is saved until you confirm submission.</span></div>
  </fieldset></form>
}
