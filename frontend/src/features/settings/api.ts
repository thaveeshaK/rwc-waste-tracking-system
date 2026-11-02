import { request, jsonRequest } from '../../api/client'
import type { Configuration, Reference, ReferenceInput, OperationalConfiguration } from './types'
const base = '/api/admin/settings'
export const getSettings = () => request<Configuration>(base)
export const saveSettings = (settings: Configuration) => jsonRequest<Configuration>(base, 'PUT', settings)
export const getReferences = (container: boolean) => request<Reference[]>(base + (container ? '/container-types' : '/material-categories'))
export const saveReference = (container: boolean, input: ReferenceInput, id?: number) => jsonRequest<Reference>(base + (container ? '/container-types' : '/material-categories') + (id ? '/'+id : ''), id ? 'PUT' : 'POST', input)
export const getOperationalConfiguration = () => request<OperationalConfiguration>('/api/operational-configuration')
