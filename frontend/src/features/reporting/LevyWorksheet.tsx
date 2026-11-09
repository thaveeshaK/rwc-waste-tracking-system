import { decimalHundredths } from '../../utils/decimal'
import { amount } from './format'
import type { Total, ContainerMaterial } from './types'

interface Props { totals: Total[]; tonnes: boolean }
interface CategoryWeight { key: string; id: number | null; name: string; weight: bigint }
const intakeWorkflows = ['CRATE_PALLET', 'COLLECTABLES', 'PICKUP']
const downOrder = ['Iron and Steel', 'Aluminium', 'Other Non-Ferrous Metals', 'Mixed Metals', 'E-waste', 'Lead Acid Batteries', 'Timber', 'Bricks, Blocks and Pavers', 'Building and Demolition Material', 'Whitegoods / Large Appliances', 'Paper, Cardboard and Books', 'Plastics', 'Glass', 'Mixed Materials']
const scrapOrder = ['Iron and Steel', 'Aluminium', 'Other Non-Ferrous', 'Mixed Metals', 'E-waste']
const categoryKey = (row: Total) => row.categoryId === null ? `name:${row.category}` : `id:${row.categoryId}`
const materialRows = (rows: Total[]) => rows.filter(row => row.metric === 'Material weight' && row.unit === 'kg' && row.category !== null)
const exact = (value: string): bigint => {
  const parsed = decimalHundredths(value)
  if (parsed === null) throw new Error('Expected a non-negative value with at most two decimal places.')
  return parsed
}
const decimal = (value: bigint) => `${value / 100n}.${(value % 100n).toString().padStart(2, '0')}`
const sum = (rows: Total[]) => rows.reduce((total, row) => total + exact(row.value), 0n)
const weight = (value: bigint, tonnes: boolean) => amount(decimal(value), tonnes)
const metric = (rows: Total[], name: string) => sum(rows.filter(row => row.metric === name))

function categories(rows: Total[], order: string[] = []): CategoryWeight[] {
  const grouped = new Map<string, CategoryWeight>()
  for (const row of materialRows(rows)) {
    const key = categoryKey(row)
    const entry = grouped.get(key) ?? { key, id: row.categoryId, name: row.category!, weight: 0n }
    entry.weight += exact(row.value)
    grouped.set(key, entry)
  }
  const rank = (name: string) => order.includes(name) ? order.indexOf(name) : order.length
  return [...grouped.values()].sort((a, b) => rank(a.name) - rank(b.name) || (a.id ?? Number.MAX_SAFE_INTEGER) - (b.id ?? Number.MAX_SAFE_INTEGER) || a.name.localeCompare(b.name))
}

function CategoryTable({ rows, tonnes, total = false, scrap = false }: { rows: CategoryWeight[]; tonnes: boolean; total?: boolean; scrap?: boolean }) {
  if (!rows.length) return <p>No records for this period.</p>
  return <div className="table-scroll"><table><thead><tr><th scope="col">Category</th><th scope="col" className="numeric">Weight ({tonnes ? 'tonnes' : 'kg'})</th></tr></thead>
    <tbody>{rows.map(row => <tr key={row.key}><th scope="row">{scrap && row.name === 'Other Non-Ferrous' ? 'Other Non-Ferrous Metals' : row.name}</th><td className="numeric">{weight(row.weight, tonnes)}</td></tr>)}</tbody>
    {total && <tfoot><tr><th scope="row">Total weight</th><td className="numeric">{weight(rows.reduce((n, row) => n + row.weight, 0n), tonnes)}</td></tr></tfoot>}
  </table></div>
}

function SalvageSection({ rows, direction, tonnes }: { rows: Total[]; direction: 'UP' | 'DOWN'; tonnes: boolean }) {
  return <section className="panel report-section levy-section" data-levy-section={direction === 'DOWN' ? 'owf' : 'rtl'}>
    <h2>{direction === 'DOWN' ? 'OWF / Salvage Down' : 'RTL / Salvage Up'}</h2>
    <CategoryTable rows={categories(rows, direction === 'DOWN' ? downOrder : [])} tonnes={tonnes}/>
    <dl className="levy-summary"><div><dt>Total weight</dt><dd>{weight(metric(rows, 'Net weight'), tonnes)} {tonnes ? 'tonnes' : 'kg'}</dd></div><div><dt>Records</dt><dd>{(metric(rows, 'Records') / 100n).toString()}</dd></div></dl>
  </section>
}

function IntakeMatrix({ totals, tonnes }: Props) {
  const rows = materialRows(totals.filter(row => intakeWorkflows.includes(row.workflow)))
  const labels = categories(rows)
  const columnFor = (row: Total) => row.workflow !== 'CRATE_PALLET' ? row.workflow
    : row.summaryGroup === 'CRATE' ? 'Crates' : row.summaryGroup === 'PALLET' ? 'Pallets' : 'Other containers'
  const columnNames = ['Crates', 'Pallets', ...(rows.some(row => columnFor(row) === 'Other containers' && exact(row.value) > 0n) ? ['Other containers'] : []), 'COLLECTABLES', 'PICKUP']
  const values = (key: string, column: string) => sum(rows.filter(row => categoryKey(row) === key && columnFor(row) === column))
  const columns = columnNames.map(column => sum(rows.filter(row => columnFor(row) === column)))
  return <section className="panel report-section levy-section levy-wide" data-levy-section="msw"><h2>MSW / general intake</h2><p className="hint">Direct intake material weights ({tonnes ? 'tonnes' : 'kg'}).</p>
    {!labels.length ? <p>No records for this period.</p> : <div className="table-scroll levy-matrix" tabIndex={0} role="region" aria-label="General intake material matrix"><table><thead><tr><th scope="col">Category</th>{columnNames.map(column => <th scope="col" className="numeric" key={column}>{column === 'COLLECTABLES' ? 'Collectables' : column === 'PICKUP' ? 'Pickup' : column}</th>)}<th scope="col" className="numeric">Total</th></tr></thead>
      <tbody>{labels.map(row => <tr key={row.key}><th scope="row">{row.name}</th>{columnNames.map(column => <td className="numeric" key={column}>{weight(values(row.key, column), tonnes)}</td>)}<td className="numeric levy-total">{weight(row.weight, tonnes)}</td></tr>)}</tbody>
      <tfoot><tr><th scope="row">Total weight</th>{columns.map((value, index) => <td className="numeric" key={columnNames[index]}>{weight(value, tonnes)}</td>)}<td className="numeric">{weight(sum(rows), tonnes)}</td></tr></tfoot>
    </table></div>}
  </section>
}

