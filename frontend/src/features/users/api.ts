import {jsonRequest,request} from '../../api/client'
export interface ManagedUser {id:number;username:string;role:'STAFF'|'ADMIN';active:boolean;version:number;createdAt:string|null;updatedAt:string|null;createdBy:string|null;updatedBy:string|null}
export interface UserPage {items:ManagedUser[];page:number;size:number;totalItems:number;totalPages:number}
const base='/api/admin/users'
export const listUsers=(page:number,size:number,query='')=>request<UserPage>(`${base}?page=${page}&size=${size}&query=${encodeURIComponent(query)}`)
export const createUser=(username:string,password:string,role:string)=>jsonRequest<ManagedUser>(base,'POST',{username,password,role})
export const changeRole=(user:ManagedUser,role:string)=>jsonRequest<ManagedUser>(`${base}/${user.id}/role`,'PUT',{role,version:user.version})
export const changeStatus=(user:ManagedUser)=>jsonRequest<ManagedUser>(`${base}/${user.id}/status`,'PATCH',{active:!user.active,version:user.version})
export const resetPassword=(user:ManagedUser,password:string)=>jsonRequest<ManagedUser>(`${base}/${user.id}/password`,'PUT',{password,version:user.version})
