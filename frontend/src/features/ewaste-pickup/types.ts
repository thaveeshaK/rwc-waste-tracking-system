export interface ContainerType { id: number; name: string; tareWeight: string }
export interface ContainerInput { containerTypeId: number; quantity: number }
export interface EwastePickupInput { submissionId: string; version?: number; recordDate: string; organisation: string; enteredWeight: string; containers: ContainerInput[] }
export interface ContainerResult extends ContainerInput { name: string; tarePerUnit: string; tareSubtotal: string }
export interface EwastePickupRecord { id: number|null; version: number|null; submissionId: string|null; recordDate: string; organisation: string; enteredWeight: string; totalTareWeight: string; netWeight: string; containers: ContainerResult[]; createdBy: string|null;createdAt:string|null;updatedBy:string|null;updatedAt:string|null }
