import { decimalHundredths } from '../../utils/decimal'
export function amount(value: string, tonnes: boolean): string {
  const n = decimalHundredths(value)
  if (n === null) return value
  const divisor = tonnes ? 100000n : 100n
  return `${n / divisor}.${(n % divisor).toString().padStart(tonnes ? 5 : 2, '0')}`
}
