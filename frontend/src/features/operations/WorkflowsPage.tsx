import type { Workflow } from '../records/types'
import { workflowEntries } from './workflows'
interface Props { onNew: (workflow: Workflow) => void; busy: boolean; unresolved: boolean; currentWorkflow: Workflow }
export function WorkflowsPage({onNew, busy, unresolved, currentWorkflow}: Props) {
  return <section><div className="page-heading"><div><p className="eyebrow">OPERATIONS</p><h1>Workflows</h1><p>Choose a workflow to enter and review a record.</p></div></div>
    <div className="workflow-card-grid">{workflowEntries.map(w => <article className="panel workflow-card" key={w.id} data-workflow={w.id}><h2>{w.name}</h2><p>{w.description}</p><button disabled={busy || (unresolved && currentWorkflow !== w.id)} aria-label={`New entry: ${w.name}`} onClick={() => onNew(w.id)}>{unresolved && currentWorkflow === w.id ? 'Resume submission' : 'New Entry'}</button></article>)}</div>
  </section>
}
