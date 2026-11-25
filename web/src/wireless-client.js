// API requests stay on this origin. Downloads use query auth to remain browser-streamed.
export function createWirelessClient(search, fetcher = globalThis.fetch) {
  const token = new URLSearchParams(search).get('t')
  return {
    request(route, options = {}, path) {
      if (!['/tree', '/read', '/delete'].includes(route)) throw new TypeError('Invalid wireless API route')
      const headers = new Headers(options.headers)
      if (token) headers.set('X-Xime-Token', token)
      const url = path === undefined ? route : `${route}?${new URLSearchParams({ path })}`
      return fetcher(url, { ...options, headers, redirect: 'error', referrerPolicy: 'no-referrer' })
    },
    openUpload(xhr) {
      xhr.open('POST', '/upload', true)
      if (token) xhr.setRequestHeader('X-Xime-Token', token)
    },
    downloadUrl(path) {
      const query = new URLSearchParams({ path })
      if (token) query.set('t', token)
      return `/download?${query}`
    },
  }
}
