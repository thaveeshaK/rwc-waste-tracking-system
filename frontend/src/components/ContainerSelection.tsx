import { useId } from 'react'

interface Container { id: number; name: string; tareWeight: string }
interface Props { containers: Container[]; value: number; onChange: (id: number) => void }

export function ContainerSelection({ containers, value, onChange }: Props) {
  const name = useId()
  return <fieldset className="container-selection"><legend>Container type (required)</legend>
    <div className="container-options">{containers.map(container => <label key={container.id} className={'container-option'+(value === container.id ? ' selected' : '')}>
      <input type="radio" required name={name} value={container.id} checked={value === container.id} onChange={() => onChange(container.id)} />
      <span><strong>{container.name}</strong><span className="hint">{container.tareWeight} kg tare</span></span>
    </label>)}</div>
  </fieldset>
}
