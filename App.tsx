import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  Modal,
  NativeEventEmitter,
  NativeModules,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import ImageView from 'react-native-image-viewing';
import AsyncStorage from '@react-native-async-storage/async-storage';

const {Backend} = NativeModules;
const chatEvents = new NativeEventEmitter(NativeModules.Backend);
const CHAT_KEY = 'localcore.chat.v1';
const SETTINGS_KEY = 'localcore.settings.v1';
const DEFAULT_BUDGET_PX = 100000;

type RouteKey = 'chat' | 'core' | 'model' | 'backend' | 'log';

// 侧边栏顺序按用户要求：后端在日志上面，日志沉底。
// 主屏叫测试：无上下文单轮，用完即走，不做复杂功能。
const ROUTES: Array<{key: RouteKey; title: string}> = [
  {key: 'chat', title: '测试'},
  {key: 'core', title: '核心'},
  {key: 'model', title: '模型'},
  {key: 'backend', title: '后端'},
  {key: 'log', title: '日志'},
];

const TITLES: Record<RouteKey, string> = {
  chat: '测试',
  core: '核心管理',
  model: '模型管理',
  backend: '后端服务',
  log: '日志',
};

type LogLine = {kind: 'info' | 'ok' | 'fail'; text: string};
type ChatMsg = {role: 'user' | 'ai' | 'error'; text: string; imageUris?: string[]; imageUri?: string | null; live?: boolean; stats?: TurnStats};
type TurnStats = {inT: number; out: number; ttft: number; llm: number};

function msgImageUris(m: ChatMsg): string[] {
  if (Array.isArray(m.imageUris)) return m.imageUris.filter(u => typeof u === 'string' && u.length > 0);
  if (typeof m.imageUri === 'string' && m.imageUri) return [m.imageUri];
  return [];
}

// 单轮统计算式照搬 legadoC AiUsageFormat：千分位 + t，时长 ms/s/m，速度 t/s，单耗 ms/t。
function fmtCount(n: number): string {
  return Math.max(0, Math.round(n)).toLocaleString('en-US') + 't';
}

