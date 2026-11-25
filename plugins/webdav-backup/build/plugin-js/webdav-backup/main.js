var definePlugin = function (spec) { return spec; };
var plugin = (function() {

//#region libs/url-codec.ts
/** JS 字符串 → UTF-8 字节（TextEncoder 由宿主注入）。 */
	function utf8Bytes(value) {
		return new TextEncoder().encode(value);
	}
	function isHexDigit(ch) {
		return ch >= "0" && ch <= "9" || ch >= "a" && ch <= "f" || ch >= "A" && ch <= "F";
	}
	function encodePath(path) {
		const bytes = utf8Bytes(path);
		let out = "";
		for (let i = 0; i < bytes.length; i++) {
			const b = bytes[i];
			if (b >= 48 && b <= 57 || b >= 65 && b <= 90 || b >= 97 && b <= 122 || b === 45 || b === 46 || b === 95 || b === 126 || b === 47 || b === 58) out += String.fromCharCode(b);
			else out += "%" + (b < 16 ? "0" : "") + b.toString(16).toUpperCase();
		}
		return out;
	}
	function decodeSegment(segment) {
		const bytes = [];
		for (let i = 0; i < segment.length; i++) if (segment.charCodeAt(i) === 37 && i + 2 < segment.length && isHexDigit(segment.charAt(i + 1)) && isHexDigit(segment.charAt(i + 2))) {
			bytes.push(parseInt(segment.slice(i + 1, i + 3), 16));
			i += 2;
		} else {
			const encoded = utf8Bytes(segment.charAt(i));
			for (let j = 0; j < encoded.length; j++) bytes.push(encoded[j]);
		}
		return new TextDecoder().decode(new Uint8Array(bytes));
	}
	function hrefToPath(href) {
		if (!href) return null;
		const m = href.match(/^https?:\/\/[^/]+(\/.*)$/);
		let p = m ? m[1] : href;
		if (p.charAt(0) !== "/") p = "/" + p;
		const decoded = [];
		for (const seg of p.split("/")) {
			if (seg === "") continue;
			decoded.push(decodeSegment(seg));
		}
		return "/" + decoded.join("/");
	}

//#endregion
//#region libs/webdav-xml.ts
	const MONTHS = {
		Jan: 1,
		Feb: 2,
		Mar: 3,
		Apr: 4,
		May: 5,
		Jun: 6,
		Jul: 7,
		Aug: 8,
		Sep: 9,
		Oct: 10,
		Nov: 11,
		Dec: 12
	};
	function epochFromParts(y, m, d, hh, mm, ss) {
		if (!y || !m || !d) return 0;
		let yy = y;
		if (m <= 2) yy = yy - 1;
		const era = Math.floor(yy / 400);
		const yoe = yy - era * 400;
		const mp = m + (m > 2 ? -3 : 9);
		const doy = Math.floor((153 * mp + 2) / 5) + d - 1;
		const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
		return ((era * 146097 + doe - 719468) * 86400 + (hh || 0) * 3600 + (mm || 0) * 60 + (ss || 0)) * 1e3;
	}
	function parseTime(block) {
		const m = block.match(/<[dD]:?creationdate>\s*(\d{4})-(\d{2})-(\d{2})T(\d+):(\d+):(\d+)/);
		if (m) return epochFromParts(parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10), parseInt(m[4], 10), parseInt(m[5], 10), parseInt(m[6], 10));
		const g = block.match(/<[dD]:?getlastmodified>\s*\w+,\s*(\d+)\s+(\w+)\s+(\d+)\s+(\d+):(\d+):(\d+)/);
		if (g && MONTHS[g[2]]) return epochFromParts(parseInt(g[3], 10), MONTHS[g[2]], parseInt(g[1], 10), parseInt(g[4], 10), parseInt(g[5], 10), parseInt(g[6], 10));
		return 0;
	}
	function tagValue(block, tag) {
		const re = new RegExp("<[dD]:?" + tag + "[^>]*>\\s*([\\s\\S]*?)\\s*</[dD]:?" + tag + ">");
		const m = block.match(re);
		return m ? m[1] : null;
	}
	function parseLength(raw) {
		if (raw === null || raw.trim() === "") return -1;
		const n = Number(raw.trim());
		return Number.isFinite(n) ? n : -1;
	}
	/**
	* 解析 PROPFIND Depth:1 响应，返回 basePath 下的文件条目（不含目录），
	* 按 createdAt 降序（最新在前）。无法解析的响应返回空数组。
	*/
	function parseBackupList(xml, basePath) {
		const basePrefix = basePath + "/";
		const items = [];
		const blockRe = /<[dD]:?response[^>]*>([\s\S]*?)<\/[dD]:?response>/g;
		let bm;
		while ((bm = blockRe.exec(xml)) !== null) {
			const block = bm[1];
			const p = hrefToPath(tagValue(block, "href"));
			if (p === null) continue;
			if (p.slice(-1) === "/" || /<d?:?collection/.test(block.toLowerCase())) continue;
			if (p.substring(0, basePrefix.length) !== basePrefix) continue;
			const name = p.substring(basePrefix.length);
			if (name === "" || name.indexOf("/") !== -1) continue;
			items.push({
				id: p,
				name,
				createdAt: parseTime(block),
				size: parseLength(tagValue(block, "getcontentlength"))
			});
		}
		items.sort((a, b) => b.createdAt - a.createdAt);
		return items;
	}

//#endregion
//#region main.ts
	function getConfig() {
		return {
			url: host.config.get("url") || "",
			username: host.config.get("username") || "",
			password: host.config.get("password") || "",
			remotePath: host.config.get("remote_path") || "/xime_backup"
		};
	}
	function resolveBase() {
		const cfg = getConfig();
		const result = {
			origin: "",
			base: "",
			basePath: "",
			davRoot: "",
			headers: {},
			cfg,
			error: null
		};
		if (cfg.url === "") {
			result.error = "请先填写服务器地址";
			return result;
		}
		if (cfg.username === "") {
			result.error = "请先填写账号";
			return result;
		}
		let url = cfg.url;
		if (!/^https?:\/\//.test(url)) url = "https://" + url;
		url = url.replace(/\/+$/, "");
		const m = url.match(/^https?:\/\/[^/]+/);
		const origin = m ? m[0] : url;
		const urlPath = url.substring(origin.length);
		const remote = cfg.remotePath.replace(/^\/+/, "").replace(/\/+$/, "");
		result.origin = origin;
		result.base = origin + urlPath + "/" + remote;
		result.basePath = urlPath + "/" + remote;
		result.davRoot = origin + urlPath;
		if (cfg.password !== "") {
			const b64 = host.crypto.base64(new TextEncoder().encode(cfg.username + ":" + cfg.password));
			if (!b64) {
				result.error = "Basic 认证编码失败";
				return result;
			}
			result.headers["Authorization"] = "Basic " + b64;
		}
		return result;
	}
	async function ensureCollection(cfg, headers) {
		const { davRoot } = resolveBase();
		if (!davRoot) return false;
		const remote = cfg.remotePath.replace(/^\/+/, "").replace(/\/+$/, "");
		let cur = davRoot;
		for (const seg of remote.split("/").filter((s) => s !== "")) {
			cur = cur + "/" + encodePath(seg);
			try {
				const res = await host.http.request("MKCOL", cur, headers, null);
				if (res.status !== 201 && res.status !== 405 && res.status !== 200 && res.status !== 301) return false;
			} catch (e) {
				host.logError("MKCOL 失败: " + cur + " " + (e.message || ""));
				return false;
			}
		}
		return true;
	}
	function statusDetail(res) {
		let body = res.text || "";
		body = body.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
		if (body === "") return "";
		return ": " + body.substring(0, 160);
	}
	/** 文件级备份根目录（URL 形式，未编码前）。 */
	function filesUrl() {
		const { base } = resolveBase();
		return base.replace(/\/+$/, "") + "/files";
	}
	/** 文件级备份根目录（URL 路径形式，供 PROPFIND 与 parser 用）。 */
	function filesPath() {
		const { basePath } = resolveBase();
		return basePath.replace(/\/+$/, "") + "/files";
	}
	/** 逐级 MKCOL 建出 <files>/<dir 路径>，返回是否成功。已存在（405/301）也算成功。 */
	async function ensureFileDirs(headers, segments) {
		const { cfg } = resolveBase();
		let cur = filesUrl();
		const all = ["files"].concat(segments);
		for (const seg of all) {
			if (seg === "files") {} else cur = cur + "/" + encodePath(seg);
			try {
				const res = await host.http.request("MKCOL", encodePath(cur), headers, null);
				if (res.status !== 201 && res.status !== 405 && res.status !== 200 && res.status !== 301) return false;
			} catch (e) {
				host.logError("MKCOL 失败: " + cur + " " + (e.message || ""));
				return false;
			}
		}
		return true;
	}
	const testHooks = { _encodePath: encodePath };
	const plugin = definePlugin({
		backup: {
			async test() {
				const { base, headers, cfg, error } = resolveBase();
				if (error) return error;
				headers["Depth"] = "0";
				try {
					const res = await host.http.request("PROPFIND", encodePath(base), headers, null);
					if (res.status === 207 || res.status === 200) return null;
					if (res.status === 401) return "认证失败（401），请检查账号与密码";
					if (res.status === 404) {
						if (await ensureCollection(cfg, headers)) return null;
						return "远端目录不存在且自动创建失败";
					}
					return "服务器返回 HTTP " + res.status + statusDetail(res);
				} catch (e) {
					return e.message || "请求失败";
				}
			},
			async push(args) {
				const name = args.name || "";
				const archiveId = args.archiveId || "";
				if (name === "") return {
					ok: false,
					message: "备份包名为空"
				};
				if (archiveId === "") return {
					ok: false,
					message: "备份包为空"
				};
				const { base, basePath, headers, cfg, error } = resolveBase();
				if (error) return {
					ok: false,
					message: error
				};
				headers["Content-Type"] = "application/octet-stream";
				headers["Overwrite"] = "T";
				const url = encodePath(base) + "/" + encodePath(name);
				try {
					let res = await host.http.upload("PUT", url, headers, archiveId);
					if (res.status === 409 || res.status === 404) {
						if (!await ensureCollection(cfg, headers)) return {
							ok: false,
							message: "创建远端目录失败"
						};
						res = await host.http.upload("PUT", url, headers, archiveId);
					}
					if (res.status === 200 || res.status === 201 || res.status === 204) return {
						ok: true,
						id: basePath + "/" + name
					};
					if (res.status === 401) return {
						ok: false,
						message: "认证失败（401），请检查账号与密码"
					};
					return {
						ok: false,
						message: "上传失败（HTTP " + res.status + "）" + statusDetail(res)
					};
				} catch (e) {
					return {
						ok: false,
						message: e.message || "上传请求失败"
					};
				}
			},
			async pull(id) {
				if (!id || id === "") return null;
				const { origin, headers, error } = resolveBase();
				if (error) return null;
				try {
					const res = await host.http.download("GET", origin + encodePath(id), headers);
					if (res.status !== 200) {
						host.logError("pull 失败: HTTP " + res.status + statusDetail(res));
						return null;
					}
					return res.blobId || null;
				} catch (e) {
					host.logError("pull 失败: " + (e.message || ""));
					return null;
				}
			},
			async list() {
				const { base, basePath, headers, cfg, error } = resolveBase();
				if (error) return null;
				headers["Depth"] = "1";
				try {
					let res = await host.http.request("PROPFIND", encodePath(base), headers, null);
					if (res.status === 404 || res.status === 409) {
						if (!await ensureCollection(cfg, headers)) return null;
						res = await host.http.request("PROPFIND", encodePath(base), headers, null);
					}
					if (res.status !== 207 && res.status !== 200) return null;
					return parseBackupList(res.text || "", basePath);
				} catch (e) {
					host.logError("list 失败: " + (e.message || ""));
					return null;
				}
			},
			async remove(id) {
				if (!id || id === "") return false;
				const { origin, headers, error } = resolveBase();
				if (error) return false;
				try {
					const res = await host.http.request("DELETE", origin + encodePath(id), headers, null);
					return res.status === 204 || res.status === 200 || res.status === 404;
				} catch (e) {
					host.logError("remove 失败: " + (e.message || ""));
					return false;
				}
			},
			async listFiles() {
				const { headers, error } = resolveBase();
				if (error) return null;
				headers["Depth"] = "1";
				try {
					let res = await host.http.request("PROPFIND", encodePath(filesUrl()), headers, null);
					if (res.status === 404 || res.status === 409) {
						if (!await ensureFileDirs(headers, [])) return null;
						res = await host.http.request("PROPFIND", encodePath(filesUrl()), headers, null);
					}
					if (res.status === 404) return [];
					if (res.status !== 207 && res.status !== 200) return null;
					return parseBackupList(res.text || "", filesPath()).map((it) => ({
						path: it.name,
						sha256: "",
						size: it.size
					}));
				} catch (e) {
					host.logError("listFiles 失败: " + (e.message || ""));
					return null;
				}
			},
			async pushFile(args) {
				const rel = (args.path || "").replace(/^\/+/, "");
				const blobId = args.blobId || "";
				if (rel === "") return {
					ok: false,
					message: "文件路径为空"
				};
				if (blobId === "") return {
					ok: false,
					message: "文件内容为空"
				};
				const { headers, error } = resolveBase();
				if (error) return {
					ok: false,
					message: error
				};
				const segs = rel.split("/");
				if (!await ensureFileDirs(headers, segs.slice(0, segs.length - 1))) return {
					ok: false,
					message: "创建远端目录失败"
				};
				headers["Content-Type"] = "application/octet-stream";
				headers["Overwrite"] = "T";
				const url = encodePath(filesUrl() + "/" + rel);
				try {
					const res = await host.http.upload("PUT", url, headers, blobId);
					if (res.status === 200 || res.status === 201 || res.status === 204) return { ok: true };
					if (res.status === 401) return {
						ok: false,
						message: "认证失败（401），请检查账号与密码"
					};
					return {
						ok: false,
						message: "上传失败（HTTP " + res.status + "）" + statusDetail(res)
					};
				} catch (e) {
					return {
						ok: false,
						message: e.message || "上传请求失败"
					};
				}
			},
			async pullFile(path) {
				const rel = (path || "").replace(/^\/+/, "");
				if (rel === "") return null;
				const { headers, error } = resolveBase();
				if (error) return null;
				try {
					const res = await host.http.download("GET", encodePath(filesUrl() + "/" + rel), headers);
					if (res.status !== 200) {
						host.logError("pullFile 失败: " + rel + " HTTP " + res.status);
						return null;
					}
					return res.blobId || null;
				} catch (e) {
					host.logError("pullFile 失败: " + (e.message || ""));
					return null;
				}
			},
			async removeFile(path) {
				const rel = (path || "").replace(/^\/+/, "");
				if (rel === "") return false;
				const { headers, error } = resolveBase();
				if (error) return false;
				try {
					const res = await host.http.request("DELETE", encodePath(filesUrl() + "/" + rel), headers, null);
					return res.status === 204 || res.status === 200 || res.status === 404;
				} catch (e) {
					host.logError("removeFile 失败: " + (e.message || ""));
					return false;
				}
			}
		},
		settings: { schema() {
			return [
				{
					type: "text",
					key: "url",
					label: "服务器地址",
					required: true,
					placeholder: "https://dav.jianguoyun.com/dav/",
					helpText: "WebDAV 根地址；坚果云为 https://dav.jianguoyun.com/dav/"
				},
				{
					type: "text",
					key: "username",
					label: "账号",
					required: true
				},
				{
					type: "secret",
					key: "password",
					label: "密码 / 应用密码",
					required: true,
					helpText: "坚果云请到网页端「安全选项」生成应用密码"
				},
				{
					type: "text",
					key: "remote_path",
					label: "备份目录",
					defaultValue: "/xime_backup",
					helpText: "远端目录，不存在时自动创建"
				},
				{
					type: "button",
					key: "testConnection",
					label: "测试连接"
				}
			];
		} },
		...testHooks
	});

//#endregion
return plugin;
})();