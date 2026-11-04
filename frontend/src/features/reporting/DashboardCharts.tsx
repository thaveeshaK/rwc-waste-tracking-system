import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, PointElement, LineElement, Tooltip, Legend } from 'chart.js'
import type { ChartOptions } from 'chart.js'
import { Bar, Line } from 'react-chartjs-2'
import type { Overview } from './types'
import { workflowEntries } from '../operations/workflows'
import { typeLabels } from '../electrical-testing/types'
import type { TallyType } from '../electrical-testing/types'
ChartJS.register(CategoryScale, LinearScale, BarElement, PointElement, LineElement, Tooltip, Legend)
const colours = ['#245c43', '#347aa1', '#b47725', '#795797', '#ad5975', '#44877f', '#596a7c', '#586e32', '#795548']
interface Point { name: string; value: string }
function wrap(label: string): string[] {
  const words = label.split(' '), lines: string[] = []
  for (const word of words) {
    const last = lines.length-1
    if (last >= 0 && lines[last].length + word.length < 26) lines[last] += ' '+word
    else lines.push(word)
  }
  return lines
}
function Empty() { return <div className="dashboard-empty"><span aria-hidden="true">-</span><p>No activity recorded for this period.</p></div> }
function BarPanel({title, subtitle, points, unit, colour = '#245c43', id}: {title: string; subtitle: string; points: Point[]; unit: string; colour?: string | string[]; id: string}) {
  const active = points.some(p => Number(p.value) > 0)
  const options: ChartOptions<'bar'> = {
    responsive: true, maintainAspectRatio: false, animation: false, indexAxis: 'y',
    plugins: {legend: {display: false}, tooltip: {callbacks: {label: item => `${points[item.dataIndex].value} ${unit}`}}},
    scales: {x: {beginAtZero: true, title: {display: true, text: unit}, ticks: {precision: unit === 'records' || unit === 'count' ? 0 : 2}, grid: {color: '#e8ede8'}}, y: {grid: {display: false}, ticks: {autoSkip: false, color: '#20382f', font: {size: 12}}}},
  }
  return <section className="panel dashboard-chart" aria-label={title} data-chart={id}><h2>{title}</h2><p className="hint">{subtitle}</p>
    {active ? <div className="dashboard-chart-canvas" style={{height: Math.max(250, points.length*42)}}><Bar role="img" aria-label={title} options={options} data={{labels: points.map(p => wrap(p.name)), datasets: [{label: unit, data: points.map(p => Number(p.value)), backgroundColor: colour, borderRadius: 4, maxBarThickness: 22}]}}
      fallbackContent={<ul>{points.map(p => <li key={p.name}>{p.name}: {p.value} {unit}</li>)}</ul>}/></div> : <Empty/>}
    <ul className="sr-only" data-chart-values={id}>{points.map(p => <li key={p.name} data-name={p.name} data-value={p.value}>{p.name}: {p.value} {unit}</li>)}</ul>
  </section>
}
export default function DashboardCharts({data}: {data: Overview}) {
  const months = [...new Set(data.trend.map(t => t.month))]
  const monthLabel = (value: string) => new Intl.DateTimeFormat('en-AU', {month: 'short', year: '2-digit', timeZone: 'UTC'}).format(new Date(value+'-01T00:00:00Z'))
  const trendOptions: ChartOptions<'line'> = {responsive: true, maintainAspectRatio: false, animation: false,
    plugins: {legend: {display: false}, tooltip: {mode: 'index', intersect: false}},
    interaction: {mode: 'index', intersect: false},
    scales: {x: {grid: {display: false}}, y: {beginAtZero: true, ticks: {precision: 0}, title: {display: true, text: 'Records'}, grid: {color: '#e8ede8'}}}}
  return <div className="dashboard-chart-grid">
    <section className="panel dashboard-chart dashboard-trend" aria-label="Six-month operational activity" data-chart="trend"><h2>Six-month operational activity</h2><p className="hint">Recorded entries by workflow. The current month is partial; Electrical Testing counts tally submissions, not individual items.</p>
      {data.trend.some(t => t.count > 0) ? <><div className="dashboard-chart-canvas trend-canvas"><Line role="img" aria-label="Six-month operational activity" options={trendOptions} data={{labels: months.map(monthLabel), datasets: workflowEntries.map((w, i) => ({label: w.name, data: months.map(m => data.trend.find(t => t.month === m && t.workflow === w.id)?.count ?? 0), borderColor: colours[i], backgroundColor: colours[i], borderWidth: 2, pointRadius: 3, tension: 0.15}))}} fallbackContent={<p>Six calendar months of workflow record counts. Numerical details are available in Reports.</p>}/></div><ul className="dashboard-legend">{workflowEntries.map((w, i) => <li key={w.id}><span style={{background: colours[i]}} aria-hidden="true"/>{w.name}</li>)}</ul></> : <Empty/>}
      <ul className="sr-only" data-chart-values="trend">{data.trend.map(t => <li key={t.month+t.workflow} data-month={t.month} data-workflow={t.workflow} data-value={t.count}>{t.month}, {workflowEntries.find(w => w.id === t.workflow)?.name}: {t.count} records</li>)}</ul>
    </section>
    <BarPanel id="activity" title="Workflow activity" subtitle="Record counts for the selected month. Each Salvage trip is one record." unit="records" colour={colours} points={data.activity.map(a => ({name: workflowEntries.find(w => w.id === a.workflow)?.name ?? a.workflow, value: String(a.count)}))}/>
    <BarPanel id="salvage" title="Salvage recovered vs landfill" subtitle="Council-provided weights for the selected month." unit="kg" colour={['#245c43', '#b47725']} points={[{name: 'Salvage Down / recovered', value: data.metrics.salvageRecoveredKg}, {name: 'Salvage Up / landfill', value: data.metrics.landfillKg}]}/>
    <BarPanel id="testing" title="Electrical Testing activity" subtitle="Processing tallies, not unique items or stock balances. Sold Last Month is excluded." unit="count" colour="#795797" points={data.electricalTesting.map(t => ({name: typeLabels[t.status as TallyType], value: t.count}))}/>
    <BarPanel id="intake" title="Top intake materials this month" subtitle="Crate / Pallet, Collectables, Pickup and Salvage Down. Top eight stored category weights; not a unique-material total." unit="kg" points={data.topIntake.map(t => ({name: t.category ?? '', value: t.value}))}/>
    <section className="panel dashboard-chart" aria-label="Salvage recovered trend" data-chart="recovered-trend"><h2>Salvage recovered trend</h2><p className="hint">Six months of council-provided Salvage Down weight. The current month is partial.</p>
      {data.recoveredTrend.some(t => Number(t.kg) > 0) ? <div className="dashboard-chart-canvas" style={{height: 336}}><Line role="img" aria-label="Salvage recovered trend" options={{...trendOptions, plugins: {legend: {display: false}, tooltip: {callbacks: {label: item => `${data.recoveredTrend[item.dataIndex].kg} kg`}}}, scales: {x: {grid: {display: false}}, y: {beginAtZero: true, title: {display: true, text: 'kg'}}}}} data={{labels: data.recoveredTrend.map(t => monthLabel(t.month)), datasets: [{label: 'Salvage Down / recovered', data: data.recoveredTrend.map(t => Number(t.kg)), borderColor: colours[0], backgroundColor: colours[0], tension: 0.15}]}}/></div> : <Empty/>}
      <ul className="sr-only" data-chart-values="recovered-trend">{data.recoveredTrend.map(t => <li key={t.month} data-month={t.month} data-value={t.kg}>{t.month}: {t.kg} kg</li>)}</ul>
    </section>
  </div>
}
