import { IncompleteIndicator } from '../../components/IncompleteIndicator'
import type { Workflow } from '../records/types'
import { WorkflowCards } from './WorkflowCards'
interface Props { onIncomplete: () => void; onNew: (workflow: Workflow) => void; busy: boolean; unresolved: boolean; currentWorkflow: Workflow }
export function WorkflowsPage({onIncomplete, onNew, busy, unresolved, currentWorkflow}: Props) {
  return <section><div className="page-heading"><div><p className="eyebrow">OPERATIONS</p><div className="workflows-heading"><h1>Workflows</h1><IncompleteIndicator refreshKey="workflows" onOpen={onIncomplete}/></div><p>Choose a workflow to enter and review a record.</p></div></div>
    <WorkflowCards onNew={onNew} busy={busy} unresolved={unresolved} currentWorkflow={currentWorkflow}/>
  </section>
}
