export type ModelDownloadSource = {
  id: string;
  name: string;
  endpoint: string;
};

export type HfRepository = {
  id: string;
  author: string;
  downloads: number;
  likes: number;
  lastModified: string;
};

export type HfModelFile = {
  path: string;
  size: number;
  sha256: string | null;
  kind: 'model' | 'projection';
  sharded: boolean;
};

export type HfRepositoryDetail = {
  id: string;
  revision: string;
  contextSize: number;
  files: HfModelFile[];
};

export type HfSearchPage = {
  repositories: HfRepository[];
  nextUrl: string | null;
};

function endpoint(source: ModelDownloadSource): string {
  const value = source.endpoint.trim().replace(/\/+$/, '');
  if (!value) throw new Error(`下载源 ${source.id} 没有 endpoint`);
  return value;
}

function encodeRepo(repoId: string): string {
  const parts = repoId.split('/');
  if (parts.length !== 2 || parts.some(part => part.length === 0)) {
    throw new Error(`HF 仓库标识无效: ${repoId}`);
  }
  return parts.map(encodeURIComponent).join('/');
}

function nextLink(header: string | null): string | null {
  if (!header) return null;
  for (const item of header.split(',')) {
    const match = item.match(/<([^>]+)>\s*;\s*rel="?next"?/i);
    if (match) return match[1];
  }
  return null;
}

function sourceUrl(source: ModelDownloadSource, address: string): string {
  const base = endpoint(source);
  if (address.startsWith('/')) return base + address;
  try {
    const parsed = new URL(address);
    return base + parsed.pathname + parsed.search;
  } catch (error: any) {
    throw new Error(`HF 分页地址无效: ${address}; ${error?.message ?? String(error)}`);
  }
}

async function responseError(response: Response): Promise<Error> {
  let detail = '';
  try {
    detail = (await response.text()).trim();
  } catch (error: any) {
    detail = `读取错误正文失败: ${error?.message ?? String(error)}`;
  }
  return new Error(`HF API HTTP ${response.status}${detail ? `: ${detail}` : ''}`);
}

async function getJson<T>(address: string, signal?: AbortSignal): Promise<{data: T; next: string | null}> {
  const response = await fetch(address, {
    method: 'GET',
    headers: {Accept: 'application/json'},
    signal,
  });
  if (!response.ok) throw await responseError(response);
  return {data: (await response.json()) as T, next: nextLink(response.headers.get('link'))};
}

function finiteNumber(value: unknown): number {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

export async function searchHfRepositories(
  source: ModelDownloadSource,
  query: string,
  nextUrl?: string | null,
  signal?: AbortSignal,
): Promise<HfSearchPage> {
  const address = nextUrl
    ? sourceUrl(source, nextUrl)
    : endpoint(source) +
      '/api/models?search=' +
      encodeURIComponent(query.trim()) +
      '&filter=gguf&sort=downloads&direction=-1&limit=20&full=true';
  const response = await getJson<any[]>(address, signal);
  if (!Array.isArray(response.data)) throw new Error('HF 搜索结果不是数组');
  const repositories = response.data.map((item: any) => {
    const id = String(item?.id ?? item?.modelId ?? '');
    if (!id) throw new Error('HF 搜索结果缺少仓库 id');
    return {
      id,
      author: String(item?.author ?? id.split('/')[0] ?? ''),
      downloads: finiteNumber(item?.downloads),
      likes: finiteNumber(item?.likes),
      lastModified: String(item?.lastModified ?? ''),
    };
  });
  return {repositories, nextUrl: response.next};
}

function fileKind(path: string): 'model' | 'projection' {
  return path.split('/').pop()?.toLowerCase().includes('mmproj') ? 'projection' : 'model';
}

function isSharded(path: string): boolean {
  return /-\d{5}-of-\d{5}\.gguf$/i.test(path);
}

function lfsSha256(item: any): string | null {
  const value = String(item?.lfs?.oid ?? '');
  return /^[a-f0-9]{64}$/i.test(value) ? value : null;
}

export async function loadHfRepository(
  source: ModelDownloadSource,
  repoId: string,
  signal?: AbortSignal,
): Promise<HfRepositoryDetail> {
  const base = endpoint(source);
  const encodedRepo = encodeRepo(repoId);
  const detail = await getJson<any>(
    `${base}/api/models/${encodedRepo}?expand[]=gguf&expand[]=sha`,
    signal,
  );
  const revision = String(detail.data?.sha ?? '');
  if (!revision) throw new Error(`HF 仓库没有返回可固定的 commit SHA: ${repoId}`);

  const files: HfModelFile[] = [];
  const visited = new Set<string>();
  let address: string | null =
    `${base}/api/models/${encodedRepo}/tree/${encodeURIComponent(revision)}` +
    '?recursive=true&expand=false';
  while (address) {
    const currentAddress: string = sourceUrl(source, address);
    if (visited.has(currentAddress)) throw new Error(`HF 文件分页形成循环: ${currentAddress}`);
    visited.add(currentAddress);
    const page = await getJson<any[]>(currentAddress, signal);
    if (!Array.isArray(page.data)) throw new Error(`HF 仓库文件列表不是数组: ${repoId}`);
    for (const item of page.data) {
      const path = String(item?.path ?? '');
      if (item?.type !== 'file' || !/\.gguf$/i.test(path)) continue;
      files.push({
        path,
        size: finiteNumber(item?.size ?? item?.lfs?.size),
        sha256: lfsSha256(item),
        kind: fileKind(path),
        sharded: isSharded(path),
      });
    }
    address = page.next;
  }
  files.sort((left, right) => {
    if (left.kind !== right.kind) return left.kind === 'model' ? -1 : 1;
    return left.path.localeCompare(right.path);
  });

  const contextSize = finiteNumber(
    detail.data?.gguf?.context_length ?? detail.data?.gguf?.contextLength,
  );
  return {id: repoId, revision, contextSize, files};
}
