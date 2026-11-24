import { useLayoutEffect, useRef } from 'react'
import type { ReactNode } from 'react'

interface Props {
  labelledBy: string
  className?: string
  children: ReactNode
  busy: boolean
  onClose: () => void
  restoreFocus: () => void
}

export function Dialog({labelledBy,className='',children,busy,onClose,restoreFocus}: Props) {
  const dialog=useRef<HTMLDialogElement>(null)
  useLayoutEffect(()=>{
    const element=dialog.current!
    const overflow=document.body.style.overflow
    element.showModal()
    document.body.style.overflow='hidden'
    return()=>{
      element.close()
      document.body.style.overflow=overflow
      restoreFocus()
    }
  },[restoreFocus])
  return <dialog ref={dialog} className={`panel modal-dialog ${className}`} aria-labelledby={labelledBy} aria-modal="true"
    onKeyDown={event=>{
      if(event.key!=='Tab')return
      const elements=[...event.currentTarget.querySelectorAll<HTMLElement>('button,input,select,textarea,a[href],[tabindex]:not([tabindex="-1"])')]
        .filter(element=>!element.matches(':disabled')&&element.tabIndex>=0&&element.getClientRects().length>0)
      const index=elements.indexOf(document.activeElement as HTMLElement)
      if(index<0 || (!event.shiftKey && index===elements.length-1) || (event.shiftKey && index===0)) {
        event.preventDefault()
        const target=(event.shiftKey?elements.at(-1):elements[0])??event.currentTarget
        target.focus()
      }
    }}
    onCancel={event=>{event.preventDefault();if(!busy)onClose()}}>{children}</dialog>
}
