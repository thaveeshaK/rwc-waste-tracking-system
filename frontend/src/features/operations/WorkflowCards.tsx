import type { Workflow } from '../records/types'
import { workflowEntries } from './workflows'

interface Props { onNew: (workflow: Workflow) => void; busy: boolean; unresolved: boolean; currentWorkflow: Workflow }

export function WorkflowCards({onNew,busy,unresolved,currentWorkflow}: Props) {
  return <div className="workflow-card-grid">{workflowEntries.map(workflow => {
    const disabled=busy || (unresolved && currentWorkflow!==workflow.id)
    const resume=unresolved && currentWorkflow===workflow.id
    const action=resume?'Resume submission':workflow.id==='ELECTRICAL_TESTING'?'Open':'New entry'
    return <article key={workflow.id} className="panel workflow-card" data-workflow={workflow.id}
      role="button" tabIndex={disabled?-1:0} aria-disabled={disabled} aria-label={`${action}: ${workflow.name}`}
      onClick={()=>{if(!disabled)onNew(workflow.id)}}
      onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();if(!disabled&&!event.repeat)onNew(workflow.id)}}}>
      <div className="workflow-card-heading"><h2>{workflow.name}</h2><span className="workflow-card-arrow" aria-hidden="true">→</span></div>
      <p>{workflow.description}</p>{resume && <span className="hint">Resume submission</span>}
    </article>
  })}</div>
}
