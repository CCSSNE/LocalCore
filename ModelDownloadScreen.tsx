import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Modal,
  NativeModules,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

import {
  HfModelFile,
  HfRepository,
  HfRepositoryDetail,
  ModelDownloadSource,
  loadHfRepository,
  searchHfRepositories,
} from './huggingFace';

const {Backend} = NativeModules;

type ResourceView = {
  id: string;
  status: string;
  downloaded: number;
  total: number;
  path: string | null;
  error: string | null;
};

function errorText(error: any): string {
  return error?.message ?? String(error);
}

function fileKey(repoId: string, path: string): string {
  return `${repoId}\n${path}`;
}

function baseName(path: string): string {
  const name = path.split('/').pop() ?? path;
  return name.replace(/\.gguf$/i, '');
}

function formatCount(value: number): string {
  return Math.max(0, Math.round(value)).toLocaleString('en-US');
}

function formatSize(value: number): string {
  if (!(value > 0)) return '大小未知';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size >= 10 || unit === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`;
}

function formatDate(value: string): string {
  if (!value) return '更新时间未知';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString();
}

export default function ModelDownloadScreen() {
  const [root, setRoot] = useState<any>(null);
  const [query, setQuery] = useState('');
  const [repositories, setRepositories] = useState<HfRepository[] | null>(null);
  const [nextUrl, setNextUrl] = useState<string | null>(null);
  const [detail, setDetail] = useState<HfRepositoryDetail | null>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const [actionBusy, setActionBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pairFile, setPairFile] = useState<HfModelFile | null>(null);
  const request = useRef<AbortController | null>(null);
  const autoLoadedSource = useRef<string | null>(null);

  const refreshState = async (reportError: boolean) => {
    try {
      const value = JSON.parse(String(await Backend.getBackendState()));
      const downloads = value?.config?.modelDownloads;
      if (!downloads || !Array.isArray(downloads.sources)) {
        throw new Error('配置中没有 modelDownloads.sources');
      }
      setRoot(value);
    } catch (stateError: any) {
      if (reportError) setError(`读取下载配置失败: ${errorText(stateError)}`);
    }
  };

  useEffect(() => {
    refreshState(true);
    const timer = setInterval(() => refreshState(false), 1000);
    return () => {
      clearInterval(timer);
      request.current?.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const configuredSources = root?.config?.modelDownloads?.sources;
  const sources: ModelDownloadSource[] = Array.isArray(configuredSources)
    ? configuredSources.map((item: any) => ({
        id: String(item?.id ?? ''),
        name: String(item?.name ?? item?.id ?? ''),
        endpoint: String(item?.endpoint ?? ''),
      }))
    : [];
  const activeSourceId = String(root?.config?.modelDownloads?.activeSource ?? '');
  const activeSource = sources.find(item => item.id === activeSourceId) ?? null;

  const runRequest = async <T,>(label: string, action: (signal: AbortSignal) => Promise<T>): Promise<T> => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(label);
    setError(null);
    try {
      return await action(controller.signal);
    } catch (requestError: any) {
      if (requestError?.name === 'AbortError') throw requestError;
      setError(`${label}失败: ${errorText(requestError)}`);
      throw requestError;
    } finally {
      if (request.current === controller) {
        request.current = null;
        setLoading(null);
      }
    }
  };

  const search = async (append: boolean) => {
    if (!activeSource) {
      setError(`当前下载源不存在: ${activeSourceId || '未配置'}`);
      return;
    }
    try {
      const page = await runRequest(append ? '加载更多模型' : '搜索模型', signal =>
        searchHfRepositories(activeSource, query, append ? nextUrl : null, signal),
      );
      setRepositories(previous => {
        const combined = append && previous ? [...previous, ...page.repositories] : page.repositories;
        const unique = new Map<string, HfRepository>();
        combined.forEach(item => unique.set(item.id, item));
        return [...unique.values()];
      });
      setNextUrl(page.nextUrl);
      if (!append) setDetail(null);
    } catch (requestError: any) {
      if (requestError?.name !== 'AbortError') return;
    }
  };

  useEffect(() => {
    if (!activeSource || autoLoadedSource.current === activeSource.id) return;
    autoLoadedSource.current = activeSource.id;
    search(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSourceId]);

  const openRepository = async (repository: HfRepository) => {
    if (!activeSource) {
      setError(`当前下载源不存在: ${activeSourceId || '未配置'}`);
      return;
    }
    try {
      const value = await runRequest('读取仓库文件', signal =>
        loadHfRepository(activeSource, repository.id, signal),
      );
      setDetail(value);
      setNotice(null);
    } catch (requestError: any) {
      if (requestError?.name !== 'AbortError') return;
    }
  };

  const chooseSource = async (source: ModelDownloadSource) => {
    if (source.id === activeSourceId || actionBusy) return;
    setActionBusy(`source:${source.id}`);
    setError(null);
    try {
      await Backend.setModelDownloadSource(source.id);
      request.current?.abort();
      setRepositories(null);
      setNextUrl(null);
      setDetail(null);
      setNotice(`已切换到 ${source.name}，后续 API 和文件下载都使用该地址`);
      await refreshState(true);
    } catch (sourceError: any) {
      setError(`切换下载源失败: ${errorText(sourceError)}`);
    } finally {
      setActionBusy(null);
    }
  };

  const descriptors = Array.isArray(root?.config?.resources) ? root.config.resources : [];
  const stateItems = Array.isArray(root?.resourceStates) ? root.resourceStates : [];
  const stateById = new Map<string, ResourceView>();
  stateItems.forEach((item: any) => {
    const id = String(item?.id ?? '');
    if (!id) return;
    stateById.set(id, {
      id,
      status: String(item?.status ?? 'MISSING'),
      downloaded: Number(item?.downloaded ?? 0),
      total: Number(item?.total ?? 0),
      path: item?.path == null ? null : String(item.path),
      error: item?.error == null ? null : String(item.error),
    });
  });
  const resourceFor = (repoId: string, path: string): ResourceView | null => {
    const descriptor = descriptors.find(
      (item: any) =>
        item?.origin === 'huggingface' && item?.repo === repoId && item?.fileName === path,
    );
    return descriptor ? stateById.get(String(descriptor.id)) ?? null : null;
  };
  const registrationFor = (repoId: string, path: string): any => {
    const models = Array.isArray(root?.config?.models) ? root.config.models : [];
    return (
      models.find(
        (model: any) => model?.source?.repo === repoId && model?.source?.fileName === path,
      )?.source ?? null
    );
  };

  const downloadModel = async (file: HfModelFile) => {
    if (!detail || !activeSource || actionBusy) return;
    if (file.sharded) {
      setError(`当前资源格式不支持多分片 GGUF，不能只下载其中一片: ${file.path}`);
      return;
    }
    const key = fileKey(detail.id, file.path);
    setActionBusy(key);
    setError(null);
    setNotice(null);
    try {
      const requestBody: any = {
        sourceId: activeSource.id,
        repoId: detail.id,
        revision: detail.revision,
        fileName: file.path,
        modelName: baseName(file.path),
        size: file.size,
        contextSize: detail.contextSize,
      };
      if (file.sha256) requestBody.sha256 = file.sha256;
      await Backend.downloadHfModel(JSON.stringify(requestBody));
      setNotice(`已登记下载: ${file.path}`);
      await refreshState(true);
    } catch (downloadError: any) {
      setError(`登记模型下载失败: ${errorText(downloadError)}`);
    } finally {
      setActionBusy(null);
    }
  };

  const cancelDownload = async (resource: ResourceView) => {
    if (actionBusy) return;
    setActionBusy(`cancel:${resource.id}`);
    setError(null);
    try {
      await Backend.cancelResourceDownload(resource.id);
      setNotice(`已取消下载，断点文件保留: ${resource.id}`);
      await refreshState(true);
    } catch (cancelError: any) {
      setError(`取消下载失败: ${errorText(cancelError)}`);
    } finally {
      setActionBusy(null);
    }
  };

  const downloadProjection = async (model: any) => {
    if (!detail || !activeSource || !pairFile || actionBusy) return;
    if (pairFile.sharded) {
      setPairFile(null);
      setError(`当前资源格式不支持多分片 GGUF: ${pairFile.path}`);
      return;
    }
    const file = pairFile;
    setPairFile(null);
    setActionBusy(`projection:${file.path}`);
    setError(null);
    try {
      const requestBody: any = {
        sourceId: activeSource.id,
        repoId: detail.id,
        revision: detail.revision,
        fileName: file.path,
        size: file.size,
      };
      if (file.sha256) requestBody.sha256 = file.sha256;
      await Backend.downloadHfProjection(JSON.stringify(requestBody), String(model.id));
      setNotice(`已将 ${file.path} 配对到 ${model.name ?? model.id}`);
      await refreshState(true);
    } catch (projectionError: any) {
      setError(`登记 MMPROJ 下载失败: ${errorText(projectionError)}`);
    } finally {
      setActionBusy(null);
    }
  };

  const models = Array.isArray(root?.config?.models) ? root.config.models : [];

  const renderFile = (file: HfModelFile) => {
    if (!detail) return null;
    const resource = resourceFor(detail.id, file.path);
    const registration = registrationFor(detail.id, file.path);
    const downloading = resource?.status === 'QUEUED' || resource?.status === 'DOWNLOADING';
    const installed = !!resource?.path && resource.status === 'INSTALLED';
    const progress =
      resource && resource.total > 0
        ? `${((resource.downloaded / resource.total) * 100).toFixed(1)}% · ${formatSize(resource.downloaded)} / ${formatSize(resource.total)}`
        : null;
    const registrationError = registration?.registrationError
      ? String(registration.registrationError)
      : null;
    const modelComplete = installed && !registration?.contextPending && !registrationError;
    return (
      <View key={file.path} style={styles.card}>
        <View style={styles.titleRow}>
          <Text style={styles.fileTitle} selectable>
            {file.path}
          </Text>
          <Text style={file.kind === 'projection' ? styles.projectionTag : styles.modelTag}>
            {file.kind === 'projection' ? 'MMPROJ' : 'GGUF'}
          </Text>
        </View>
        <Text style={styles.meta}>{formatSize(file.size)}{file.sha256 ? ' · SHA-256 可校验' : ' · API 未提供 SHA-256'}</Text>
        {file.sharded ? (
          <Text style={styles.errorText}>当前资源格式不支持多分片；不会隐藏，也不会只下载一片</Text>
        ) : null}
        {progress && downloading ? <Text style={styles.progress}>{progress}</Text> : null}
        {resource?.error ? <Text style={styles.errorText}>{resource.error}</Text> : null}
        {registrationError ? <Text style={styles.errorText}>模型登记失败：{registrationError}</Text> : null}
        {installed && registration?.contextPending && !registrationError ? (
          <Text style={styles.progress}>文件已安装，正在从 GGUF 登记上下文</Text>
        ) : null}
        <View style={styles.actions}>
          {downloading && resource ? (
            <TouchableOpacity
              style={styles.button}
              disabled={!!actionBusy}
              onPress={() => cancelDownload(resource)}>
              <Text style={styles.buttonText}>取消</Text>
            </TouchableOpacity>
          ) : file.kind === 'model' ? (
            <TouchableOpacity
              style={[styles.button, (modelComplete || file.sharded) && styles.disabled]}
              disabled={!!actionBusy || modelComplete || file.sharded}
              onPress={() => downloadModel(file)}>
              <Text style={styles.buttonText}>
                {modelComplete
                  ? '已下载'
                  : registrationError
                    ? '重试登记'
                    : resource?.status === 'FAILED' || resource?.status === 'CANCELLED'
                      ? '继续下载'
                      : '下载'}
              </Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity
              style={[styles.button, file.sharded && styles.disabled]}
              disabled={!!actionBusy || file.sharded}
              onPress={() => setPairFile(file)}>
              <Text style={styles.buttonText}>{installed ? '选择配对' : '配对下载'}</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    );
  };

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.sectionTitle}>下载源</Text>
        <View style={styles.sourceRow}>
          {sources.map(source => (
            <TouchableOpacity
              key={source.id}
              style={[styles.sourceButton, source.id === activeSourceId && styles.sourceButtonActive]}
              disabled={!!actionBusy}
              onPress={() => chooseSource(source)}>
              <Text style={source.id === activeSourceId ? styles.sourceTextActive : styles.sourceText}>
                {source.name}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
        <Text style={styles.endpoint} selectable>
          {activeSource?.endpoint ?? '当前下载源无效'}
        </Text>

        <View style={styles.searchRow}>
          <TextInput
            value={query}
            onChangeText={setQuery}
            onSubmitEditing={() => search(false)}
            placeholder="搜索 GGUF 仓库；留空浏览热门模型"
            placeholderTextColor="#888888"
            returnKeyType="search"
            style={styles.input}
          />
          <TouchableOpacity
            style={[styles.searchButton, !!loading && styles.disabled]}
            disabled={!!loading}
            onPress={() => search(false)}>
            <Text style={styles.searchButtonText}>搜索</Text>
          </TouchableOpacity>
        </View>

        {error ? <Text style={styles.errorBox}>{error}</Text> : null}
        {notice ? <Text style={styles.noticeBox}>{notice}</Text> : null}
        {loading ? (
          <View style={styles.loadingRow}>
            <ActivityIndicator />
            <Text style={styles.meta}>{loading}…</Text>
          </View>
        ) : null}

        {detail ? (
          <View>
            <TouchableOpacity onPress={() => setDetail(null)} style={styles.backButton}>
              <Text style={styles.link}>‹ 返回搜索结果</Text>
            </TouchableOpacity>
            <Text style={styles.sectionTitle} selectable>{detail.id}</Text>
            <Text style={styles.meta} selectable>commit {detail.revision}</Text>
            {detail.contextSize > 0 ? (
              <Text style={styles.meta}>目录上下文 {formatCount(detail.contextSize)}</Text>
            ) : null}
            {detail.files.length === 0 && !loading ? (
              <Text style={styles.empty}>仓库没有返回 GGUF 文件</Text>
            ) : null}
            {detail.files.map(renderFile)}
          </View>
        ) : (
          <View>
            {repositories === null && !loading ? (
              <Text style={styles.empty}>搜索或直接浏览 HF 上的 GGUF 模型</Text>
            ) : null}
            {repositories?.length === 0 && !loading ? (
              <Text style={styles.empty}>没有匹配的 GGUF 仓库</Text>
            ) : null}
            {(repositories ?? []).map(repository => (
              <TouchableOpacity
                key={repository.id}
                style={styles.card}
                disabled={!!loading}
                onPress={() => openRepository(repository)}>
                <Text style={styles.repoTitle} selectable>{repository.id}</Text>
                <Text style={styles.meta}>
                  下载 {formatCount(repository.downloads)} · 喜欢 {formatCount(repository.likes)} · {formatDate(repository.lastModified)}
                </Text>
              </TouchableOpacity>
            ))}
            {nextUrl ? (
              <TouchableOpacity
                style={[styles.moreButton, !!loading && styles.disabled]}
                disabled={!!loading}
                onPress={() => search(true)}>
                <Text style={styles.link}>加载更多</Text>
              </TouchableOpacity>
            ) : null}
          </View>
        )}
      </ScrollView>

      <Modal
        visible={pairFile !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setPairFile(null)}>
        <Pressable style={styles.mask} onPress={() => setPairFile(null)}>
          <Pressable style={styles.modalCard} onPress={event => event.stopPropagation()}>
            <Text style={styles.modalTitle}>选择要配对的模型</Text>
            <Text style={styles.meta} numberOfLines={2}>{pairFile?.path}</Text>
            <ScrollView style={styles.modelList}>
              {models.length === 0 ? <Text style={styles.empty}>当前没有模型</Text> : null}
              {models.map((model: any) => (
                <TouchableOpacity
                  key={String(model.id)}
                  style={styles.modelChoice}
                  onPress={() => downloadProjection(model)}>
                  <Text style={styles.repoTitle}>{String(model.name ?? model.id)}</Text>
                  <Text style={styles.meta} selectable>{String(model.id)}</Text>
                </TouchableOpacity>
              ))}
            </ScrollView>
            <TouchableOpacity style={styles.closeButton} onPress={() => setPairFile(null)}>
              <Text style={styles.buttonText}>取消</Text>
            </TouchableOpacity>
          </Pressable>
        </Pressable>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#ffffff'},
  content: {padding: 16, paddingBottom: 32},
  sectionTitle: {fontSize: 16, fontWeight: '700', color: '#111111', marginBottom: 8},
  sourceRow: {flexDirection: 'row', flexWrap: 'wrap', marginBottom: 4},
  sourceButton: {
    borderWidth: 1,
    borderColor: '#cccccc',
    borderRadius: 8,
    paddingHorizontal: 12,
    minHeight: 38,
    justifyContent: 'center',
    marginRight: 8,
    marginBottom: 8,
  },
  sourceButtonActive: {borderColor: '#2563eb', backgroundColor: '#e8eefc'},
  sourceText: {fontSize: 13, color: '#333333'},
  sourceTextActive: {fontSize: 13, color: '#1a3faa', fontWeight: '700'},
  endpoint: {fontSize: 12, color: '#666666', marginBottom: 12},
  searchRow: {flexDirection: 'row', alignItems: 'center', marginBottom: 12},
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 0,
    minHeight: 44,
    color: '#111111',
    textAlignVertical: 'center',
    includeFontPadding: false,
  },
  searchButton: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 16,
    marginLeft: 8,
    borderRadius: 8,
    backgroundColor: '#2563eb',
  },
  searchButtonText: {color: '#ffffff', fontWeight: '700'},
  card: {
    padding: 12,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 10,
    backgroundColor: '#f7f7f7',
  },
  titleRow: {flexDirection: 'row', alignItems: 'flex-start', marginBottom: 6},
  repoTitle: {fontSize: 15, color: '#111111', fontWeight: '600', marginBottom: 5},
  fileTitle: {flex: 1, fontSize: 14, color: '#111111', fontWeight: '600', marginRight: 8},
  modelTag: {fontSize: 11, color: '#1a3faa', fontWeight: '700'},
  projectionTag: {fontSize: 11, color: '#6a3daa', fontWeight: '700'},
  meta: {fontSize: 12, color: '#666666', lineHeight: 18},
  progress: {fontSize: 12, color: '#b26a00', marginTop: 6},
  errorText: {fontSize: 12, color: '#b00020', marginTop: 6, lineHeight: 18},
  actions: {flexDirection: 'row', marginTop: 10},
  button: {
    borderWidth: 1,
    borderColor: '#cccccc',
    borderRadius: 8,
    paddingHorizontal: 14,
    minHeight: 38,
    justifyContent: 'center',
    backgroundColor: '#ffffff',
  },
  buttonText: {color: '#111111'},
  disabled: {opacity: 0.45},
  backButton: {alignSelf: 'flex-start', paddingVertical: 8, marginBottom: 6},
  link: {fontSize: 14, color: '#1a3faa', fontWeight: '600'},
  moreButton: {alignItems: 'center', paddingVertical: 12},
  empty: {fontSize: 13, color: '#666666', paddingVertical: 20, textAlign: 'center'},
  loadingRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingVertical: 12, gap: 8},
  errorBox: {fontSize: 12, color: '#b00020', backgroundColor: '#fdecea', borderRadius: 8, padding: 10, marginBottom: 10},
  noticeBox: {fontSize: 12, color: '#1b5e20', backgroundColor: '#edf7ed', borderRadius: 8, padding: 10, marginBottom: 10},
  mask: {flex: 1, backgroundColor: 'rgba(0,0,0,0.3)', alignItems: 'center', justifyContent: 'center'},
  modalCard: {width: '86%', maxHeight: '72%', backgroundColor: '#ffffff', borderRadius: 12, padding: 16},
  modalTitle: {fontSize: 16, fontWeight: '700', color: '#111111', marginBottom: 6},
  modelList: {marginVertical: 12},
  modelChoice: {paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#eeeeee'},
  closeButton: {alignSelf: 'flex-end', paddingHorizontal: 14, paddingVertical: 8},
});
