import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { Workflow } from '../../features/records/types'
import { workflowEntries } from '../../features/operations/workflows'
interface Props { busy: boolean; unresolved: boolean; workflow: Workflow; onNew: (workflow: Workflow) => void }
export function NewEntryMenu({busy, unresolved, workflow, onNew}: Props) {
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
  return <div className="new-entry" ref={wrapper} onKeyDown={keyboard}><button className="new-entry-trigger" ref={trigger} disabled={busy} aria-haspopup="menu" aria-expanded={open} aria-controls="admin-new-entry-menu" onClick={() => setOpen(value => !value)}>+ New Entry</button>
    {open && <div id="admin-new-entry-menu" className="new-entry-menu" role="menu" aria-label="Choose a workflow">{workflowEntries.map(w => <button key={w.id} role="menuitem" data-workflow={w.id} disabled={busy || (unresolved && workflow !== w.id)} onClick={() => {setOpen(false); trigger.current?.focus(); onNew(w.id)}}>{w.name}{unresolved && workflow === w.id && <span className="hint">Resume submission</span>}</button>)}</div>}
  </div>
}
