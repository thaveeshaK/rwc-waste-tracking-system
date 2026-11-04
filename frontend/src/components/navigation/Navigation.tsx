import { SettingsMenu } from './SettingsMenu'
import type { SettingsSection } from '../../features/settings/sections'
export type Page = 'operations' | 'entry' | 'records' | 'overview' | 'reports' | 'waste-levy' | 'settings'
interface Props { isAdmin: boolean; page: Page; busy: boolean; section?: SettingsSection; onPage: (page: Page) => void; onSettings: (section: SettingsSection) => void }
export function Navigation({isAdmin, page, busy, section, onPage, onSettings}: Props) {
  function link(target: Page, text: string) { return <button disabled={busy} aria-current={page === target ? 'page' : undefined} className={'nav-item'+(page === target ? ' active' : '')} onClick={() => onPage(target)}>{text}</button> }
  return <nav className="role-navigation" aria-label={isAdmin ? 'Admin navigation' : 'Staff navigation'}>
    <div className="primary-navigation"><div className="management-navigation">{isAdmin && link('overview', 'Overview')}{link('operations', isAdmin ? 'Workflows' : 'Operations')}{isAdmin && <>{link('reports', 'Reports')}{link('waste-levy', 'Waste Levy Summary')}</>}{link('records', 'Records')}</div>{isAdmin && <SettingsMenu busy={busy} selected={page === 'settings' ? section : undefined} onSelect={onSettings}/>}</div>
  </nav>
}