function fmtDur(ms: number): string {
  if (!(ms >= 0)) return '--';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m${Math.round(s % 60)}s`;
  return `${Math.floor(m / 60)}h${m % 60}m`;
}

function fmtSpeed(tok: number, ms: number): string {
  if (!(ms > 0)) return '--';
  const tps = (tok * 1000) / ms;
  if (tps >= 10) return `${Math.round(tps).toLocaleString('en-US')}t/s`;
  return `${tps.toFixed(1)}t/s`;
}

function fmtMsPerTok(ms: number, tok: number): string {
  if (!(ms > 0) || !(tok > 0)) return '--';
  return `${(ms / tok).toFixed(1)}ms/t`;
}

function fmtClock(d: Date): string {
  const p = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

function StatsStrip({stats}: {stats: TurnStats}) {
  const [open, setOpen] = useState(false);
  const head = `total-${fmtCount(stats.inT + stats.out)} ${fmtSpeed(stats.out, stats.llm)} ${fmtDur(stats.ttft)}`;
  const body =
    `in-${fmtCount(stats.inT)} ${fmtSpeed(stats.inT, stats.ttft)} ${fmtMsPerTok(stats.ttft, stats.inT)} ${fmtDur(stats.ttft)}` +
    `\nout-${fmtCount(stats.out)} ${fmtSpeed(stats.out, stats.llm)} ${fmtMsPerTok(stats.llm, stats.out)} ${fmtDur(stats.llm)}` +
    `\ntotal-${fmtCount(stats.inT + stats.out)}`;
  return (
    <TouchableOpacity onPress={() => setOpen(v => !v)} style={styles.statsBox}>
      <Text style={styles.statsText}>
        {open ? body : head + ' ▼'}
      </Text>
    </TouchableOpacity>
  );
}
type ModelEntry = {id: string; name: string; paired: boolean};

function pickAnd(pickLabel: string, after: (uri: string) => Promise<any>) {
  return async () => {
    try {
      const uri: string = await Backend.pickFile();
      return await after(uri);
    } catch (error: any) {
      throw new Error(pickLabel + ' 未完成: ' + (error?.message ?? String(error)));
    }
  };
}

async function firstModelId(): Promise<string> {
  const state = JSON.parse(await Backend.getBackendState());
  const models = state.config.models as {id: string}[];
  if (!models || models.length === 0) throw new Error('配置中没有模型');
  return models[models.length - 1].id;
}

function parseModels(root: any): ModelEntry[] {
  const arr = root?.config?.models;
  if (!Array.isArray(arr)) throw new Error('状态中没有 config.models');
  return arr.map((m: any) => ({
    id: String(m?.id ?? ''),
    name: String(m?.name ?? m?.id ?? '未命名'),
    paired:
      !!(m?.mmproj && String(m.mmproj).length > 0) ||
      (Array.isArray(m?.capabilities) && m.capabilities.includes('vision')),
  }));
}

function ImageIcon() {
  return (
    <View style={styles.imgIcon}>
      <View style={styles.imgSun} />
      <View style={styles.imgPeakLeft} />
      <View style={styles.imgPeakRight} />
    </View>
  );
}

function EyeIcon() {
  return (
    <View style={styles.eyeOuter}>
      <View style={styles.eyePupil} />
    </View>
  );
}

const NUM_FIELDS: Array<{key: string; label: string; group: 'load' | 'inference'; int: boolean}> = [
  {key: 'contextSize', label: '上下文长度', group: 'load', int: true},
  {key: 'batchSize', label: '批大小', group: 'load', int: true},
  {key: 'threads', label: '线程数', group: 'load', int: true},
  {key: 'gpuLayers', label: 'GPU 层数（-1 自动）', group: 'load', int: true},
  {key: 'maxTokens', label: '最大生成数', group: 'inference', int: true},
  {key: 'temperature', label: '温度', group: 'inference', int: false},
  {key: 'topP', label: 'Top-P', group: 'inference', int: false},
  {key: 'topK', label: 'Top-K', group: 'inference', int: true},
  {key: 'seed', label: '随机种子（-1 随机）', group: 'inference', int: true},
];

export default function App() {
  const [route, setRoute] = useState<RouteKey>('chat');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [chatMenuOpen, setChatMenuOpen] = useState(false);
  const [log, setLog] = useState<LogLine[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [draft, setDraft] = useState('');
  const [pendingImages, setPendingImages] = useState<string[]>([]);
  const [viewerData, setViewerData] = useState<{uris: string[]; index: number} | null>(null);
  const [stageMsg, setStageMsg] = useState('');
  const [progressMsg, setProgressMsg] = useState('');
  const prefillComplete = useRef(true);
  const progressStarted = useRef(false);
  const progressT0 = useRef(0);
  const [chatGate, setChatGate] = useState<{loading: boolean; models: number; loaded: boolean}>({
    loading: true,
    models: 0,
    loaded: false,
  });
  const [budgetPx, setBudgetPx] = useState(DEFAULT_BUDGET_PX);
  const [budgetWan, setBudgetWan] = useState('10');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [tplOpen, setTplOpen] = useState(false);
  const [tplModel, setTplModel] = useState<ModelEntry | null>(null);
  const [tplText, setTplText] = useState('');
  const [tplLoading, setTplLoading] = useState(false);
  const [stForm, setStForm] = useState<Record<string, string>>({});
  const [coreRt, setCoreRt] = useState<{cuPhase: string | null; cuError: string | null}>({
    cuPhase: null,
    cuError: null,
  });
  const [modelList, setModelList] = useState<ModelEntry[] | null>(null);
  const [modelError, setModelError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [loadedId, setLoadedId] = useState<string | null>(null);
  const [runtimePhase, setRuntimePhase] = useState<string | null>(null);
  const [runtimeError, setRuntimeError] = useState<string | null>(null);
  const [coreInfo, setCoreInfo] = useState<{id: string; version: string} | null>(null);
  const [coreError, setCoreError] = useState<string | null>(null);
  const [coreLoading, setCoreLoading] = useState(false);
  const [updateStatus, setUpdateStatus] = useState<string | null>(null);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollActive = useRef(false);
  const [backendInfo, setBackendInfo] = useState<{running: boolean; address: string | null; error: string | null} | null>(null);
  const [backendError, setBackendError] = useState<string | null>(null);
  const [backendLoading, setBackendLoading] = useState(false);
  const chatScroll = useRef<ScrollView | null>(null);
  const logScroll = useRef<ScrollView | null>(null);
  const chatScrollSig = useRef<string>('');
  // 是否跟随输出追到最下面：用户主动上滑即停，滑回底部再恢复。
  const followOutput = useRef(true);
  // 从测试页小横杠跳去模型页后，加载成功自动跳回测试页。只用于这一种情况。
  const chatReturnAfterLoad = useRef(false);

  const push = (kind: LogLine['kind'], text: string) =>
    setLog(prev => [...prev, {kind, text: `[${fmtClock(new Date())}] ${text}`}]);

  const go = (next: RouteKey) => {
    setRoute(next);
    setDrawerOpen(false);
    setChatMenuOpen(false);
  };

  const run = (label: string, action: () => Promise<any>, afterOk?: () => void) => {
    if (busy) {
      push('fail', 'FAIL ' + label + ' => 已有任务进行中: ' + busy + '，请稍候再试');
      return;
    }
    setBusy(label);
    push('info', '>> ' + label);
    action()
      .then((value: any) => {
        push('ok', 'OK ' + label + (value ? ' => ' + String(value) : ''));
        afterOk?.();
      })
      .catch((error: Error) => push('fail', 'FAIL ' + label + ' => ' + error.message))
      .finally(() => setBusy(null));
  };

  const refreshState = () => run('查询状态', () => Backend.getBackendState());

  const fetchChatState = async () => {
    setChatGate(g => ({...g, loading: true}));
    try {
      const root = JSON.parse(String(await Backend.getBackendState()));
      const models = root?.config?.models;
      const count = Array.isArray(models) ? models.length : 0;
      const phase = root?.runtime?.phase;
      const mid = root?.runtime?.modelId;
      setChatGate({
        loading: false,
        models: count,
        loaded: count > 0 && !!mid && (phase === 'model_ready' || phase === 'generating'),
      });
    } catch (e: any) {
      setChatGate({loading: false, models: 0, loaded: false});
      push('fail', 'FAIL 读取聊天状态 => ' + (e?.message ?? String(e)));
    }
  };

  const applyBudgetPx = (px: number) => {
    setBudgetPx(px);
    Backend.setImageBudget(px).catch((e: any) =>
      push('fail', 'FAIL 设置图片预算 => ' + (e?.message ?? String(e))),
    );
  };

  const loadSettings = () => {
    AsyncStorage.getItem(SETTINGS_KEY)
      .then(raw => {
        if (raw == null) {
          applyBudgetPx(DEFAULT_BUDGET_PX);
          return;
        }
        const px = Number(JSON.parse(raw)?.imageBudgetPx);
        if (px > 0) {
          const rounded = Math.round(px);
          setBudgetWan(String(Math.round(rounded / 10000)));
          applyBudgetPx(rounded);
        } else {
          applyBudgetPx(DEFAULT_BUDGET_PX);
        }
      })
      .catch((e: any) => push('fail', 'FAIL 读取图片设置 => ' + (e?.message ?? String(e))));
  };

  const saveBudget = () => {
    const wan = Number(budgetWan);
    if (!Number.isFinite(wan) || wan <= 0) {
      push('fail', 'FAIL 保存图片设置 => 请输入大于0的数字（万像素）');
      return;
    }
    const px = Math.round(wan * 10000);
    setSettingsOpen(false);
    applyBudgetPx(px);
    AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify({imageBudgetPx: px})).catch((e: any) =>
      push('fail', 'FAIL 保存图片设置 => ' + (e?.message ?? String(e))),
    );
  };

  const fetchModels = async () => {
    setListLoading(true);
    try {
      const value = String(await Backend.getBackendState());
      const root = JSON.parse(value);
      setModelList(parseModels(root));
      setLoadedId(root?.runtime?.modelId ?? null);
      setRuntimePhase(root?.runtime?.phase ?? null);
      const runtimeErr = root?.runtime?.error;
      setRuntimeError(runtimeErr == null ? null : String(runtimeErr));
      setModelError(null);
    } catch (error: any) {
      const message = error?.message ?? String(error);
      setModelError(message);
      push('fail', 'FAIL 刷新模型列表 => ' + message);
    } finally {
      setListLoading(false);
    }
  };

  const fetchCore = async () => {
    setCoreLoading(true);
    try {
      const value = String(await Backend.getBackendState());
      const root = JSON.parse(value);
      const resources = root?.config?.resources;
      let found: {id: string; version: string} | null = null;
      if (Array.isArray(resources)) {
        const core = resources.find((r: any) => r?.type === 'core');
        if (core) {
          found = {
            id: String(core.id ?? 'localcore.core'),
            version: String(core.version ?? root?.runtime?.coreVersion ?? '未知'),
          };
        }
      }
      setCoreInfo(found);
      const cu = root?.coreUpdate ?? {};
      const cuErr = cu.error;
      setCoreRt({
        cuPhase: cu.phase == null ? null : String(cu.phase),
        cuError: cuErr == null ? null : String(cuErr),
      });
      setCoreError(null);
    } catch (error: any) {
      const message = error?.message ?? String(error);
      setCoreError(message);
      push('fail', 'FAIL 刷新核心 => ' + message);
    } finally {
      setCoreLoading(false);
    }
  };

  const stopPoll = () => {
    pollActive.current = false;
    if (pollTimer.current) {
      clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
  };

  const pollUpdate = async () => {
    stopPoll();
    pollActive.current = true;
    const step = async () => {
      if (!pollActive.current) return;
      try {
        const root = JSON.parse(String(await Backend.getBackendState()));
        const cu = root?.coreUpdate ?? {};
        const phase = String(cu.phase ?? '');
        if (phase === 'checking') {
          setUpdateStatus('正在检查更新…');
        } else if (phase === 'downloading') {
          setUpdateStatus(`正在下载核心 ${cu.version ?? ''}…`);
        } else if (phase === 'failed') {
          setUpdateStatus(`更新失败：${cu.error ?? '未知错误'}`);
          stopPoll();
          return;
        } else if (phase === 'disabled') {
          setUpdateStatus('更新已禁用');
          stopPoll();
          return;
        } else {
          setUpdateStatus(cu.version ? `已是最新 ${cu.version}` : '就绪');
          fetchCore();
          stopPoll();
          return;
        }
      } catch (e: any) {
        setUpdateStatus(`状态读取失败：${e?.message ?? String(e)}`);
        stopPoll();
        return;
      }
      pollTimer.current = setTimeout(step, 1000);
    };
    step();
  };

  const fetchBackend = async () => {
    setBackendLoading(true);
    try {
      const value = String(await Backend.getBackendState());
      const root = JSON.parse(value);
      const b = root?.backend ?? {};
      setBackendInfo({
        running: !!b.running,
        address: b.address == null ? null : String(b.address),
        error: b.error == null ? null : String(b.error),
      });
      setBackendError(null);
    } catch (error: any) {
      const message = error?.message ?? String(error);
      setBackendError(message);
      push('fail', 'FAIL 刷新后端状态 => ' + message);
    } finally {
      setBackendLoading(false);
    }
  };

  useEffect(() => {
    AsyncStorage.getItem(CHAT_KEY)
      .then(raw => {
        if (raw == null) return;
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) throw new Error('聊天记录格式无效');
        const clean: ChatMsg[] = [];
        for (const item of parsed) {
          if (item == null || (item.role !== 'user' && item.role !== 'ai' && item.role !== 'error')) continue;
          const msg: ChatMsg = {role: item.role, text: String(item.text ?? '')};
          if (Array.isArray((item as any).imageUris)) {
            const uris = ((item as any).imageUris as any[]).filter(u => typeof u === 'string' && u);
            if (uris.length > 0) msg.imageUris = uris;
          } else if (typeof (item as any).imageUri === 'string' && (item as any).imageUri) {
            msg.imageUris = [String((item as any).imageUri)];
          }
          if (item.role === 'ai' && item.stats != null && typeof item.stats === 'object') {
            msg.stats = {
              inT: Number(item.stats.inT ?? 0),
              out: Number(item.stats.out ?? 0),
              ttft: Number(item.stats.ttft ?? 0),
              llm: Number(item.stats.llm ?? 0),
            };
          }
          if (msg.role === 'ai' && msg.text === '' && !msg.stats) continue;
          clean.push(msg);
        }
        setMessages(clean);
      })
      .catch((e: any) => push('fail', 'FAIL 恢复聊天记录 => ' + (e?.message ?? String(e))));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (messages.some(m => m.live)) return;
    AsyncStorage.setItem(CHAT_KEY, JSON.stringify(messages)).catch((e: any) =>
      push('fail', 'FAIL 保存聊天记录 => ' + (e?.message ?? String(e))),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages]);

  useEffect(() => {
    const sub = chatEvents.addListener('LocalCoreChatToken', (token: any) => {
      const piece = String(token ?? '');
      if (!piece) return;
      setProgressMsg('');
      prefillComplete.current = true;
      push('info', piece);
      setMessages(prev => {
        if (prev.length === 0) return prev;
        const last = prev[prev.length - 1];
        if (last.role !== 'ai' || !last.live) return prev;
        const next = [...prev];
        next[next.length - 1] = {...last, text: last.text + piece};
        return next;
      });
    });
    const stageSub = chatEvents.addListener('LocalCoreChatStage', (text: any) => {
      const s = String(text ?? '');
      if (!s) return;
      if (!progressStarted.current && !prefillComplete.current) setStageMsg(s);
      push('info', '·· ' + s);
    });
    const progSub = chatEvents.addListener('LocalCoreChatProgress', (event: any) => {
      if (prefillComplete.current) return;
      const phase = String(event?.phase ?? '');
      if (!phase) return;
      const done = Number(event?.done ?? 0);
      const total = Number(event?.total ?? 0);
      const elapsedMs = Number(event?.elapsedMs ?? 0);
      const labels: Record<string, string> = {
        context_prepare: '正在准备文字',
        image_prepare: '正在读取和预处理图片',
        context: '正在解码文字',
        image: '正在解码图片（视觉编码）',
        image_context: '正在解码图片（上下文计算）',
      };
      const pct = total > 0 ? ` ${((done / total) * 100).toFixed(2)}%` : '';
      const speedTxt = done > 0 && elapsedMs > 0 ? `${(done / (elapsedMs / 1000)).toFixed(2)}t/s` : '--';
      const mspTxt = done > 0 && elapsedMs > 0 ? `${(elapsedMs / done).toFixed(2)}ms/t` : '--';
      const secTxt = progressT0.current > 0 ? `${((Date.now() - progressT0.current) / 1000).toFixed(1)}s` : '--';
      const msg = `${labels[phase] ?? phase} ${done}/${total}${pct} ${speedTxt} ${mspTxt} ${secTxt}`;
      progressStarted.current = true;
      setProgressMsg(msg);
      push('info', '·· ' + msg);
    });
    return () => {
      sub.remove();
      stageSub.remove();
      progSub.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    loadSettings();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (route === 'chat') {
      chatReturnAfterLoad.current = false;
      fetchChatState();
    }
    if (route === 'model') {
      fetchModels();
    }
    if (route === 'core') {
      fetchCore();
    }
    if (route === 'backend') {
      fetchBackend();
    }
    return () => {
      stopPoll();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route]);

  const importModel = () =>
    run('导入模型', pickAnd('导入模型', uri => Backend.importModel(uri)), () => {
      fetchModels();
    });

  const importCore = () =>
    run('导入核心', pickAnd('导入核心', uri => Backend.importCore(uri)), () => {
      fetchCore();
    });

  const updateCore = () =>
    run('从仓库 Release 下载/更新核心', () => Backend.checkCoreUpdate(), () => {
      fetchCore();
      pollUpdate();
    });

  const startBackend = () =>
    run('启动后端服务', () => Backend.startService(), () => {
      fetchBackend();
    });

  const stopBackend = () =>
    run('停止后端服务', () => Backend.stopService(), () => {
      fetchBackend();
    });

  const isStoredFileUri = (uri: string) => uri.startsWith('file:');

  const deleteStoredUri = async (uri: string) => {
    if (!isStoredFileUri(uri)) return;
    await Backend.deleteStoredImage(uri);
  };

  const removePendingImage = (uri: string) => {
    setPendingImages(prev => prev.filter(u => u !== uri));
    deleteStoredUri(uri).catch((e: any) =>
      push('fail', 'FAIL 删除待发送图片 => ' + (e?.message ?? String(e))),
    );
  };

  const clearChat = () => {
    const uris: string[] = [];
    for (const m of messages) {
      for (const u of msgImageUris(m)) uris.push(u);
    }
    for (const u of pendingImages) uris.push(u);
    setMessages([]);
    setPendingImages([]);
    setChatMenuOpen(false);
    (async () => {
      for (const uri of uris) {
        try {
          await deleteStoredUri(uri);
        } catch (e: any) {
          push('fail', 'FAIL 删除存入图片 => ' + (e?.message ?? String(e)));
        }
      }
    })();
  };

  const pickImage = () =>
    run(
      '选择图片',
      pickAnd('选择图片', async uri => {
        const stored: string = String(await Backend.prepareChatImage(uri, budgetPx));
        setPendingImages(prev => [...prev, stored]);
        return stored;
      }),
    );

  const startChatTurn = (prompt: string, images: string[]) => {
    if ((!prompt && images.length === 0) || busy) return;
    followOutput.current = true;
    chatScrollSig.current = '';
    setMessages(prev => [
      ...prev,
      ...(images.length > 0
        ? [{role: 'user' as const, text: prompt, imageUris: images}]
        : [{role: 'user' as const, text: prompt}]),
    ]);
    setMessages(prev => [...prev, {role: 'ai', text: '', live: true}]);
    setStageMsg('');
    setProgressMsg('');
    prefillComplete.current = false;
    progressStarted.current = false;
    progressT0.current = Date.now();
    const label = '聊天推理';
    setBusy(label);
    push('info', '>> ' + label + '：' + prompt + (images.length > 0 ? ` [${images.length}张图片]` : ''));
    (async () => {
      const id = await firstModelId();
      const raw = String(await Backend.chatStreamMulti(id, prompt, images.length > 0 ? images : null, budgetPx));
      return JSON.parse(raw);
    })()
      .then((result: any) => {
        const text = String(result?.text ?? '');
        const stats: TurnStats = {
          inT: Number(result?.promptTokens ?? 0),
          out: Number(result?.completionTokens ?? 0),
          ttft: Number(result?.ttftMs ?? 0),
          llm: Number(result?.llmMs ?? 0),
        };
        setMessages(prev => {
          if (prev.length === 0) return [...prev, {role: 'ai', text, stats}];
          const last = prev[prev.length - 1];
          if (last.role !== 'ai' || !last.live) return [...prev, {role: 'ai', text, stats}];
          const next = [...prev];
          next[next.length - 1] = {role: 'ai', text, stats};
          return next;
        });
        setStageMsg('');
        setProgressMsg('');
        prefillComplete.current = true;
        push('ok', 'OK ' + label);
      })
      .catch((error: Error) => {
        setMessages(prev => {
          if (prev.length === 0) return [...prev, {role: 'error', text: error.message}];
          const last = prev[prev.length - 1];
          if (last.role !== 'ai' || !last.live) return [...prev, {role: 'error', text: error.message}];
          const next = [...prev];
          next[next.length - 1] = {role: 'error', text: error.message};
          return next;
        });
        setStageMsg('');
        setProgressMsg('');
        prefillComplete.current = true;
        push('fail', 'FAIL ' + label + ' => ' + error.message);
      })
      .finally(() => setBusy(null));
  };

  const sendChat = () => {
    const prompt = draft.trim();
    if ((!prompt && pendingImages.length === 0) || busy) return;
    const images = [...pendingImages];
    setDraft('');
    setPendingImages([]);
    startChatTurn(prompt, images);
  };

  const resendChat = (index: number) => {
    const m = messages[index];
    if (!m || m.role !== 'user' || busy) return;
    const prompt = m.text ?? '';
    const images = msgImageUris(m);
    if (!prompt && images.length === 0) return;
    startChatTurn(prompt, images);
  };

  const stopChat = () => {
    if (busy !== '聊天推理') return;
    push('info', '>> 停止推理');
    Backend.stopChat()
      .then(() => push('info', '·· 已发送停止信号，等待核心收尾'))
      .catch((error: any) => push('fail', 'FAIL 停止推理 => ' + (error?.message ?? String(error))));
  };

  const logText = () => log.map(line => line.text).join('\n');

  const copyLog = () => {
    if (log.length === 0) {
      push('fail', 'FAIL 复制日志 => 日志为空');
      return;
    }
    run('复制日志', () => Backend.copyText(logText()));
  };

  const exportLog = () => {
    if (log.length === 0) {
      push('fail', 'FAIL 导出日志 => 日志为空');
      return;
    }
    const fileName = 'localcore-log-' + Date.now() + '.log';
    run('导出日志', () => Backend.exportLog(fileName, logText()));
  };

  const confirmSaveImage = (uri: string) => {
    if (!uri) return;
    Alert.alert('保存图片', '保存到相册 Pictures/LocalCore？', [
      {text: '取消', style: 'cancel'},
      {text: '保存', onPress: () => run('保存图片', () => Backend.saveImage(uri))},
    ]);
  };

  const openModelSettings = (model: ModelEntry) => {
    setTplModel(model);
    setTplText('');
    setStForm({});
    setTplLoading(true);
    setTplOpen(true);
    Promise.all([Backend.getModelSettings(model.id), Backend.getModelTemplate(model.id)])
      .then(([settings, text]: any[]) => {
        const root = JSON.parse(String(settings ?? '{}'));
        const form: Record<string, string> = {};
        for (const field of NUM_FIELDS) {
          const value = root?.[field.group]?.[field.key];
          form[field.key] = value === undefined || value === null ? '' : String(value);
        }
        const stop = root?.inference?.stop;
        form.stop = Array.isArray(stop) ? stop.join(',') : '';
        setStForm(form);
        setTplText(String(text ?? ''));
        setTplLoading(false);
      })
      .catch((e: any) => {
        setTplLoading(false);
        push('fail', 'FAIL 读取模型设置 => ' + (e?.message ?? String(e)));
      });
  };

  const saveModelSettings = () => {
    if (!tplModel) return;
    const id = tplModel.id;
    const load: Record<string, number> = {};
    const inference: Record<string, any> = {};
    for (const field of NUM_FIELDS) {
      const raw = (stForm[field.key] ?? '').trim();
      const value = Number(raw);
      if (raw === '' || !Number.isFinite(value)) {
        push('fail', `FAIL 保存设置 => ${field.label}必须是数字`);
        return;
      }
      if (field.int && !Number.isInteger(value)) {
        push('fail', `FAIL 保存设置 => ${field.label}必须是整数`);
        return;
      }
      if (field.group === 'load') load[field.key] = value;
      else inference[field.key] = value;
    }
    inference.stop = stForm.stop
      .split(',')
      .map(s => s.trim())
      .filter(s => s.length > 0);
    const text = tplText;
    run(
      '保存设置',
      async () => {
        await Backend.setModelSettings(id, JSON.stringify(load), JSON.stringify(inference));
        await Backend.setModelTemplate(id, text);
      },
      () => {
        setTplOpen(false);
        fetchModels();
      },
    );
  };

  const confirmDelete = (model: ModelEntry) => {
    Alert.alert(
      '删除模型',
      model.name + ' 及已配对的投影将一起删除，是否继续？',
      [
        {text: '取消', style: 'cancel'},
        {
          text: '删除',
          style: 'destructive',
          onPress: () =>
            run('删除模型', () => Backend.deleteModel(model.id), () => {
              fetchModels();
            }),
        },
      ],
    );
  };

  const renderChat = () => {
    const lastMsg = messages.length > 0 ? messages[messages.length - 1] : null;
    const typing =
      busy === '聊天推理' &&
      lastMsg !== null &&
      lastMsg.role === 'ai' &&
      !!lastMsg.live &&
      lastMsg.text === '';
    const gateHint = chatGate.loading
      ? '正在读取状态…'
      : chatGate.models === 0
        ? '暂无模型，先去模型屏导入'
        : chatGate.loaded
          ? null
          : '点击此处或者模型界面加载模型';
    const gateCanGoModel = !chatGate.loading && chatGate.models > 0 && !chatGate.loaded;
    const inputLocked = !!busy || gateHint !== null;
    const generating = busy === '聊天推理';
    const canSend = draft.trim() !== '' || pendingImages.length > 0;
    return (
    <View style={styles.screen}>
      <ScrollView
        ref={chatScroll}
        style={styles.chatList}
        contentContainerStyle={styles.chatListContent}
        scrollEventThrottle={16}
        onScroll={e => {
          const {contentOffset, contentSize, layoutMeasurement} = e.nativeEvent;
          const distance = contentSize.height - (contentOffset.y + layoutMeasurement.height);
          followOutput.current = distance < 24;
        }}
        onContentSizeChange={() => {
          if (!followOutput.current) return;
          const last = messages[messages.length - 1];
          const sig = `${messages.length}:${last?.role ?? ''}:${last?.text.length ?? 0}:${typing ? 1 : 0}`;
          if (sig === chatScrollSig.current) return;
          chatScrollSig.current = sig;
          chatScroll.current?.scrollToEnd({animated: true});
        }}>
        {messages.map((m, i) => {
          const uris = msgImageUris(m);
          if (m.role === 'user') {
            return (
            <View key={i} style={styles.userRow}>
              <TouchableOpacity
                style={[styles.retryBtn, (!!busy || gateHint !== null) && styles.retryBtnDisabled]}
                onPress={() => resendChat(i)}
                disabled={!!busy || gateHint !== null}
                hitSlop={8}>
                <Text style={styles.retryText}>↻</Text>
              </TouchableOpacity>
              <View style={styles.userContent}>
                {m.text !== '' ? (
                  <View style={[styles.bubble, styles.bubbleUser, styles.bubbleUserInRow, uris.length === 0 && styles.bubbleUserLast]}>
                    <Text style={styles.bubbleUserText} selectable>
                      {m.text}
                    </Text>
                  </View>
                ) : null}
                {uris.length > 0 ? (
                  <View style={[styles.sentStrip, styles.sentStripInRow]}>
                    {uris.map((uri, idx) => (
                      <TouchableOpacity key={uri + '#' + idx} onPress={() => setViewerData({uris, index: idx})}>
                        <Image source={{uri}} style={styles.thumbSmall} resizeMode="cover" />
                      </TouchableOpacity>
                    ))}
                  </View>
                ) : null}
              </View>
            </View>
            );
          }
          return (
          <React.Fragment key={i}>
            {m.text !== '' ? (
              <View
                style={[
                  styles.bubble,
                  m.role === 'error' ? styles.bubbleError : styles.bubbleAi,
                ]}>
                <Text style={styles.bubbleAiText} selectable>
                  {m.text}
                </Text>
              </View>
            ) : null}
            {uris.length > 0 ? (
              <View style={styles.sentStrip}>
                {uris.map((uri, idx) => (
                  <TouchableOpacity key={uri + '#' + idx} onPress={() => setViewerData({uris, index: idx})}>
                    <Image source={{uri}} style={styles.thumbSmall} resizeMode="cover" />
                  </TouchableOpacity>
                ))}
              </View>
            ) : null}
            {m.role === 'ai' && !m.live && m.stats ? <StatsStrip stats={m.stats} /> : null}
          </React.Fragment>
          );
        })}
        {typing ? (
          <View style={[styles.bubble, styles.bubbleAi]}>
            <Text style={styles.bubbleAiText}>
              {progressMsg || (stageMsg && stageMsg !== '核心推理开始' ? stageMsg : '正在准备输入…')}
            </Text>
          </View>
        ) : null}
      </ScrollView>
      {pendingImages.length > 0 ? (
        <View style={styles.pendingStrip}>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.pendingStripContent}>
            {pendingImages.map((uri, idx) => (
              <View key={uri + '#' + idx} style={styles.pendingThumbWrap}>
                <TouchableOpacity onPress={() => setViewerData({uris: pendingImages, index: idx})}>
                  <Image source={{uri}} style={styles.pendingThumb} resizeMode="cover" />
                </TouchableOpacity>
                <TouchableOpacity onPress={() => removePendingImage(uri)} style={styles.pendingThumbX} hitSlop={10}>
                  <Text style={styles.pendingThumbXText}>×</Text>
                </TouchableOpacity>
              </View>
            ))}
          </ScrollView>
        </View>
      ) : null}
      {gateHint !== null ? (
        gateCanGoModel ? (
          <TouchableOpacity
            style={styles.pendingBar}
            onPress={() => {
              chatReturnAfterLoad.current = true;
              go('model');
            }}>
            <Text style={styles.pendingText}>{gateHint}</Text>
          </TouchableOpacity>
        ) : (
          <View style={styles.pendingBar}>
            <Text style={styles.pendingText}>{gateHint}</Text>
          </View>
        )
      ) : null}
      <View style={styles.inputBar}>
        <TouchableOpacity onPress={pickImage} disabled={inputLocked} style={styles.iconBtn}>
          <ImageIcon />
        </TouchableOpacity>
        <TextInput
          style={styles.input}
          value={draft}
          onChangeText={setDraft}
          placeholder="输入消息…"
          placeholderTextColor="#999999"
          multiline
          editable={!inputLocked}
        />
        <TouchableOpacity
          style={[styles.sendBtn, generating ? styles.sendBtnStop : (!canSend || inputLocked) && styles.sendBtnDisabled]}
          onPress={generating ? stopChat : sendChat}
          disabled={generating ? false : !canSend || inputLocked}>
          {generating ? <View style={styles.stopIcon} /> : <Text style={styles.sendText}>发送</Text>}
        </TouchableOpacity>
      </View>
    </View>
    );
  };

  const renderCore = () => (
    <View style={styles.screen}>
      <View style={styles.actionBar}>
        <TouchableOpacity
          style={[styles.btn, styles.btnFlex]}
          disabled={!!busy}
          onPress={importCore}>
          <Text>导入核心</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.btn, styles.btnFlex, styles.btnLast]}
          disabled={!!busy}
          onPress={updateCore}>
          <Text>从下载更新</Text>
        </TouchableOpacity>
      </View>
      {updateStatus !== null ? (
        <View style={styles.updateBar}>
          <Text style={styles.cardSub} selectable>
            {updateStatus}
          </Text>
        </View>
      ) : null}
      <ScrollView style={styles.chatList} contentContainerStyle={styles.screenContent}>
        {coreLoading && coreInfo === null && coreError === null ? (
          <View style={styles.centerBox}>
            <ActivityIndicator />
            <Text style={styles.hint}>正在读取核心…</Text>
          </View>
        ) : null}
        {coreError !== null ? (
          <TouchableOpacity style={styles.card} onPress={() => fetchCore()}>
            <Text style={styles.logFail}>加载失败：{coreError}</Text>
            <Text style={styles.hint}>点我重试</Text>
          </TouchableOpacity>
        ) : null}
        {coreInfo !== null && coreRt.cuPhase !== 'failed' ? (
          <View style={styles.card}>
            <View style={styles.cardTitleRow}>
              <Text style={styles.cardTitle} numberOfLines={1}>
                {coreInfo.id}
              </Text>
              {coreRt.cuPhase !== 'checking' && coreRt.cuPhase !== 'downloading' ? (
                <Text style={styles.tagLoaded}>已激活</Text>
              ) : null}
            </View>
            <Text style={styles.hint}>版本 {coreInfo.version}</Text>
          </View>
        ) : null}
        {coreInfo !== null && coreRt.cuPhase === 'failed' ? (
          <View style={styles.card}>
            <View style={styles.cardTitleRow}>
              <Text style={styles.cardTitle} numberOfLines={1}>
                {coreInfo.id}
              </Text>
              <Text style={styles.tagError}>异常</Text>
            </View>
            <Text style={styles.loadErrorText} selectable>
              {coreRt.cuError ?? '未知错误'}
            </Text>
          </View>
        ) : null}
        {coreInfo === null && coreError === null && !coreLoading ? (
          <Text style={styles.hint}>暂无已安装核心</Text>
        ) : null}
      </ScrollView>
    </View>
  );

  const renderModel = () => (
    <ScrollView style={styles.screen} contentContainerStyle={styles.screenContent}>
      {listLoading && modelList === null && modelError === null ? (
        <View style={styles.centerBox}>
          <ActivityIndicator />
          <Text style={styles.hint}>正在读取模型列表…</Text>
        </View>
      ) : null}
      {modelError !== null ? (
        <TouchableOpacity style={styles.card} onPress={() => fetchModels()}>
          <Text style={styles.logFail}>加载失败：{modelError}</Text>
          <Text style={styles.hint}>点我重试</Text>
        </TouchableOpacity>
      ) : null}
      {modelList !== null && modelList.length === 0 && modelError === null ? (
        <Text style={styles.hint}>暂无已导入模型</Text>
      ) : null}
      {(modelList ?? []).map(model => (
        <View key={model.id} style={styles.card}>
          <View style={styles.cardTitleRow}>
            <Text style={styles.cardTitle} numberOfLines={1}>
              {model.name}
            </Text>
            {model.paired ? <EyeIcon /> : null}
            {loadedId === model.id &&
            (runtimePhase === 'model_ready' || runtimePhase === 'generating') ? (
              <Text style={styles.tagLoaded}>已加载</Text>
            ) : null}
            {loadedId === model.id && runtimePhase === 'model_loading' ? (
              <Text style={styles.tagLoading}>加载中</Text>
            ) : null}
            {loadedId === model.id && runtimePhase === 'error' ? (
              <Text style={styles.tagError}>加载失败</Text>
            ) : null}
          </View>
          {loadedId === model.id && runtimePhase === 'error' && runtimeError ? (
            <Text style={styles.loadErrorText} numberOfLines={2}>
              {runtimeError}
            </Text>
          ) : null}
          <View style={styles.rowBtns}>
            {loadedId === model.id &&
            (runtimePhase === 'model_ready' || runtimePhase === 'generating') ? (
              <TouchableOpacity
                style={styles.btn}
                disabled={!!busy}
                onPress={() =>
                  run('卸载模型', () => Backend.unloadModel(), () => {
                    fetchModels();
                  })
                }>
                <Text>卸载</Text>
              </TouchableOpacity>
            ) : (
              <TouchableOpacity
                style={styles.btn}
                disabled={!!busy}
                onPress={() =>
                  run('加载模型', () => Backend.loadModel(model.id), () => {
                    fetchModels();
                    if (chatReturnAfterLoad.current) {
                      chatReturnAfterLoad.current = false;
                      go('chat');
                    }
                  })
                }>
                <Text>加载</Text>
              </TouchableOpacity>
            )}
            <TouchableOpacity
              style={styles.btn}
              disabled={!!busy}
              onPress={() =>
                run(
                  '配对MMPROJ',
                  pickAnd('选择MMPROJ文件', uri => Backend.importMmproj(uri, model.id)),
                  () => {
                    fetchModels();
                  },
                )
              }>
              <Text>配对</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.btn}
              disabled={!!busy}
              onPress={() => confirmDelete(model)}>
              <Text>删除</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.btn, styles.btnLast]}
              disabled={!!busy}
              onPress={() => openModelSettings(model)}>
              <Text>模板</Text>
            </TouchableOpacity>
          </View>
        </View>
      ))}
      {listLoading && modelList !== null ? <ActivityIndicator /> : null}
    </ScrollView>
  );

  const renderBackend = () => (
    <View style={styles.screen}>
      <View style={styles.actionBar}>
        <TouchableOpacity
          style={[styles.btn, styles.btnFlex]}
          disabled={!!busy}
          onPress={startBackend}>
          <Text>启动</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.btn, styles.btnFlex, styles.btnLast]}
          disabled={!!busy}
          onPress={stopBackend}>
          <Text>停止</Text>
        </TouchableOpacity>
      </View>
      <ScrollView style={styles.chatList} contentContainerStyle={styles.screenContent}>
        {backendLoading && backendInfo === null && backendError === null ? (
          <View style={styles.centerBox}>
            <ActivityIndicator />
            <Text style={styles.hint}>正在读取后端状态…</Text>
          </View>
        ) : null}
        {backendError !== null ? (
          <TouchableOpacity style={styles.card} onPress={() => fetchBackend()}>
            <Text style={styles.logFail}>加载失败：{backendError}</Text>
            <Text style={styles.hint}>点我重试</Text>
          </TouchableOpacity>
        ) : null}
        {backendInfo !== null ? (
          <View style={styles.card}>
            <View style={styles.cardTitleRow}>
              <Text style={styles.cardTitle}>状态：{backendInfo.running ? '运行中' : '未运行'}</Text>
            </View>
            <Text style={styles.cardSub} selectable>地址：{backendInfo.address ?? '—'}</Text>
            <Text style={styles.cardSub} selectable>错误：{backendInfo.error ?? '无'}</Text>
          </View>
        ) : null}
      </ScrollView>
    </View>
  );

  const renderLog = () => (
    <View style={styles.screen}>
      <View style={styles.actionBar}>
        <TouchableOpacity style={[styles.btn, styles.btnFlex]} onPress={refreshState}>
          <Text>查询</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.btn, styles.btnFlex]} onPress={() => setLog([])}>
          <Text>清空</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.btn, styles.btnFlex]} onPress={copyLog}>
          <Text>复制</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.btn, styles.btnFlex, styles.btnLast]} onPress={exportLog}>
          <Text>导出</Text>
        </TouchableOpacity>
      </View>
      <ScrollView
        ref={logScroll}
        style={styles.logList}
        contentContainerStyle={styles.screenContent}
        onContentSizeChange={() => logScroll.current?.scrollToEnd({animated: false})}>
        {log.map((line, index) => (
          <Text
            key={index}
            selectable
            style={[
              styles.logText,
              line.kind === 'fail'
                ? styles.logFail
                : line.kind === 'ok'
                  ? styles.logOk
                  : styles.logInfo,
            ]}>
            {line.text}
          </Text>
        ))}
      </ScrollView>
    </View>
  );

  const renderHeaderRight = () => {
    if (route === 'chat') {
      return (
        <TouchableOpacity onPress={() => setChatMenuOpen(true)} style={styles.iconBtn}>
          <Text style={styles.iconText}>⋮</Text>
        </TouchableOpacity>
      );
    }
    if (route === 'model') {
      return (
        <View style={styles.headerBtnRow}>
          <TouchableOpacity
            onPress={() => {
              setBudgetWan(String(Math.round(budgetPx / 10000)));
              setSettingsOpen(true);
            }}
            style={styles.headerAction}>
            <Text style={styles.headerActionText}>⚙</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={importModel} style={styles.headerAction}>
            <Text style={styles.headerActionText}>导入</Text>
          </TouchableOpacity>
        </View>
      );
    }
    return <View style={styles.headerAction} />;
  };

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => setDrawerOpen(true)} style={styles.iconBtn}>
          <Text style={styles.iconText}>＝</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{TITLES[route]}</Text>
        {renderHeaderRight()}
      </View>

      {route === 'chat'
        ? renderChat()
        : route === 'core'
          ? renderCore()
          : route === 'model'
            ? renderModel()
            : route === 'backend'
              ? renderBackend()
              : renderLog()}

      <Modal visible={drawerOpen} transparent animationType="fade" onRequestClose={() => setDrawerOpen(false)}>
        <Pressable style={styles.drawerMask} onPress={() => setDrawerOpen(false)}>
          <Pressable style={styles.drawer} onPress={e => e.stopPropagation()}>
            <Text style={styles.drawerTitle}>LocalCore</Text>
            {ROUTES.map(r => (
              <TouchableOpacity
                key={r.key}
                onPress={() => go(r.key)}
                style={[styles.drawerItem, route === r.key && styles.drawerItemActive]}>
                <Text style={[styles.drawerText, route === r.key && styles.drawerTextActive]}>
                  {r.title}
                </Text>
              </TouchableOpacity>
            ))}
          </Pressable>
        </Pressable>
      </Modal>

      <ImageView
        images={(viewerData?.uris ?? []).map(uri => ({uri}))}
        imageIndex={viewerData?.index ?? 0}
        visible={viewerData !== null}
        onRequestClose={() => setViewerData(null)}
        onLongPress={image => confirmSaveImage(String((image as any)?.uri ?? viewerData?.uris?.[viewerData?.index ?? 0] ?? ''))}
      />

      <Modal
        visible={tplOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setTplOpen(false)}>
        <Pressable style={styles.tplMask} onPress={() => setTplOpen(false)}>
          <Pressable style={styles.tplCard} onPress={e => e.stopPropagation()}>
            <Text style={styles.settingsTitle} numberOfLines={1}>
              设置{tplModel ? ' - ' + tplModel.name : ''}
            </Text>
            <Text style={styles.hint}>加载项下次加载生效；推理项下次请求生效</Text>
            {tplLoading ? (
              <View style={styles.centerBox}>
                <ActivityIndicator />
                <Text style={styles.hint}>正在读取设置…</Text>
              </View>
            ) : (
              <ScrollView style={styles.tplScroll}>
                {NUM_FIELDS.map(field => (
                  <View key={field.key}>
                    <Text style={styles.hint}>{field.label}</Text>
                    <TextInput
                      value={stForm[field.key] ?? ''}
                      onChangeText={v => setStForm(prev => ({...prev, [field.key]: v}))}
                      keyboardType="numeric"
                      placeholderTextColor="#999999"
                      style={styles.settingsInput}
                    />
                  </View>
                ))}
                <Text style={styles.hint}>停止词（逗号分隔）</Text>
                <TextInput
                  value={stForm.stop ?? ''}
                  onChangeText={v => setStForm(prev => ({...prev, stop: v}))}
                  placeholderTextColor="#999999"
                  style={styles.settingsInput}
                />
                <Text style={styles.hint}>聊天模板</Text>
                <TextInput
                  value={tplText}
                  onChangeText={setTplText}
                  multiline
                  placeholderTextColor="#999999"
                  style={styles.tplInput}
                />
              </ScrollView>
            )}
            <View style={styles.rowBtns}>
              <TouchableOpacity
                style={[styles.btn, styles.btnFlex, styles.btnLast]}
                disabled={tplLoading || !!busy}
                onPress={saveModelSettings}>
                <Text>保存</Text>
              </TouchableOpacity>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      <Modal
        visible={settingsOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setSettingsOpen(false)}>
        <Pressable
          style={styles.settingsMask}
          onPress={() => {
            setBudgetWan(String(Math.round(budgetPx / 10000)));
            setSettingsOpen(false);
          }}>
          <Pressable style={styles.settingsCard} onPress={e => e.stopPropagation()}>
            <Text style={styles.settingsTitle}>图片设置</Text>
            <Text style={styles.hint}>总分辨率预算（万像素，默认10，超出等比压缩，测试端与后端接口都生效）</Text>
            <TextInput
              value={budgetWan}
              onChangeText={setBudgetWan}
              keyboardType="numeric"
              placeholderTextColor="#999999"
              style={styles.settingsInput}
            />
            <View style={styles.rowBtns}>
              <TouchableOpacity
                style={[styles.btn, styles.btnFlex]}
                onPress={() => {
                  setBudgetWan(String(Math.round(budgetPx / 10000)));
                  setSettingsOpen(false);
                }}>
                <Text>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[styles.btn, styles.btnFlex, styles.btnLast]} onPress={saveBudget}>
                <Text>保存</Text>
              </TouchableOpacity>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      {chatMenuOpen ? (
        <Pressable style={styles.menuLayer} onPress={() => setChatMenuOpen(false)}>
          <Pressable style={styles.menu} onPress={e => e.stopPropagation()}>
            <TouchableOpacity
              style={styles.menuItem}
              onPress={clearChat}>
              <Text>清空聊天记录</Text>
            </TouchableOpacity>
          </Pressable>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#ffffff', paddingTop: 40},
  header: {
    height: 52,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#e5e5e5',
    backgroundColor: '#ffffff',
  },
  headerTitle: {fontSize: 17, fontWeight: 'bold', color: '#111111'},
  iconBtn: {width: 44, height: 44, alignItems: 'center', justifyContent: 'center'},
  iconText: {fontSize: 22, color: '#111111'},
  imgIcon: {width: 26, height: 22, borderWidth: 2, borderColor: '#666666', borderRadius: 6},
  imgSun: {
    position: 'absolute',
    top: 5,
    left: 6,
    width: 4,
    height: 4,
    borderRadius: 2,
    backgroundColor: '#666666',
  },
  imgPeakLeft: {
    position: 'absolute',
    left: 2,
    top: 12,
    width: 13,
    height: 2,
    borderRadius: 1,
    backgroundColor: '#666666',
    transform: [{rotate: '-45deg'}],
  },
  imgPeakRight: {
    position: 'absolute',
    left: 11,
    top: 12,
    width: 13,
    height: 2,
    borderRadius: 1,
    backgroundColor: '#666666',
    transform: [{rotate: '45deg'}],
  },
  headerAction: {minWidth: 44, height: 44, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 8},
  headerActionText: {fontSize: 15, color: '#1a3faa'},
  headerBtnRow: {flexDirection: 'row', alignItems: 'center'},
  settingsMask: {flex: 1, backgroundColor: 'rgba(0,0,0,0.3)', alignItems: 'center', justifyContent: 'center'},
  settingsCard: {width: 280, backgroundColor: '#ffffff', borderRadius: 12, padding: 16},
  settingsTitle: {fontSize: 16, fontWeight: 'bold', color: '#111111', marginBottom: 8},
  settingsInput: {
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 0,
    minHeight: 44,
    marginVertical: 10,
    color: '#111111',
    textAlignVertical: 'center',
    includeFontPadding: false,
  },
  tplMask: {flex: 1, backgroundColor: 'rgba(0,0,0,0.3)', alignItems: 'center', justifyContent: 'center'},
  tplCard: {width: '86%', height: '80%', backgroundColor: '#ffffff', borderRadius: 12, padding: 16},
  tplScroll: {flex: 1},
  tplInput: {
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    minHeight: 220,
    marginVertical: 10,
    color: '#111111',
    fontFamily: 'monospace',
    textAlignVertical: 'top',
    includeFontPadding: false,
  },
  screen: {flex: 1, backgroundColor: '#ffffff'},
  screenContent: {padding: 16},
  centerBox: {alignItems: 'center', paddingVertical: 24},
  hint: {fontSize: 13, color: '#666666', lineHeight: 20},
  actionBar: {flexDirection: 'row', padding: 12, borderBottomWidth: 1, borderBottomColor: '#e5e5e5'},
  updateBar: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#e5e5e5',
    backgroundColor: '#f7f7f7',
  },
  btn: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: '#cccccc',
    borderRadius: 8,
    marginRight: 8,
    backgroundColor: '#ffffff',
  },
  btnFlex: {flex: 1, alignItems: 'center'},
  btnLast: {marginRight: 0},
  card: {
    padding: 12,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 10,
    backgroundColor: '#f7f7f7',
  },
  cardTitleRow: {flexDirection: 'row', alignItems: 'center', marginBottom: 8},
  cardTitle: {flex: 1, fontSize: 15, color: '#111111', fontWeight: '600'},
  cardSub: {fontSize: 12, color: '#333333', marginTop: 4},
  tagLoaded: {fontSize: 12, color: '#1a3faa', marginLeft: 6, fontWeight: '700'},
  tagLoading: {fontSize: 12, color: '#b26a00', marginLeft: 6, fontWeight: '700'},
  tagError: {fontSize: 12, color: '#b00020', marginLeft: 6, fontWeight: '700'},
  loadErrorText: {fontSize: 12, color: '#b00020', marginBottom: 8},
  eyeOuter: {
    width: 26,
    height: 16,
    borderRadius: 8,
    borderWidth: 1.5,
    borderColor: '#666666',
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: 6,
  },
  eyePupil: {
    width: 7,
    height: 7,
    borderRadius: 3.5,
    borderWidth: 1.5,
    borderColor: '#666666',
  },
  rowBtns: {flexDirection: 'row'},
  chatList: {flex: 1},
  chatListContent: {padding: 16},
  bubble: {padding: 10, borderRadius: 10, marginBottom: 8, maxWidth: '85%', alignSelf: 'flex-start'},
  bubbleAi: {backgroundColor: '#f1f1f1', alignSelf: 'flex-start'},
  bubbleUser: {backgroundColor: '#2563eb', alignSelf: 'flex-end'},
  bubbleError: {backgroundColor: '#fdecea', alignSelf: 'flex-start', borderWidth: 1, borderColor: '#b00020'},
  bubbleAiText: {color: '#111111'},
  bubbleUserText: {color: '#ffffff'},
  userRow: {flexDirection: 'row', justifyContent: 'flex-end', alignItems: 'flex-end', marginBottom: 8},
  userContent: {flexShrink: 1, maxWidth: '85%', alignItems: 'flex-end'},
  bubbleUserInRow: {alignSelf: 'flex-end', maxWidth: '100%', marginBottom: 4},
  bubbleUserLast: {marginBottom: 0},
  sentStripInRow: {alignSelf: 'flex-end', marginBottom: 0},
  retryBtn: {
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 1,
    borderColor: '#999999',
    backgroundColor: '#ffffff',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 6,
  },
  retryBtnDisabled: {opacity: 0.4},
  retryText: {
    fontSize: 13,
    lineHeight: 14,
    padding: 0,
    margin: 0,
    color: '#666666',
    textAlign: 'center',
    textAlignVertical: 'center',
    includeFontPadding: false,
    transform: [{translateY: -1}],
  },
  sentStrip: {flexDirection: 'row', flexWrap: 'wrap', marginBottom: 8, alignSelf: 'flex-end', justifyContent: 'flex-end'},
  thumbSmall: {width: 72, height: 72, borderRadius: 10, marginLeft: 6, marginBottom: 6},
  statsBox: {backgroundColor: '#f4f4f4', borderRadius: 8, padding: 8, marginBottom: 8, alignSelf: 'flex-start', maxWidth: '85%'},
  statsText: {fontFamily: 'monospace', fontSize: 12, color: '#555555'},
  inputBar: {flexDirection: 'row', padding: 10, paddingLeft: 2, borderTopWidth: 1, borderTopColor: '#e5e5e5', alignItems: 'flex-end'},
  pendingStrip: {
    borderTopWidth: 1,
    borderTopColor: '#e5e5e5',
    backgroundColor: '#f7f7f7',
    paddingVertical: 8,
    overflow: 'visible',
  },
  pendingStripContent: {paddingHorizontal: 12, paddingTop: 10, paddingBottom: 6, flexDirection: 'row', alignItems: 'center'},
  pendingThumbWrap: {marginRight: 10, marginTop: 2, position: 'relative', overflow: 'visible', zIndex: 1},
  pendingThumb: {width: 56, height: 56, borderRadius: 8},
  pendingThumbX: {
    position: 'absolute',
    top: -8,
    right: -8,
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: '#111111',
    opacity: 0.8,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 10,
    elevation: 10,
  },
  pendingThumbXText: {color: '#ffffff', fontSize: 14, lineHeight: 16, fontWeight: '700'},
  pendingBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: '#e5e5e5',
    backgroundColor: '#f7f7f7',
  },
  pendingText: {flex: 1, fontSize: 12, color: '#333333'},
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 18,
    paddingHorizontal: 14,
    paddingVertical: 0,
    minHeight: 44,
    maxHeight: 110,
    color: '#111111',
    textAlignVertical: 'center',
    includeFontPadding: false,
  },
  sendBtn: {marginLeft: 8, backgroundColor: '#2563eb', borderRadius: 18, paddingHorizontal: 16, paddingVertical: 10, minWidth: 64, minHeight: 40, alignItems: 'center', justifyContent: 'center'},
  sendBtnDisabled: {opacity: 0.4},
  sendBtnStop: {backgroundColor: '#dc2626', opacity: 1},
  stopIcon: {width: 14, height: 14, borderRadius: 2, backgroundColor: '#ffffff'},
  sendText: {color: '#ffffff', fontWeight: '600'},
  logList: {flex: 1},
  logText: {fontSize: 13, marginBottom: 4},
  logInfo: {color: '#333333'},
  logOk: {color: '#1b5e20'},
  logFail: {color: '#b00020'},
  drawerMask: {flex: 1, backgroundColor: 'rgba(0,0,0,0.3)', flexDirection: 'row'},
  drawer: {width: 280, backgroundColor: '#ffffff', paddingTop: 56, paddingHorizontal: 12},
  drawerTitle: {fontSize: 18, fontWeight: 'bold', marginBottom: 16, marginLeft: 8, color: '#111111'},
  drawerItem: {paddingVertical: 12, paddingHorizontal: 10, borderRadius: 8},
  drawerItemActive: {backgroundColor: '#e8eefc'},
  drawerText: {fontSize: 15, color: '#333333'},
  drawerTextActive: {color: '#1a3faa', fontWeight: '700'},
  menuLayer: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'transparent',
  },
  menu: {position: 'absolute', top: 94, right: 8, backgroundColor: '#ffffff', borderRadius: 10, borderWidth: 1, borderColor: '#e0e0e0', minWidth: 170, paddingVertical: 6, elevation: 4},
  menuItem: {paddingVertical: 12, paddingHorizontal: 16},
});
