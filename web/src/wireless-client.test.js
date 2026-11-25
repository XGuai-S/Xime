import test from 'node:test'
import assert from 'node:assert/strict'
import { createWirelessClient } from './wireless-client.js'

test('all fetch routes carry authentication in the header only', async () => {
  const calls = []
  const client = createWirelessClient('?t=test%2Btoken', (...args) => calls.push(args))
  await client.request('/tree')
  await client.request('/read', undefined, '/files/字 &t=no')
  await client.request('/delete', { method: 'POST', headers: { 'Content-Type': 'application/json' } })
  for (const [url, opts] of calls) {
    const parsed = new URL(url, 'http://localhost')
    assert.equal(parsed.searchParams.has('t'), false)
    assert.equal(opts.headers.get('X-Xime-Token'), 'test+token')
    assert.equal(opts.referrerPolicy, 'no-referrer')
    assert.equal(opts.redirect, 'error')
  }
  assert.equal(new URL(calls[1][0], 'http://localhost').searchParams.get('path'), '/files/字 &t=no')
  assert.equal(calls[2][1].headers.get('Content-Type'), 'application/json')
  assert.throws(() => client.request('//elsewhere.invalid/tree'), TypeError)
})

test('upload uses a header and browser download uses an encoded query token', () => {
  const calls = []
  const client = createWirelessClient('?t=a%2Bb')
  client.openUpload({ open: (...args) => calls.push(args), setRequestHeader: (...args) => calls.push(args) })
  assert.deepEqual(calls, [['POST', '/upload', true], ['X-Xime-Token', 'a+b']])
  const url = new URL(client.downloadUrl('/files/文件 #&.txt'), 'http://localhost')
  assert.equal(url.pathname, '/download')
  assert.equal(url.searchParams.get('path'), '/files/文件 #&.txt')
  assert.equal(url.searchParams.get('t'), 'a+b')
  assert.equal(new URL(createWirelessClient('').downloadUrl('file'), 'http://localhost').searchParams.has('t'), false)
})
