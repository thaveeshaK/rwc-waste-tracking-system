import type { FormEvent } from 'react'
import { percentageTotal, decimalHundredths } from '../../utils/decimal'
import type { ContainerType, ElectricalCratePalletInput, MaterialCategory } from './types'

interface Props {
  value: ElectricalCratePalletInput
  containers: ContainerType[]
  materials: MaterialCategory[]
  today: string
  busy: boolean
  onChange: (value: ElectricalCratePalletInput) => void
  onReview: () => void
}

export function ElectricalCratePalletForm({ value, containers, materials, today, busy, onChange, onReview }: Props) {
  const total = percentageTotal(value.materials.map(material => material.percentage))
  const selected = containers.find(container => container.id === value.containerTypeId)
  const gross = decimalHundredths(value.grossWeight)
  const tare = selected ? decimalHundredths(selected.tareWeight) : null
  const net = gross !== null && tare !== null ? gross - tare : null
  const valid = total === '100.00' && net !== null && net > 0n && value.materials.length === 2
  const displayNet = net === null ? 'Select container and enter gross weight' : net < 0n ? 'Gross weight is below tare' : `${net / 100n}.${(net % 100n).toString().padStart(2, '0')} kg`
  function submit(event: FormEvent) { event.preventDefault(); if (!busy && valid) onReview() }
  return <form onSubmit={submit}>
    <fieldset disabled={busy}><legend>Load details</legend>
      <div className="form-grid">
        <label>Record date <span className="required">(required)</span>
          <input type="date" required min="0001-01-01" max={today} value={value.recordDate}
            onChange={event => onChange({ ...value, recordDate: event.target.value })} /></label>
        <label>Container type <span className="required">(required)</span>
          <select required value={value.containerTypeId || ''}
            onChange={event => onChange({ ...value, containerTypeId: Number(event.target.value) })}>
            <option value="">Select a container</option>
            {containers.map(container => <option key={container.id} value={container.id}>{container.name} — {container.tareWeight} kg tare</option>)}
          </select></label>
        <label>Gross weight (kg) <span className="required">(required)</span>
          <input type="number" inputMode="decimal" required min={selected?.tareWeight ?? '0'} max="99999999.99" step="0.01"
            value={value.grossWeight} onChange={event => onChange({ ...value, grossWeight: event.target.value })} />
          <span className="hint">Include the container. Gross must be greater than tare; net material weight must be greater than 0 kg.</span></label>
      </div>
      <dl className="record-facts"><div><dt>Applied tare</dt><dd>{selected ? `${selected.tareWeight} kg` : 'Select a container'}</dd></div>
        <div><dt>Net material weight</dt><dd aria-live="polite">{displayNet}</dd></div></dl>
    </fieldset>
    <fieldset disabled={busy}><legend>Material percentages</legend>
      <p className="hint">Enter both electrical percentages. Either may be zero; their total must equal 100%.</p>
      <div className="material-grid">{materials.map(material => <label key={material.id}>
        {material.name}<span className="percentage-input"><input type="number" inputMode="decimal" required min="0" max="100" step="0.01"
          value={value.materials.find(row => row.materialCategoryId === material.id)?.percentage ?? '0'}
          onChange={event => onChange({ ...value, materials: value.materials.map(row => row.materialCategoryId === material.id
            ? { ...row, percentage: event.target.value } : row) })} /><span>%</span></span></label>)}</div>
      <p className={`percentage-total ${total === '100.00' ? 'valid' : ''}`} aria-live="polite">
        Total: {total ?? 'Check entries'}{total !== null ? '%' : ''} <span>Must equal 100.00%</span></p>
    </fieldset>
    <div className="actions"><button disabled={busy || !valid}>{busy ? 'Calculating…' : 'Review record'}</button>
      <span className="hint">Nothing is saved until you confirm submission.</span></div>
  </form>
}
