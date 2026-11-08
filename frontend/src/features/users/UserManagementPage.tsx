import {useEffect,useState} from 'react'
import type {FormEvent} from 'react'
import {Pagination} from '../../components/Pagination'
import {currentUser} from '../auth/api'
import {errorMessage} from '../../api/client'
import {listUsers,createUser,changeRole,changeStatus,resetPassword} from './api'
import type {ManagedUser,UserPage} from './api'
type Editor={kind:'create'}|{kind:'role'|'status'|'password';user:ManagedUser}
const validPassword=(password:string)=>password.trim().length>0&&new TextEncoder().encode(password).length<=72
const date=(value:string|null)=>value?new Date(value).toLocaleString('en-AU',{timeZone:'Australia/Hobart'}):'Not recorded'
export function UserManagementPage() {
  const [page,setPage]=useState(0),[size,setSize]=useState(10),[attempt,setAttempt]=useState(0)
  const [result,setResult]=useState<{key:string;data:UserPage}|null>(null)
  const [search,setSearch]=useState(''),[query,setQuery]=useState('')
  const [self,setSelf]=useState<number|null>(null)
  const [editor,setEditor]=useState<Editor|null>(null)
  const [username,setUsername]=useState(''),[password,setPassword]=useState(''),[confirmation,setConfirmation]=useState('')
  const [role,setRole]=useState<'STAFF'|'ADMIN'>('STAFF')
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('')
  const key=`${query}:${page}:${size}:${attempt}`,loading=result?.key!==key
  useEffect(()=>{let cancelled=false;Promise.all([listUsers(page,size,query),currentUser()]).then(([data,user])=>{if(!cancelled){setResult({key,data});setSelf(user.id);if(data.page!==page)setPage(data.page)}}).catch(e=>{if(!cancelled)setError(errorMessage(e))});return()=>{cancelled=true}},[page,size,query,key])
  function open(next:Editor){setEditor(next);setPassword('');setConfirmation('');setUsername('');setRole(next.kind==='role'?next.user.role:'STAFF');setError('');setMessage('')}
  function close(){setEditor(null);setPassword('');setConfirmation('')}
  const passwordForm=editor?.kind==='create'||editor?.kind==='password'
  const valid=(!passwordForm||(validPassword(password)&&password===confirmation))&&(editor?.kind!=='create'||username.trim().length>0)
  async function save(event:FormEvent){event.preventDefault();if(!editor||!valid||busy)return;setBusy(true);setError('')
    try{if(editor.kind==='create')await createUser(username.trim(),password,role)
      else if(editor.kind==='role')await changeRole(editor.user,role)
      else if(editor.kind==='status')await changeStatus(editor.user)
      else await resetPassword(editor.user,password)
      setMessage(editor.kind==='create'?'User created successfully.':'User updated successfully.');close();setAttempt(n=>n+1)
    }catch(e){setError(errorMessage(e));setPassword('');setConfirmation('')}finally{setBusy(false)}
  }
  return <div className="user-management">
    <div className="detail-heading"><p>Manage sign-in access. Usernames are permanent; accounts are deactivated rather than deleted.</p><button disabled={busy||loading} onClick={()=>open({kind:'create'})}>Add User</button></div>
    {message&&<p className="notice success" role="status">{message}</p>}{error&&<p className="notice error" role="alert">{error}</p>}
    <form className="filter-bar" onSubmit={event=>{event.preventDefault();if(busy)return;close();setError('');setPage(0);setQuery(search.trim());setAttempt(n=>n+1)}}><label>Search username<input maxLength={100} value={search} disabled={busy} onChange={event=>setSearch(event.target.value)}/></label><button disabled={busy}>Search users</button><button type="button" className="secondary" disabled={busy} onClick={()=>{close();setSearch('');setQuery('');setPage(0);setAttempt(n=>n+1)}}>Clear search</button></form>
    <div className="actions"><button className="secondary" disabled={busy} onClick={()=>{close();setError('');setAttempt(n=>n+1)}}>Reload users</button></div>
    {editor&&<form className="panel user-editor" onSubmit={save}><fieldset disabled={busy}><legend>{editor.kind==='create'?'Add User':editor.kind==='role'?'Change Role':editor.kind==='password'?'Reset Password':editor.user.active?'Deactivate User':'Activate User'}</legend>
      {editor.kind!=='create'&&<p><strong>{editor.user.username}</strong></p>}
      <div className="form-grid">{editor.kind==='create'&&<label>Username<input required maxLength={100} autoComplete="off" value={username} onChange={e=>setUsername(e.target.value)}/></label>}
        {(editor.kind==='create'||editor.kind==='role')&&<label>Role<select required value={role} onChange={e=>setRole(e.target.value as 'STAFF'|'ADMIN')}><option value="STAFF">STAFF</option><option value="ADMIN">ADMIN</option></select></label>}
        {passwordForm&&<><label>{editor.kind==='create'?'Password':'New password'}<input type="password" required autoComplete="new-password" value={password} onChange={e=>setPassword(e.target.value)}/></label><label>Confirm password<input type="password" required autoComplete="new-password" value={confirmation} onChange={e=>setConfirmation(e.target.value)}/></label></>}
      </div>
      {passwordForm&&<><p className="hint">Password must not be blank. Maximum 72 UTF-8 bytes. Passwords are never displayed after saving.</p>{confirmation&&password!==confirmation&&<p role="alert" className="notice error">Passwords do not match.</p>}{password&&!validPassword(password)&&<p role="alert" className="notice error">Check the password: it must not be blank or exceed 72 UTF-8 bytes.</p>}</>}
      {editor.kind==='status'&&<p>{editor.user.active?`Deactivate ${editor.user.username}? This user will no longer be able to sign in. Historical records will remain unchanged.`:`Reactivate ${editor.user.username}? This user will be able to sign in again.`}</p>}
      {editor.kind==='role'&&<p>Change {editor.user.username} from {editor.user.role} to {role}? Existing sessions will require sign-in again.</p>}
      {editor.kind==='password'&&<p>Reset the password for {editor.user.username}? Existing sessions will require sign-in again. The old password will no longer work.</p>}
      <div className="actions"><button disabled={busy||!valid||(editor.kind==='role'&&role===editor.user.role)}>{busy?'Saving...':editor.kind==='create'?'Create user':editor.kind==='role'?'Confirm role change':editor.kind==='password'?'Reset password':editor.user.active?'Confirm deactivation':'Confirm activation'}</button><button type="button" className="secondary" disabled={busy} onClick={close}>Cancel</button></div>
    </fieldset></form>}
    <section className="panel report-section" aria-label="Users">{loading?<p role="status">Loading users...</p>:result&&<>{!result.data.items.length?<p>No users found.</p>:<div className="table-scroll"><table><thead><tr><th>Username</th><th>Role</th><th>Status</th><th>Created</th><th>Last updated</th><th>Actions</th></tr></thead><tbody>{result.data.items.map(user=><tr key={user.id} data-user-id={user.id}><td>{user.username}{user.id===self&&' (you)'}</td><td>{user.role}</td><td>{user.active?'Active':'Inactive'}</td><td>{date(user.createdAt)}</td><td>{date(user.updatedAt)}</td><td><div className="user-actions"><button className="text-button" disabled={busy||user.id===self} onClick={()=>open({kind:'role',user})}>Change Role</button><button className="text-button" disabled={busy||user.id===self} onClick={()=>open({kind:'password',user})}>Reset Password</button><button className="text-button" disabled={busy||user.id===self} onClick={()=>open({kind:'status',user})}>{user.active?'Deactivate':'Activate'}</button></div></td></tr>)}</tbody></table></div>}
      <Pagination label="Users" page={page} size={size} totalItems={result.data.totalItems} busy={busy||loading} onPage={p=>{close();setPage(p)}} onSize={s=>{close();setSize(s);setPage(0)}}/>
    </>}</section><p className="hint">At least one active Admin must remain. Your account cannot be deactivated or demoted by you; ask another Admin to reset your password. New usernames must be unique regardless of case. Sign in using the stored username spelling.</p>
  </div>
}
