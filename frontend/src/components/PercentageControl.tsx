import { useId } from 'react'
import { decimalHundredths } from '../utils/decimal'

interface Props { material: string; value: string; onChange: (value: string) => void }

export function PercentageControl({material,value,onChange}: Props) {
  const nameId=useId()
  const amount=decimalHundredths(value)
  function set(next: bigint) {
    const clamped=next<0n?0n:next>10000n?10000n:next
    onChange(`${clamped/100n}.${(clamped%100n).toString().padStart(2,'0')}`)
  }
  return <div className="percentage-control" role="group" aria-labelledby={nameId} data-material-name={material}>
    <span className="percentage-material" id={nameId}>{material}</span>
    <div className="percentage-buttons">
      <div className="percentage-button-group">
        <button type="button" className="secondary" aria-label={`Set ${material} to minimum (0%)`} onClick={()=>set(0n)}>Min</button>
        <button type="button" className="secondary" aria-label={`Decrease ${material} by 10 percentage points`} disabled={amount===null} onClick={()=>{if(amount!==null)set(amount-1000n)}}>-10</button>
        <button type="button" className="secondary" aria-label={`Decrease ${material} by 5 percentage points`} disabled={amount===null} onClick={()=>{if(amount!==null)set(amount-500n)}}>-5</button>
      </div>
      <output aria-label={`${material} percentage`} aria-live="polite" data-percentage-value={value}>{value}%</output>
      <div className="percentage-button-group">
        <button type="button" className="secondary" aria-label={`Increase ${material} by 5 percentage points`} disabled={amount===null} onClick={()=>{if(amount!==null)set(amount+500n)}}>+5</button>
        <button type="button" className="secondary" aria-label={`Increase ${material} by 10 percentage points`} disabled={amount===null} onClick={()=>{if(amount!==null)set(amount+1000n)}}>+10</button>
        <button type="button" className="secondary" aria-label={`Set ${material} to maximum (100%)`} onClick={()=>set(10000n)}>Max</button>
      </div>
    </div>
  </div>
}
