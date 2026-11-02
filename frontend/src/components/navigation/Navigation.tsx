import { NewEntryMenu } from './NewEntryMenu'
import { workflowEntries } from '../../features/operations/workflows'
import type { Workflow } from '../../features/records/types'
export type Page = 'operations' | 'entry' | 'records' | 'overview' | 'reports' | 'waste-levy' | 'settings'
interface Props { isAdmin: boolean; page: Page; busy: boolean; unresolved: boolean; workflow: Workflow; onPage: (page: Page) => void; onNew: (workflow: Workflow) => void }
export function Navigation({isAdmin, page, busy, unresolved, workflow, onPage, onNew}: Props) {
  function link(target: Page, text: string) { return <button disabled={busy} aria-current={page === target ? 'page' : undefined} className={'nav-item'+(page === target ? ' active' : '')} onClick={() => onPage(target)}>{text}</button> }
  return <nav className="role-navigation" aria-label={isAdmin ? 'Admin navigation' : 'Staff navigation'}>
    <div className="primary-navigation"><div className="management-navigation">{isAdmin && link('overview', 'Overview')}{link('operations', isAdmin ? 'Workflows' : 'Operations')}{isAdmin && <>{link('reports', 'Reports')}{link('waste-levy', 'Waste Levy Summary')}</>}{link('records', 'Records')}{isAdmin && link('settings', 'Settings')}</div>{isAdmin && <NewEntryMenu busy={busy} unresolved={unresolved} workflow={workflow} onNew={onNew}/>}</div>
    {!isAdmin && <div className="workflow-navigation" aria-label="Operations">{workflowEntries.map(w => <button key={w.id} disabled={busy || (unresolved && workflow !== w.id)} className={'nav-item'+(page === 'entry' && workflow === w.id ? ' active' : '')} onClick={() => onNew(w.id)}>{unresolved && workflow === w.id ? 'Resume submission' : w.staffLabel}</button>)}</div>}
  </nav>
}