function ElectricalMatrix({ totals, tonnes }: Props) {
  const rows = materialRows(totals.filter(row => row.workflow === 'ELECTRICAL_CRATE_PALLET'))
  const labels = categories(rows)
  // Null-container rows are zero-fill category templates, not weighed containers.
  const containers = [...new Set(rows.flatMap(row => row.container === null ? [] : [row.container]))]
  const values = (container: string, key: string) => sum(rows.filter(row => row.container === container && categoryKey(row) === key))
  const activeRows = rows.filter(row => row.container !== null)
  return <section className="panel report-section levy-section levy-wide" data-levy-section="electrical"><h2>Electrical Crate / Pallet - operational</h2><p className="hint">Stored material weights ({tonnes ? 'tonnes' : 'kg'}), by container.</p>
    {!containers.length ? <p>No records for this period.</p> : <><div className="table-scroll levy-matrix" tabIndex={0} role="region" aria-label="Electrical container material matrix"><table><thead><tr><th scope="col">Container</th>{labels.map(row => <th scope="col" className="numeric" key={row.key}>{row.name}</th>)}<th scope="col" className="numeric">Total</th></tr></thead>
      <tbody>{containers.map(container => <tr key={container}><th scope="row">{container}</th>{labels.map(row => <td className="numeric" key={row.key}>{weight(values(container, row.key), tonnes)}</td>)}<td className="numeric levy-total">{weight(sum(activeRows.filter(row => row.container === container)), tonnes)}</td></tr>)}</tbody>
      <tfoot><tr><th scope="row">Total weight</th>{labels.map(row => <td className="numeric" key={row.key}>{weight(sum(activeRows.filter(value => categoryKey(value) === row.key)), tonnes)}</td>)}<td className="numeric">{weight(sum(activeRows), tonnes)}</td></tr></tfoot>
    </table></div><p className="levy-record-count">Records: {(metric(totals.filter(row => row.workflow === 'ELECTRICAL_CRATE_PALLET'), 'Records') / 100n).toString()}</p></>}
  </section>
}

function WhitegoodsSummary({ totals, tonnes }: Props) {
  const rows = totals.filter(row => row.workflow === 'WHITEGOODS')
  const factors = [...new Set(rows.filter(row => row.metric === 'Applied factor').map(row => amount(row.value, false)))]
  return <section className="panel report-section levy-section levy-wide" data-levy-section="whitegoods"><h2>Whitegoods - estimated</h2>
    <dl className="levy-summary levy-whitegoods"><div><dt>Whitegoods count</dt><dd>{(metric(rows, 'Whitegoods count') / 100n).toString()}</dd></div><div><dt>Estimated weight</dt><dd>{weight(metric(rows, 'Estimated weight'), tonnes)} {tonnes ? 'tonnes' : 'kg'}</dd></div><div><dt>Applied factor{factors.length > 1 ? 's' : ''}</dt><dd>{factors.length ? `${factors.join(' / ')} kg/item` : 'No entries'}</dd></div></dl>
    {factors.length > 1 && <p className="hint">Multiple historical factors were applied. Estimated weight uses the stored entry values.</p>}
  </section>
}

export function LevyWorksheet({ totals, tonnes, containerMaterials }: Props & { containerMaterials: ContainerMaterial[] }) {
  return <div className="levy-worksheet">
    <SalvageSection rows={totals.filter(row => row.workflow === 'SALVAGE' && row.direction === 'DOWN')} direction="DOWN" tonnes={tonnes}/>
    <SalvageSection rows={totals.filter(row => row.workflow === 'SALVAGE' && row.direction === 'UP')} direction="UP" tonnes={tonnes}/>
    <IntakeMatrix totals={[...totals.filter(row => row.workflow !== 'CRATE_PALLET' || row.metric !== 'Material weight' || exact(row.value) === 0n), ...containerMaterials.map(row => ({...row, workflow: 'CRATE_PALLET', direction: null, container: null, metric: 'Material weight', unit: 'kg'}))]} tonnes={tonnes}/>
    <section className="panel report-section levy-section" data-levy-section="combined"><h2>Combined general intake categories</h2><CategoryTable rows={categories(totals.filter(row => row.workflow === 'GENERAL_INTAKE'))} tonnes={tonnes} total/></section>
    <section className="panel report-section levy-section" data-levy-section="scrap"><h2>SCRAP</h2><CategoryTable rows={categories(totals.filter(row => row.workflow === 'SCRAP'), scrapOrder)} tonnes={tonnes} scrap total/></section>
    <ElectricalMatrix totals={totals} tonnes={tonnes}/>
    <WhitegoodsSummary totals={totals} tonnes={tonnes}/>
  </div>
}
