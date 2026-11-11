import type { TodayActivitySummary } from '../features/reporting/types'
import { label } from '../features/reporting/types'

export function TodayActivity({today, todayActivity}: TodayActivitySummary) {
  return <section className="dashboard-today" aria-label="Today's activity"><h2>Today's activity</h2><p className="hint">Operational activity for {new Intl.DateTimeFormat('en-AU', {day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Australia/Hobart'}).format(new Date(today+'T00:00:00Z'))}</p>
    <div className="dashboard-today-grid">{todayActivity.map(activity => <article className="panel" key={activity.workflow} data-today-workflow={activity.workflow}><h3>{label(activity.workflow)}</h3><dl>{activity.metrics.map(metric => <div key={metric.label}><dt>{metric.label}</dt><dd>{metric.value}{metric.unit && <small> {metric.unit}</small>}</dd></div>)}</dl></article>)}</div>
  </section>
}
