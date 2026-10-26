import type { Workflow } from '../../features/records/types'
export type Page = 'operations' | 'entry' | 'records' | 'overview' | 'reports' | 'waste-levy'
const entries: [Workflow, string][] = [
  ['CRATE_PALLET', 'New Crate / Pallet'], ['SALVAGE', 'New Salvage'], ['PICKUP', 'New Pickup'],
  ['ELECTRICAL_CRATE_PALLET', 'New Electrical Crate / Pallet'], ['COLLECTABLES', 'New Collectables'],
  ['WHITEGOODS', 'New Whitegoods'], ['ELECTRICAL_TESTING', 'Electrical Testing'],
]
interface Props { isAdmin: boolean; page: Page; busy: boolean; unresolved: boolean; workflow: Workflow; onPage: (page: Page) => void; onNew: (workflow: Workflow) => void }
export function Navigation({isAdmin, page, busy, unresolved, workflow, onPage, onNew}: Props) {
  function link(target: Page, text: string) { return <button disabled={busy} className={'nav-item'+(page === target ? ' active' : '')} onClick={() => onPage(target)}>{text}</button> }
  return <nav className="role-navigation" aria-label={isAdmin ? 'Admin navigation' : 'Staff navigation'}>
    <div className="management-navigation">{isAdmin && link('overview', 'Overview')}{link('operations', isAdmin ? 'Workflows' : 'Operations')}{isAdmin && <>{link('reports', 'Reports')}{link('waste-levy', 'Waste Levy Summary')}</>}{link('records', 'Records')}</div>
    <div className="workflow-navigation" aria-label={isAdmin ? 'Workflows' : 'Operations'}>{entries.map(([w, name]) => <button key={w} disabled={busy || (unresolved && workflow !== w)} className={'nav-item'+(page === 'entry' && workflow === w ? ' active' : '')} onClick={() => onNew(w)}>{unresolved && workflow === w ? 'Resume submission' : name}</button>)}</div>
  </nav>
}
