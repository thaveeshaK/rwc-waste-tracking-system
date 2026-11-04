import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { settingsSections } from '../../features/settings/sections'
import type { SettingsSection } from '../../features/settings/sections'
interface Props { busy: boolean; selected?: SettingsSection; onSelect: (section: SettingsSection) => void }
export function SettingsMenu({busy, selected, onSelect}: Props) {
  const [open, setOpen] = useState(false)
  const wrapper = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    function outside(event: Event) { if (!wrapper.current?.contains(event.target as Node)) setOpen(false) }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('click', outside)
    document.addEventListener('focusin', outside)
    wrapper.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')?.focus()
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('click', outside); document.removeEventListener('focusin', outside) }
  }, [open])
  function keyboard(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); setOpen(false); trigger.current?.focus(); return }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    if (!open) { setOpen(true); return }
    const items = [...(wrapper.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])]
    const current = items.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length-1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length
    items[next]?.focus()
  }
  return <div className="settings-navigation" ref={wrapper} onKeyDown={keyboard}><button className="settings-menu-trigger" ref={trigger} disabled={busy} aria-haspopup="menu" aria-expanded={open} aria-controls="admin-settings-menu" onClick={() => setOpen(value => !value)}>Settings <span aria-hidden="true" className="settings-caret"/></button>
    {open && <div id="admin-settings-menu" className="settings-menu" role="menu" aria-label="Settings sections">{settingsSections.map(s => <button key={s.id} role="menuitem" disabled={busy} aria-current={selected === s.id ? 'page' : undefined} onClick={() => {setOpen(false); trigger.current?.focus(); onSelect(s.id)}}>{s.label}</button>)}</div>}
  </div>
}
