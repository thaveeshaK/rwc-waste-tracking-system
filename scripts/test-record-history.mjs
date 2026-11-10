import assert from 'node:assert/strict'
import test from 'node:test'
import { formatAuditTimestamp, isUnmodified } from '../frontend/src/utils/recordHistory.ts'
const created = {id:1,version:0,createdBy:'staff',updatedBy:'staff',createdAt:'2026-11-10T03:32:00Z',updatedAt:'2026-11-10T03:32:00Z'}
test('only complete matching initial metadata is Not modified', () => {
  assert.equal(isUnmodified(created),true)
  assert.equal(isUnmodified({...created,version:1}),true) // Initial material persistence can bump the version.
  assert.equal(isUnmodified({...created,version:null}),true)
  for (const patch of [{updatedBy:'admin'},{updatedAt:'2026-11-10T03:32:00.000001Z'},{createdBy:null},{createdAt:null},{updatedBy:null},{updatedAt:null},{createdAt:'invalid',updatedAt:'invalid'}]) assert.equal(isUnmodified({...created,...patch}),false)
})
test('formats Hobart summer and winter instants independently of browser timezone', () => {
  const normalise = value => formatAuditTimestamp(value).replace(/[\u202f\u00a0]/g,' ')
  assert.equal(normalise('2026-11-10T03:32:00Z'),'10 Nov 2026, 2:32 pm')
  assert.equal(normalise('2026-07-10T03:32:00Z'),'10 July 2026, 1:32 pm')
})
test('Hobart day rollover and daylight saving fallback are handled', () => {
  assert.match(formatAuditTimestamp('2026-11-09T15:32:00Z'),/^10 Nov 2026, 2:32 am$/)
  assert.equal(formatAuditTimestamp('2026-04-04T15:30:00Z'),formatAuditTimestamp('2026-04-04T16:30:00Z'))
})
test('missing or invalid timestamps are Not recorded', () => {
  for(const value of [null,undefined,'','invalid']) assert.equal(formatAuditTimestamp(value),'Not recorded')
})
