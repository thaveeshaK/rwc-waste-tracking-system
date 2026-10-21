/** Sum percentage hundredths using integers, avoiding binary floating-point arithmetic. */
export function decimalHundredths(value: string): bigint | null {
  if (!/^\d+(\.\d{1,2})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}

export function percentageTotal(values: string[]): string | null {
  let total = 0n
  for (const value of values) {
    const amount = decimalHundredths(value)
    if (amount === null || amount > 10000n) return null
    total += amount
  }
  return `${total / 100n}.${(total % 100n).toString().padStart(2, '0')}`
}

export function operationalDate(): string {
  const parts = new Intl.DateTimeFormat('en-AU', {
    timeZone: 'Australia/Hobart', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = (type: string) => parts.find(part => part.type === type)!.value
  return `${value('year')}-${value('month')}-${value('day')}`
}
