import type { FormEvent } from 'react'
import { ContainerSelection } from '../../components/ContainerSelection'
import { PercentageControl } from '../../components/PercentageControl'
import { percentageTotal, decimalHundredths } from '../../utils/decimal'
import type { ContainerType, ElectricalCratePalletInput, MaterialCategory } from './types'

interface Props {
  value: ElectricalCratePalletInput
  containers: ContainerType[]
  materials: MaterialCategory[]
  today: string
  busy: boolean
  onChange: (value: ElectricalCratePalletInput) => void
  detailsReadOnly: boolean
  canSaveIncomplete: boolean
  uncertain: boolean
  onSaveIncomplete: () => void
  step: 1 | 2
  onContinue: () => void
  onBack: () => void
  onReview: () => void
}

export function ElectricalCratePalletForm({ value, containers, materials, today, busy, onChange, detailsReadOnly, canSaveIncomplete, uncertain, onSaveIncomplete, step, onContinue, onBack, onReview }: Props) {
  const total = percentageTotal(value.materials.map(material => material.percentage))
  const selected = containers.find(container => container.id === value.containerTypeId)
  const gross = decimalHundredths(value.grossWeight)
  const tare = selected ? decimalHundredths(selected.tareWeight) : null
  const net = gross !== null && tare !== null ? gross - tare : null
  const displayNet = net === null ? 'Select container and enter gross weight' : net < 0n ? 'Gross weight is below tare' : `${net / 100n}.${(net % 100n).toString().padStart(2, '0')} kg`
  function percentageChanged(materialCategoryId: number, percentage: string) {
    const amount = decimalHundredths(percentage)
    const complement = amount !== null && amount <= 10000n && value.materials.length === 2 ? 10000n - amount : null
    onChange({ ...value, materials: value.materials.map(row => row.materialCategoryId === materialCategoryId
      ? { ...row, percentage }
      : complement === null ? row : { ...row, percentage: `${complement / 100n}.${(complement % 100n).toString().padStart(2, '0')}` }) })
  }
  const detailsValid = /^\d{4}-\d{2}-\d{2}$/.test(value.recordDate) && value.recordDate >= '0001-01-01' && value.recordDate <= today &&
    gross !== null && gross <= 9999999999n && net !== null && net > 0n
  const valid = detailsValid && total === '100.00' && value.materials.length === 2
  function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || uncertain) return
    if (step === 1 && detailsValid) onContinue()
    if (step === 2 && valid) onReview()
  }
  return <form onSubmit={submit}>
    {step === 1 ? <fieldset disabled={busy || uncertain || detailsReadOnly}><legend>Record details</legend>
      <div className="form-grid">
        <label>Record date <span className="required">(required)</span>
          <input type="date" required min="0001-01-01" max={today} value={value.recordDate}
            onChange={event => onChange({ ...value, recordDate: event.target.value })} /></label>
        <label>Gross weight (kg) <span className="required">(required)</span>
          <input type="number" inputMode="decimal" required min={selected?.tareWeight ?? '0'} max="99999999.99" step="0.01"
            value={value.grossWeight} onChange={event => onChange({ ...value, grossWeight: event.target.value })} />
          <span className="hint">Include the container. Gross must be greater than tare; net material weight must be greater than 0 kg.</span></label>
      </div>
      <ContainerSelection containers={containers} value={value.containerTypeId}
        onChange={containerTypeId => onChange({ ...value, containerTypeId })} />
      <dl className="record-facts"><div><dt>Applied tare</dt><dd>{selected ? `${selected.tareWeight} kg` : 'Select a container'}</dd></div>
        <div><dt>Net material weight</dt><dd aria-live="polite">{displayNet}</dd></div></dl>
    </fieldset>
    : <fieldset disabled={busy || uncertain}><legend>Material percentages</legend>
      <p className="hint">Edit either percentage; the other is calculated automatically. Either may be zero; their total must equal 100%.</p>
      <div className="material-grid">{materials.map(material => <PercentageControl key={material.id} material={material.name}
          value={value.materials.find(row => row.materialCategoryId === material.id)?.percentage ?? '0'}
          onChange={percentage => percentageChanged(material.id, percentage)} />)}</div>
      <p className={`percentage-total ${total === '100.00' ? 'valid' : ''}`} aria-live="polite">
        Total: {total ?? 'Check entries'}{total !== null ? '%' : ''} <span>Must equal 100.00%</span></p>
    </fieldset>}
    {step === 1 && detailsReadOnly && <p className="hint">Saved record details are read-only for Staff. Complete the material breakdown on the next step.</p>}
    <div className="actions">{step === 1 ? <button disabled={busy || uncertain || !detailsValid}>Continue</button>
      : <><button type="button" className="secondary" disabled={busy || uncertain} onClick={onBack}>Back to record details</button>
        <button disabled={busy || uncertain || !valid}>{busy ? 'Calculating...' : 'Review record'}</button>
        {(total !== '100.00' || uncertain) && <button type="button" className="secondary"
          disabled={busy || !canSaveIncomplete || (!uncertain && (!detailsValid || total === null || decimalHundredths(total)! >= 10000n))}
          onClick={onSaveIncomplete}>{busy ? 'Saving...' : uncertain ? 'Retry submission' : 'Save as incomplete'}</button>}</>}

      <span className="hint">{step === 1 ? 'Continue to record the material breakdown.' : canSaveIncomplete
        ? 'Save an unfinished breakdown, or review a completed breakdown before submitting.'
        : 'The breakdown must total exactly 100% before saving.'}</span></div>
    {step === 2 && total !== '100.00' && !canSaveIncomplete && <p className="hint">{detailsReadOnly
      ? 'Staff can complete this record at 100%. Saving another incomplete version is not permitted.'
      : 'Completed records cannot be changed back to incomplete.'}</p>}
  </form>
}
