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
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import ImageView from 'react-native-image-viewing';
import AsyncStorage from '@react-native-async-storage/async-storage';
import ModelDownloadScreen from './ModelDownloadScreen';

const {Backend} = NativeModules;
const chatEvents = new NativeEventEmitter(NativeModules.Backend);
const CHAT_KEY = 'localcore.chat.v1';
const SETTINGS_KEY = 'localcore.settings.v1';
const HOT_CONTROL_STATE_KEY = 'localcore.hot-control-values.v1';
const DEFAULT_BUDGET_PX = 100000;
const EMPTY_HOT_DEFINITION = JSON.stringify({controls: []}, null, 2);

type RouteKey = 'chat' | 'core' | 'model' | 'download' | 'backend' | 'log';

// 侧边栏顺序按用户要求：后端在日志上面，日志沉底。
// 主屏叫测试：无上下文单轮，用完即走，不做复杂功能。
const ROUTES: Array<{key: RouteKey; title: string}> = [
  {key: 'chat', title: '测试'},
  {key: 'core', title: '核心'},
  {key: 'model', title: '模型'},
  {key: 'download', title: '模型下载'},
  {key: 'backend', title: '后端'},
  {key: 'log', title: '日志'},
];

const TITLES: Record<RouteKey, string> = {
  chat: '测试',
  core: '核心管理',
  model: '模型管理',
  download: '模型下载',
  backend: '后端服务',
  log: '日志',
};

type LogLine = {kind: 'info' | 'ok' | 'fail'; text: string};
type ChatMsg = {role: 'user' | 'ai' | 'error'; text: string; imageUris?: string[]; imageUri?: string | null; live?: boolean; stats?: TurnStats};
type GenerationInfo = {modelId: string; modelName: string; cold: Record<string, any>; loadRequest: Record<string, any>; hot: Record<string, any>};
type TurnStats = {inT: number; out: number; ttft: number; llm: number; generation?: GenerationInfo};

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
    `\ntotal-${fmtCount(stats.inT + stats.out)}` +
    (stats.generation
      ? `\n模型：${stats.generation.modelName || '未配置名称'}\n模型 ID：${stats.generation.modelId}` +
        `\n冷参数配置（含自定义）：\n${JSON.stringify(stats.generation.cold, null, 2)}` +
        `\n实际加载参数：\n${JSON.stringify(stats.generation.loadRequest, null, 2)}` +
        `\n热参数（含自定义）：\n${JSON.stringify(stats.generation.hot, null, 2)}`
      : '\n此历史记录未保存模型和参数信息');
  return (
    <TouchableOpacity onPress={() => setOpen(v => !v)} style={styles.statsBox}>
      <Text style={styles.statsText}>
        {open ? body : head + ' ▼'}
      </Text>
    </TouchableOpacity>
  );
}
type ModelEntry = {
  id: string;
  name: string;
  paired: boolean;
  exportable: boolean;
  external: boolean;
  ready: boolean;
  resourceStatus: string;
  resourceError: string | null;
  contextPending: boolean;
  registrationError: string | null;
};

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
  const resourceStates = new Map<string, any>();
  if (Array.isArray(root?.resourceStates)) {
    root.resourceStates.forEach((state: any) => resourceStates.set(String(state?.id ?? ''), state));
  }
  const descriptorOrigins = new Map<string, string>();
  if (Array.isArray(root?.config?.resources)) {
    root.config.resources.forEach((d: any) => descriptorOrigins.set(String(d?.id ?? ''), String(d?.origin ?? '')));
  }
  return arr.map((m: any) => {
    const resource = resourceStates.get(String(m?.resource ?? ''));
    const external = descriptorOrigins.get(String(m?.resource ?? '')) === 'external';
    const source = m?.source ?? {};
    const registrationError = source?.registrationError == null
      ? null
      : String(source.registrationError);
    const contextPending = !!source?.contextPending;
    return {
      id: String(m?.id ?? ''),
      name: String(m?.name ?? m?.id ?? '未命名'),
      paired:
        !!(m?.mmproj && String(m.mmproj).length > 0) ||
        (Array.isArray(m?.capabilities) && m.capabilities.includes('vision')),
      exportable: !!resource?.path && !external,
      external,
      ready: !!resource?.path && !contextPending && !registrationError,
      resourceStatus: String(resource?.status ?? 'MISSING'),
      resourceError: resource?.error == null ? null : String(resource.error),
      contextPending,
      registrationError,
    };
  });
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

const LOAD_FIELDS: Array<{key: string; label: string; int: boolean}> = [
  {key: 'contextSize', label: '上下文长度', int: true},
  {key: 'batchSize', label: '批大小', int: true},
  {key: 'threads', label: '线程数', int: true},
];

const HOT_FIELDS: Array<{key: string; label: string; int: boolean}> = [
  {key: 'maxTokens', label: '最大生成数', int: true},
  {key: 'temperature', label: '温度', int: false},
  {key: 'topP', label: 'Top-P', int: false},
  {key: 'topK', label: 'Top-K', int: true},
  {key: 'seed', label: '随机种子（-1 随机）', int: true},
];

const DEFAULT_HOT: Record<string, any> = {
  maxTokens: 100000,
  temperature: 0.7,
  topP: 0.95,
  topK: 40,
  seed: -1,
  stop: [],
};

const hotToForm = (hot: Record<string, any>): Record<string, string> => {
  const form: Record<string, string> = {};
  for (const field of HOT_FIELDS) {
    const value = hot?.[field.key];
    form[field.key] = value === undefined || value === null ? '' : String(value);
  }
  form.stop = Array.isArray(hot?.stop) ? hot.stop.join(',') : '';
  return form;
};

type HotControl =
  | {id: string; label: string; type: 'toggle'; default: 'on' | 'off'; states: {on: {label: string; params: Record<string, any>}; off: {label: string; params: Record<string, any>}}}
  | {id: string; label: string; type: 'select'; default: string; options: Array<{value: string; label: string; params: Record<string, any>}>}
  | {id: string; label: string; type: 'input'; valueType: 'number' | 'text'; default: number | string; bind: Record<string, '$value'>};

type HotControlValues = Record<string, string | number>;

const parseHotDefinitions = (text: string): HotControl[] => {
  const value = JSON.parse(text);
  if (value === null || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.controls)) {
    throw new Error('hotSettings 必须是包含 controls 数组的 JSON 对象');
  }
  return value.controls as HotControl[];
};

const defaultHotControlValues = (controls: HotControl[]): HotControlValues => {
  const values: HotControlValues = {};
  controls.forEach(control => {
    values[control.id] = control.default;
  });
  return values;
};

const hotControlValueValid = (control: HotControl, value: any): boolean => {
  if (control.type === 'toggle') return value === 'on' || value === 'off';
  if (control.type === 'select') return control.options.some(option => option.value === value);
  return control.valueType === 'number'
    ? typeof value === 'number' && Number.isFinite(value)
    : typeof value === 'string';
};

const mappedHotParams = (controls: HotControl[], values: HotControlValues): Record<string, any> => {
  const mapped: Record<string, any> = {};
  controls.forEach(control => {
    const value = values[control.id];
    if (!hotControlValueValid(control, value)) {
      throw new Error(`热设置控件 ${control.id} 的当前值无效`);
    }
    if (control.type === 'toggle') {
      Object.assign(mapped, control.states[value as 'on' | 'off'].params);
    } else if (control.type === 'select') {
      const option = control.options.find(item => item.value === value);
      if (!option) throw new Error(`热设置控件 ${control.id} 的选项不存在`);
      Object.assign(mapped, option.params);
    } else {
      Object.keys(control.bind).forEach(key => {
        mapped[key] = value;
      });
    }
  });
  return mapped;
};

export default function App() {
  const [route, setRoute] = useState<RouteKey>('chat');
  const [drawerOpen, setDrawerOpen] = useState(false);
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
  const latestProgress = useRef<{phase: string; done: number; total: number; elapsedMs: number; receivedAt: number} | null>(null);
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
  const [hotForm, setHotForm] = useState<Record<string, string>>(hotToForm(DEFAULT_HOT));
  const [hotOrig, setHotOrig] = useState<Record<string, string>>(hotToForm(DEFAULT_HOT));
  const hotNums = useRef<Record<string, any>>({...DEFAULT_HOT});
  const [stOrig, setStOrig] = useState<{form: Record<string, string>; loadJson: string; inferenceJson: string; tpl: string; ready: boolean}>({
    form: {},
    loadJson: '{}',
    inferenceJson: '{}',
    tpl: '',
    ready: false,
  });
  const [rightOpen, setRightOpen] = useState(false);
  const [hotDefinitionJson, setHotDefinitionJson] = useState(EMPTY_HOT_DEFINITION);
  const [hotControls, setHotControls] = useState<HotControl[]>([]);
  const [hotControlValues, setHotControlValues] = useState<HotControlValues>({});
  const [hotInputText, setHotInputText] = useState<Record<string, string>>({});
  const [extraHotOpen, setExtraHotOpen] = useState(false);
  const [extraHotDraft, setExtraHotDraft] = useState(EMPTY_HOT_DEFINITION);
  const [extraHotError, setExtraHotError] = useState<string | null>(null);
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
  const [updateProgress, setUpdateProgress] = useState<number | null>(null);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollActive = useRef(false);
  const restoreTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [backendInfo, setBackendInfo] = useState<{running: boolean; address: string | null; error: string | null} | null>(null);
  const [backendError, setBackendError] = useState<string | null>(null);
  const [backendLoading, setBackendLoading] = useState(false);
  const [serverForm, setServerForm] = useState({host: '127.0.0.1', port: '11434', apiKey: ''});
  const [serverOrig, setServerOrig] = useState({host: '127.0.0.1', port: '11434', apiKey: ''});
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
    if (rightOpen) closeRightDrawer();
    setRoute(next);
    setDrawerOpen(false);
    setRightOpen(false);
  };

  const run = (label: string, action: () => Promise<any>, afterOk?: () => void, afterFail?: (error: Error) => void) => {
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
      .catch((error: Error) => {
        push('fail', 'FAIL ' + label + ' => ' + error.message);
        afterFail?.(error);
      })
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

  const checkNum = (label: string, raw: string, int: boolean): number | null => {
    const text = (raw ?? '').trim();
    const value = Number(text);
    if (text === '' || !Number.isFinite(value)) {
      push('fail', `FAIL 保存设置 => ${label}必须是数字`);
      return null;
    }
    if (int && !Number.isInteger(value)) {
      push('fail', `FAIL 保存设置 => ${label}必须是整数`);
      return null;
    }
    return value;
  };

  const persistSettings = (px: number, hot: Record<string, any>) => {
    AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify({imageBudgetPx: px, hot})).catch((e: any) =>
      push('fail', 'FAIL 保存设置文件 => ' + (e?.message ?? String(e))),
    );
  };

  const persistHotControlValues = (values: HotControlValues) => {
    AsyncStorage.setItem(HOT_CONTROL_STATE_KEY, JSON.stringify(values)).catch((e: any) =>
      push('fail', 'FAIL 保存热设置控件状态 => ' + (e?.message ?? String(e))),
    );
  };

  const applyHot = (hot: Record<string, any>, controls = hotControls, values = hotControlValues) => {
    hotNums.current = {...hot};
    const merged = {...hot, ...mappedHotParams(controls, values)};
    Backend.setHotParams(JSON.stringify(merged)).catch((e: any) =>
      push('fail', 'FAIL 设置热参数 => ' + (e?.message ?? String(e))),
    );
  };

  const loadSettings = () => {
    AsyncStorage.getItem(SETTINGS_KEY)
      .then(raw => {
        let budget = DEFAULT_BUDGET_PX;
        let hot: Record<string, any> = {...DEFAULT_HOT};
        if (raw != null) {
          try {
            const saved = JSON.parse(raw);
            const px = Number(saved?.imageBudgetPx);
            if (px > 0) budget = Math.round(px);
            if (saved?.hot && typeof saved.hot === 'object') {
              for (const key of ['maxTokens', 'temperature', 'topP', 'topK', 'seed']) {
                const value = Number(saved.hot[key]);
                if (Number.isFinite(value)) hot[key] = value;
              }
              if (Array.isArray(saved.hot.stop)) {
                hot.stop = saved.hot.stop.filter((s: any) => typeof s === 'string');
              }
            }
          } catch {
            push('fail', 'FAIL 读取设置文件 => 文件损坏，用默认值');
          }
        }
        setBudgetWan(String(Math.round(budget / 10000)));
        setHotForm(hotToForm(hot));
        setHotOrig(hotToForm(hot));
        applyBudgetPx(budget);
        applyHot(hot);
      })
      .catch((e: any) => push('fail', 'FAIL 读取设置文件 => ' + (e?.message ?? String(e))));
    Backend.getHotSettings()
      .then(async (raw: any) => {
        const text = String(raw ?? EMPTY_HOT_DEFINITION);
        const controls = parseHotDefinitions(text);
        const savedRaw = await AsyncStorage.getItem(HOT_CONTROL_STATE_KEY);
        let saved: Record<string, any> = {};
        if (savedRaw != null) {
          const parsed = JSON.parse(savedRaw);
          if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
            throw new Error('热设置控件状态必须是 JSON 对象');
          }
          saved = parsed;
        }
        const values = defaultHotControlValues(controls);
        controls.forEach(control => {
          if (Object.prototype.hasOwnProperty.call(saved, control.id)) {
            if (hotControlValueValid(control, saved[control.id])) {
              values[control.id] = saved[control.id];
            } else {
              push('fail', `FAIL 热设置控件 ${control.id} 的持久化值已失效，已要求重新选择`);
            }
          }
        });
        setHotDefinitionJson(text);
        setHotControls(controls);
        setHotControlValues(values);
        setHotInputText(Object.fromEntries(controls.filter(control => control.type === 'input').map(control => [control.id, String(values[control.id])] )));
        persistHotControlValues(values);
        applyHot(hotNums.current, controls, values);
      })
      .catch((e: any) => push('fail', 'FAIL 读取额外热设置 => ' + (e?.message ?? String(e))));
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
    persistSettings(px, hotNums.current);
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
    if (restoreTimer.current) {
      clearTimeout(restoreTimer.current);
      restoreTimer.current = null;
    }
  };

  const pollUpdate = async () => {
    stopPoll();
    pollActive.current = true;
    setUpdateProgress(null);
    const step = async () => {
      if (!pollActive.current) return;
      try {
        const root = JSON.parse(String(await Backend.getBackendState()));
        const cu = root?.coreUpdate ?? {};
        const phase = String(cu.phase ?? '');
        // 唯一进度数据源：ResourceManager 经 resourceStates 透出的 downloaded/total。
        // UpdateManager.State 只有 phase/version/error，没有进度，直接读它只能显示纯文本。
        const states = Array.isArray(root?.resourceStates) ? root.resourceStates : [];
        const coreRs = states.find((r: any) => r?.id === 'localcore.core');
        const downloaded = Number(coreRs?.downloaded ?? NaN);
        const total = Number(coreRs?.total ?? NaN);
        const ratio =
          Number.isFinite(downloaded) && Number.isFinite(total) && total > 0
            ? Math.min(1, Math.max(0, downloaded / total))
            : null;
        const fmtMB = (bytes: number) => `${(bytes / 1048576).toFixed(1)}MB`;
        if (phase === 'checking') {
          setUpdateStatus('正在检查更新…');
          setUpdateProgress(null);
        } else if (phase === 'downloading') {
          if (ratio !== null) {
            const sizeTxt =
              Number.isFinite(downloaded) && Number.isFinite(total)
                ? ` ${fmtMB(downloaded)}/${fmtMB(total)}`
                : '';
            setUpdateStatus(`正在下载核心 ${cu.version ?? ''}${sizeTxt} ${(ratio * 100).toFixed(1)}%`);
            setUpdateProgress(ratio);
          } else {
            setUpdateStatus(`正在下载核心 ${cu.version ?? ''}…`);
            setUpdateProgress(null);
          }
        } else if (phase === 'failed') {
          setUpdateStatus(`更新失败：${cu.error ?? '未知错误'}`);
          setUpdateProgress(null);
          stopPoll();
          return;
        } else if (phase === 'disabled') {
          setUpdateStatus('更新已禁用');
          setUpdateProgress(null);
          stopPoll();
          return;
        } else {
          setUpdateStatus(cu.version ? `已是最新 ${cu.version}` : '就绪');
          setUpdateProgress(cu.version ? 1 : null);
          fetchCore();
          stopPoll();
          // 满格蓝只做3秒高亮，之后退回灰色状态条，文字保留。
          if (cu.version) {
            restoreTimer.current = setTimeout(() => {
              setUpdateProgress(null);
              restoreTimer.current = null;
            }, 3000);
          }
          return;
        }
      } catch (e: any) {
        setUpdateStatus(`状态读取失败：${e?.message ?? String(e)}`);
        setUpdateProgress(null);
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
      const serverText = String(await Backend.getServerSettings());
      const server = JSON.parse(serverText);
      const form = {
        host: String(server?.host ?? '127.0.0.1'),
        port: String(server?.port ?? '11434'),
        apiKey: String(server?.apiKey ?? ''),
      };
      setServerForm(form);
      setServerOrig(form);
      setBackendError(null);
    } catch (error: any) {
      const message = error?.message ?? String(error);
      setBackendError(message);
      push('fail', 'FAIL 刷新后端状态 => ' + message);
    } finally {
      setBackendLoading(false);
    }
  };

  const saveServerSettings = () => {
    const port = Number(serverForm.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      push('fail', 'FAIL 保存后端设置 => 端口必须是 1-65535 的整数');
      return;
    }
    if (!serverForm.host.trim()) {
      push('fail', 'FAIL 保存后端设置 => 监听地址不能为空');
      return;
    }
    run(
      '保存后端设置',
      () =>
        Backend.setServerSettings(
          JSON.stringify({host: serverForm.host.trim(), port, apiKey: serverForm.apiKey}),
        ),
      () => {
        fetchBackend();
      },
    );
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
              generation: item.stats.generation,
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
      latestProgress.current = {phase, done, total, elapsedMs: Number(event?.elapsedMs ?? 0), receivedAt: Date.now()};
      progressStarted.current = true;
    });
    // 回调只保存计算快照，显示统一由 100ms 定时器驱动，不等待新 token。
    const progressTimer = setInterval(() => {
      const progress = latestProgress.current;
      if (prefillComplete.current || !progress) return;
      const {phase, done, total} = progress;
      const elapsedMs = progress.elapsedMs + Date.now() - progress.receivedAt;
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
      setProgressMsg(msg);
    }, 100);
    return () => {
      sub.remove();
      stageSub.remove();
      progSub.remove();
      clearInterval(progressTimer);
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

  const importExternalModel = () =>
    run(
      '引用外部模型',
      pickAnd('引用外部模型', uri => Backend.importModelExternal(uri)),
      () => {
        fetchModels();
      },
      askAllFilesAccess,
    );

  const askAllFilesAccess = (error: any) => {
    const message = error?.message ?? String(error);
    if (!message.includes('NEED_ALL_FILES_ACCESS')) return;
    Alert.alert(
      '需要所有文件访问权限',
      '直接读取外部模型文件需要开启「所有文件访问权限」，去设置页打开后重试。',
      [
        {text: '取消', style: 'cancel'},
        {
          text: '去设置',
          onPress: () =>
            Backend.openAllFilesAccessSettings().catch((e: any) =>
              push('fail', 'FAIL 打开设置页 => ' + (e?.message ?? String(e))),
            ),
        },
      ],
    );
  };

  const importCore = () =>
    run('导入核心', pickAnd('导入核心', uri => Backend.importCore(uri)), () => {
      fetchCore();
    });

  const updateCore = () =>
    run('从仓库 Release 下载/更新核心', () => Backend.checkCoreUpdate(), () => {
      fetchCore();
      pollUpdate();
    });

  // 启动指令只是投递，后台线程完成图初始化+端口绑定才算就绪：点一下之后轮询等就绪，
  // 不再靠第二次点击去“补”状态。轮询过程打 info 日志，与原生分阶段日志对照看卡点。
  const waitBackendRunning = async (tries = 12) => {
    for (let i = 0; i < tries; i++) {
      try {
        const root = JSON.parse(String(await Backend.getBackendState()));
        if (root?.backend?.running) {
          await fetchBackend();
          return true;
        }
      } catch {
        // 状态读失败就继续等，不中断轮询。
      }
      push('info', `·· 等待后端就绪…(${(i + 1) * 500}ms)`);
      await new Promise(r => setTimeout(r, 500));
    }
    await fetchBackend();
    return false;
  };

  const startBackend = () =>
    run(
      '启动后端服务',
      async () => {
        await Backend.startService();
        push('info', '·· 启动指令已发送，等待后台线程就绪（分阶段日志见日志屏/通知）…');
        const ok = await waitBackendRunning();
        if (!ok) throw new Error('后端仍未就绪，请看通知与日志分阶段耗时后重试');
        return '后端已就绪';
      },
      () => {
        fetchBackend();
        Backend.isBatteryWhitelisted()
        .then((whitelisted: boolean) => {
          if (whitelisted) return;
          Alert.alert(
            '保活需要电池优化白名单',
            '小米/澎湃锁屏后会杀后台，通知也会一起没。去设置把 LocalCore 设为「无限制」，并在最近任务里下滑锁定它。',
            [
              {text: '暂不', style: 'cancel'},
              {
                text: '去设置',
                onPress: () =>
                  Backend.openBatterySettings().catch((e: any) =>
                    push('fail', 'FAIL 打开设置页 => ' + (e?.message ?? String(e))),
                  ),
              },
            ],
          );
        })
        .catch((e: any) => push('fail', 'FAIL 检查电池白名单 => ' + (e?.message ?? String(e))));
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
    go('chat');
    const uris: string[] = [];
    for (const m of messages) {
      for (const u of msgImageUris(m)) uris.push(u);
    }
    for (const u of pendingImages) uris.push(u);
    setMessages([]);
    setPendingImages([]);
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
    latestProgress.current = null;
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
          generation: result.generation,
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
    setStOrig({form: {}, loadJson: '{}', inferenceJson: '{}', tpl: '', ready: false});
    setTplLoading(true);
    setTplOpen(true);
    Promise.all([Backend.getModelSettings(model.id), Backend.getModelTemplate(model.id)])
      .then(([settings, text]: any[]) => {
        const root = JSON.parse(String(settings ?? '{}'));
        const form: Record<string, string> = {};
        for (const field of LOAD_FIELDS) {
          const value = root?.load?.[field.key];
          form[field.key] = value === undefined || value === null ? '' : String(value);
        }
        const tpl = String(text ?? '');
        setStForm(form);
        setStOrig({form: {...form}, loadJson: JSON.stringify(root?.load ?? {}), inferenceJson: JSON.stringify(root?.inference ?? {}), tpl, ready: true});
        setTplText(tpl);
        setTplLoading(false);
      })
      .catch((e: any) => {
        setTplLoading(false);
        push('fail', 'FAIL 读取模型设置 => ' + (e?.message ?? String(e)));
      });
  };

  const closeModelSettings = () => {
    setTplOpen(false);
    if (!tplModel || !stOrig.ready) return;
    const id = tplModel.id;
    let origLoad: Record<string, any> = {};
    try {
      const parsed = JSON.parse(stOrig.loadJson);
      if (parsed && typeof parsed === 'object') origLoad = parsed;
    } catch {
      push('fail', 'FAIL 保存设置 => 原始加载参数损坏，本次不保存');
      return;
    }
    const finalLoad: Record<string, any> = {...origLoad};
    const fixed: Record<string, string> = {};
    for (const field of LOAD_FIELDS) {
      const value = checkNum(field.label, stForm[field.key] ?? '', field.int);
      if (value === null) {
        fixed[field.key] = stOrig.form[field.key] ?? '';
      } else {
        finalLoad[field.key] = value;
      }
    }
    if (Object.keys(fixed).length > 0) setStForm(prev => ({...prev, ...fixed}));
    const dirty =
      LOAD_FIELDS.some(field => String(finalLoad[field.key] ?? '') !== String(stOrig.form[field.key] ?? '')) ||
      tplText !== stOrig.tpl;
    if (!dirty) return;
    const text = tplText;
    const inferenceJson = stOrig.inferenceJson;
    run(
      '保存设置',
      async () => {
        await Backend.setModelSettings(id, JSON.stringify(finalLoad), inferenceJson);
        await Backend.setModelTemplate(id, text);
      },
      () => {
        fetchModels();
      },
    );
  };

  const openRightDrawer = () => {
    setHotOrig({...hotForm});
    setRightOpen(true);
  };

  const openExtraHotSettings = () => {
    setExtraHotDraft(hotDefinitionJson);
    setExtraHotError(null);
    setExtraHotOpen(true);
  };

  const closeExtraHotSettings = () => {
    setExtraHotOpen(false);
    setExtraHotDraft(hotDefinitionJson);
    setExtraHotError(null);
  };

  const saveExtraHotSettings = () => {
    let formatted = '';
    let nextControls: HotControl[] = [];
    setExtraHotError(null);
    run(
      '保存额外热设置定义',
      async () => {
        formatted = String(await Backend.setHotSettings(extraHotDraft));
        nextControls = parseHotDefinitions(formatted);
        return formatted;
      },
      () => {
        const nextValues = defaultHotControlValues(nextControls);
        nextControls.forEach(control => {
          const previous = hotControlValues[control.id];
          if (previous !== undefined && hotControlValueValid(control, previous)) nextValues[control.id] = previous;
        });
        setHotDefinitionJson(formatted);
        setHotControls(nextControls);
        setHotControlValues(nextValues);
        setHotInputText(Object.fromEntries(nextControls.filter(control => control.type === 'input').map(control => [control.id, String(nextValues[control.id])] )));
        persistHotControlValues(nextValues);
        applyHot(hotNums.current, nextControls, nextValues);
        setExtraHotDraft(formatted);
        setExtraHotOpen(false);
      },
      error => setExtraHotError(error.message),
    );
  };

  const closeRightDrawer = () => {
    setRightOpen(false);
    const finalHot: Record<string, any> = {};
    const fixed: Record<string, string> = {};
    for (const field of HOT_FIELDS) {
      const value = checkNum(field.label, hotForm[field.key] ?? '', field.int);
      if (value === null) {
        fixed[field.key] = hotOrig[field.key] ?? '';
        finalHot[field.key] = Number(hotOrig[field.key] ?? 0);
      } else {
        finalHot[field.key] = value;
      }
    }
    finalHot.stop = (hotForm.stop ?? '')
      .split(',')
      .map(s => s.trim())
      .filter(s => s.length > 0);
    if (Object.keys(fixed).length > 0) setHotForm(prev => ({...prev, ...fixed}));
    const dirty =
      HOT_FIELDS.some(field => String(finalHot[field.key]) !== String(hotOrig[field.key] ?? '')) ||
      (hotForm.stop ?? '') !== (hotOrig.stop ?? '');
    if (!dirty) return;
    applyHot(finalHot);
    persistSettings(budgetPx, finalHot);
    setHotOrig(hotToForm(finalHot));
  };

  const loadEmbedded = () => {
    if (!tplModel) return;
    const id = tplModel.id;
    setTplLoading(true);
    Backend.getEmbeddedTemplate(id)
      .then((text: any) => {
        setTplText(String(text ?? ''));
        setTplLoading(false);
      })
      .catch((e: any) => {
        setTplLoading(false);
        push('fail', 'FAIL 读取内置模板 => ' + (e?.message ?? String(e)));
      });
  };

  const confirmDelete = (model: ModelEntry) => {
    Alert.alert(
      '删除模型',
      model.external
        ? model.name + ' 的外部源文件保留，仅移除引用；已配对的投影将一起删除，是否继续？'
        : model.name + ' 及已配对的投影将一起删除，是否继续？',
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
          {updateProgress !== null ? (
            <View
              style={[styles.updateBarFill, {width: `${Math.round(updateProgress * 100)}%`}]}
              pointerEvents="none"
            />
          ) : null}
          <Text style={[styles.cardSub, styles.updateBarText]} selectable>
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
            {model.external ? <Text style={styles.tagExternal}>外部</Text> : null}
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
            {!model.ready && model.registrationError ? (
              <Text style={styles.tagError}>登记失败</Text>
            ) : !model.ready && model.contextPending ? (
              <Text style={styles.tagLoading}>登记中</Text>
            ) : !model.ready &&
              (model.resourceStatus === 'QUEUED' || model.resourceStatus === 'DOWNLOADING') ? (
              <Text style={styles.tagLoading}>下载中</Text>
            ) : !model.ready && model.resourceStatus === 'FAILED' ? (
              <Text style={styles.tagError}>下载失败</Text>
            ) : !model.ready && model.resourceStatus === 'CANCELLED' ? (
              <Text style={styles.tagError}>已取消</Text>
            ) : !model.ready ? (
              <Text style={styles.tagError}>未下载</Text>
            ) : null}
          </View>
          {loadedId === model.id && runtimePhase === 'error' && runtimeError ? (
            <Text style={styles.loadErrorText} numberOfLines={2}>
              {runtimeError}
            </Text>
          ) : null}
          {!model.ready && (model.registrationError || model.resourceError) ? (
            <Text style={styles.loadErrorText} selectable>
              {model.registrationError ?? model.resourceError}
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
                style={[styles.btn, !model.ready && styles.btnDisabled]}
                disabled={!!busy || !model.ready}
                onPress={() =>
                  run(
                    '加载模型',
                    () => Backend.loadModel(model.id),
                    () => {
                      fetchModels();
                      if (chatReturnAfterLoad.current) {
                        chatReturnAfterLoad.current = false;
                        go('chat');
                      }
                    },
                    askAllFilesAccess,
                  )
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
              style={[styles.btn, !model.exportable && styles.btnDisabled]}
              disabled={!!busy || !model.exportable}
              onPress={() => run('导出模型', () => Backend.exportModel(model.id))}>
              <Text>导出</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.modelGearBtn}
              disabled={!!busy}
              hitSlop={8}
              onPress={() => openModelSettings(model)}>
              <Text>⚙</Text>
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
        <View style={styles.card}>
          <View style={styles.cardTitleRow}>
            <Text style={styles.cardTitle}>服务设置</Text>
          </View>
          <Text style={styles.hint}>监听地址</Text>
          <TextInput
            value={serverForm.host}
            onChangeText={v => setServerForm(prev => ({...prev, host: v}))}
            autoCapitalize="none"
            autoCorrect={false}
            placeholderTextColor="#999999"
            style={styles.settingsInput}
          />
          <Text style={styles.hint}>端口（1-65535）</Text>
          <TextInput
            value={serverForm.port}
            onChangeText={v => setServerForm(prev => ({...prev, port: v}))}
            keyboardType="numeric"
            placeholderTextColor="#999999"
            style={styles.settingsInput}
          />
          <Text style={styles.hint}>Bearer API Key（留空表示免鉴权）</Text>
          <TextInput
            value={serverForm.apiKey}
            onChangeText={v => setServerForm(prev => ({...prev, apiKey: v}))}
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry={false}
            placeholder="留空则无需 Authorization 头"
            placeholderTextColor="#999999"
            style={styles.settingsInput}
          />
          <Text style={styles.hint}>
            已配置：{serverOrig.apiKey ? `已设置（长度${serverOrig.apiKey.length}）` : '未设置（免鉴权）'}
            {'\n'}调用时请求头 Authorization: Bearer {'<key>'}
          </Text>
          <View style={styles.rowBtns}>
            <TouchableOpacity
              style={[styles.btn, styles.btnFlex, styles.btnLast]}
              disabled={!!busy}
              onPress={saveServerSettings}>
              <Text>保存服务设置</Text>
            </TouchableOpacity>
          </View>
        </View>
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
        <TouchableOpacity
          onPress={() => {
            if (rightOpen) closeRightDrawer();
            else openRightDrawer();
          }}
          style={styles.iconBtn}>
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
          <TouchableOpacity onPress={importExternalModel} style={styles.headerAction}>
            <Text style={styles.headerActionText}>引用外部</Text>
          </TouchableOpacity>
        </View>
      );
    }
    return <View style={styles.headerAction} />;
  };

  const updateHotControlValue = (control: HotControl, value: string | number) => {
    if (!hotControlValueValid(control, value)) {
      push('fail', `FAIL 热设置控件 ${control.id} 的值无效`);
      return;
    }
    const next = {...hotControlValues, [control.id]: value};
    setHotControlValues(next);
    if (control.type === 'input') setHotInputText(prev => ({...prev, [control.id]: String(value)}));
    persistHotControlValues(next);
    applyHot(hotNums.current, hotControls, next);
  };

  const renderHotControl = (control: HotControl) => {
    const value = hotControlValues[control.id];
    if (control.type === 'toggle') {
      const state = value === 'on' ? control.states.on : control.states.off;
      return (
        <View key={control.id} style={styles.hotControlRow}>
          <View style={styles.hotControlLabel}>
            <Text style={styles.hint}>{control.label}</Text>
            <Text style={styles.hotControlState}>{state.label}</Text>
          </View>
          <Switch value={value === 'on'} onValueChange={enabled => updateHotControlValue(control, enabled ? 'on' : 'off')} />
        </View>
      );
    }
    if (control.type === 'select') {
      const selected = control.options.find(option => option.value === value) ?? control.options[0];
      return (
        <View key={control.id} style={styles.hotControlRow}>
          <Text style={styles.hotControlLabel}>{control.label}</Text>
          <TouchableOpacity
            style={styles.hotSelectButton}
            onPress={() => Alert.alert(control.label, undefined, [
              ...control.options.map(option => ({text: option.label, onPress: () => updateHotControlValue(control, option.value)})),
              {text: '取消', style: 'cancel'},
            ])}>
            <Text style={styles.hotSelectText}>{selected.label}</Text>
          </TouchableOpacity>
        </View>
      );
    }
    return (
      <View key={control.id} style={styles.hotInputRow}>
        <Text style={styles.hotInputLabel}>{control.label}</Text>
        <TextInput
          value={hotInputText[control.id] ?? String(value ?? '')}
          onChangeText={text => {
            setHotInputText(prev => ({...prev, [control.id]: text}));
            if (control.valueType === 'text') updateHotControlValue(control, text);
          }}
          onEndEditing={event => {
            if (control.valueType === 'number') {
              const number = Number(event.nativeEvent.text.trim());
              if (Number.isFinite(number)) updateHotControlValue(control, number);
              else {
                push('fail', `FAIL ${control.label} 必须是数字`);
                setHotInputText(prev => ({...prev, [control.id]: String(value ?? control.default)}));
              }
            }
          }}
          keyboardType={control.valueType === 'number' ? 'numeric' : 'default'}
          placeholderTextColor="#999999"
          style={styles.settingsInput}
        />
      </View>
    );
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
            : route === 'download'
              ? <ModelDownloadScreen />
              : route === 'backend'
                ? renderBackend()
                : renderLog()}

      <Modal visible={drawerOpen} transparent animationType="fade" onRequestClose={() => setDrawerOpen(false)}>
        <Pressable style={styles.drawerMask} onPress={() => setDrawerOpen(false)}>
          <Pressable
            style={[styles.drawer, {paddingTop: 8}]} // Modal内容起点已在状态栏下方, 不再叠加状态栏高度
            onPress={e => e.stopPropagation()}>
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
        onRequestClose={closeModelSettings}>
        <Pressable style={styles.tplMask} onPress={closeModelSettings}>
          <Pressable style={styles.tplCard} onPress={e => e.stopPropagation()}>
            <Text style={styles.settingsTitle} numberOfLines={1}>
              {tplModel ? tplModel.name : ''}
            </Text>
            {tplLoading ? (
              <View style={styles.centerBox}>
                <ActivityIndicator />
                <Text style={styles.hint}>正在读取设置…</Text>
              </View>
            ) : (
              <ScrollView style={styles.tplScroll}>
                {LOAD_FIELDS.map(field => (
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
                <Text style={styles.hint}>聊天模板</Text>
                <View style={styles.rowBtns}>
                  <TouchableOpacity
                    style={[styles.btn, styles.btnFlex]}
                    disabled={tplLoading}
                    onPress={() => setTplText('')}>
                    <Text>清空</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.btn, styles.btnFlex]}
                    disabled={tplLoading || !tplText}
                    onPress={() => run('复制模板', () => Backend.copyText(tplText))}>
                    <Text>复制</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.btn, styles.btnFlex, styles.btnLast]}
                    disabled={tplLoading || !tplModel}
                    onPress={loadEmbedded}>
                    <Text>默认</Text>
                  </TouchableOpacity>
                </View>
                <TextInput
                  value={tplText}
                  onChangeText={setTplText}
                  multiline
                  placeholderTextColor="#999999"
                  style={styles.tplInput}
                />
              </ScrollView>
            )}
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

      <Modal
        visible={extraHotOpen}
        transparent
        animationType="fade"
        onRequestClose={closeExtraHotSettings}>
        <Pressable style={styles.settingsMask} onPress={closeExtraHotSettings}>
          <Pressable style={styles.extraHotCard} onPress={e => e.stopPropagation()}>
            <Text style={styles.settingsTitle}>编辑额外热设置定义</Text>
            <TextInput
              value={extraHotDraft}
              onChangeText={setExtraHotDraft}
              multiline
              autoCapitalize="none"
              autoCorrect={false}
              placeholderTextColor="#999999"
              style={styles.extraHotInput}
            />
            {extraHotError !== null ? <Text style={styles.logFail}>{extraHotError}</Text> : null}
            <View style={styles.rowBtns}>
              <TouchableOpacity style={[styles.btn, styles.btnFlex]} onPress={closeExtraHotSettings}>
                <Text>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[styles.btn, styles.btnFlex, styles.btnLast]} disabled={!!busy} onPress={saveExtraHotSettings}>
                <Text>保存</Text>
              </TouchableOpacity>
            </View>
          </Pressable>
        </Pressable>
      </Modal>

      {rightOpen ? (
        <Pressable style={styles.rightMask} onPress={closeRightDrawer}>
          <Pressable
            style={[styles.rightDrawer, {paddingTop: (StatusBar.currentHeight ?? 24) + 8}]} // 主界面edge-to-edge从屏幕顶算, H+8与左抽屉顶部对齐
            onPress={e => e.stopPropagation()}>
            <Text style={[styles.drawerTitle, {textAlign: 'center', marginLeft: 0}]}>推理设置</Text>
            <TouchableOpacity style={[styles.btn, styles.clearBtn]} onPress={clearChat}>
              <Text>清空聊天记录</Text>
            </TouchableOpacity>
            <ScrollView style={styles.rightScroll}>
              {HOT_FIELDS.map(field => (
                <View key={field.key}>
                  <Text style={styles.hint}>{field.label}</Text>
                  <TextInput
                    value={hotForm[field.key] ?? ''}
                    onChangeText={v => setHotForm(prev => ({...prev, [field.key]: v}))}
                    keyboardType="numeric"
                    placeholderTextColor="#999999"
                    style={styles.settingsInput}
                  />
                </View>
              ))}
              <Text style={styles.hint}>停止词（逗号分隔）</Text>
              <TextInput
                value={hotForm.stop ?? ''}
                onChangeText={v => setHotForm(prev => ({...prev, stop: v}))}
                placeholderTextColor="#999999"
                style={styles.settingsInput}
              />
              {hotControls.map(renderHotControl)}
              <TouchableOpacity style={[styles.btn, styles.addHotBtn]} onPress={openExtraHotSettings}>
                <Text>新增热设置项</Text>
              </TouchableOpacity>
            </ScrollView>
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
  extraHotCard: {width: '90%', height: '72%', backgroundColor: '#ffffff', borderRadius: 12, padding: 16},
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
  extraHotInput: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 10,
    minHeight: 160,
    marginBottom: 12,
    color: '#111111',
    textAlignVertical: 'top',
    includeFontPadding: false,
    fontFamily: 'monospace',
  },
  extraHotRow: {borderTopWidth: 1, borderTopColor: '#eeeeee', paddingVertical: 8},
  extraHotValue: {fontFamily: 'monospace', fontSize: 12, color: '#333333', marginTop: 2},
  addHotBtn: {marginTop: 10, marginRight: 0, alignItems: 'center'},
  hotControlRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderTopWidth: 1, borderTopColor: '#eeeeee', paddingVertical: 8},
  hotControlLabel: {flex: 1, color: '#333333'},
  hotControlState: {fontSize: 12, color: '#777777', marginTop: 2},
  hotSelectButton: {minWidth: 110, minHeight: 40, borderWidth: 1, borderColor: '#dddddd', borderRadius: 8, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 10},
  hotSelectText: {color: '#222222'},
  hotInputRow: {borderTopWidth: 1, borderTopColor: '#eeeeee', paddingTop: 8},
  hotInputLabel: {fontSize: 13, color: '#666666'},
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
  // 填充条 width 百分比按容器内容盒结算，容器禁一切内边距，否则满格也盖不住 padding 区。
  updateBar: {
    borderBottomWidth: 1,
    borderBottomColor: '#e5e5e5',
    backgroundColor: '#f7f7f7',
    position: 'relative',
    overflow: 'hidden',
  },
  updateBarFill: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    backgroundColor: '#cfe0ff',
  },
  updateBarText: {position: 'relative', zIndex: 1, paddingHorizontal: 12, paddingVertical: 8},
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
  btnDisabled: {opacity: 0.45},
  modelGearBtn: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
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
  tagExternal: {fontSize: 12, color: '#0a7a42', marginLeft: 6, fontWeight: '700'},
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
  drawer: {width: 280, backgroundColor: '#ffffff', paddingHorizontal: 12},
  drawerTitle: {fontSize: 18, fontWeight: 'bold', marginBottom: 8, marginLeft: 8, color: '#111111'},
  drawerItem: {paddingVertical: 12, paddingHorizontal: 10, borderRadius: 8},
  drawerItemActive: {backgroundColor: '#e8eefc'},
  drawerText: {fontSize: 15, color: '#333333'},
  drawerTextActive: {color: '#1a3faa', fontWeight: '700'},
  rightMask: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.3)',
    flexDirection: 'row',
    justifyContent: 'flex-end',
  },
  rightDrawer: {width: 280, backgroundColor: '#ffffff', paddingHorizontal: 12},
  rightHead: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8},
  rightScroll: {flex: 1},
  clearBtn: {marginBottom: 10, alignItems: 'center'},
});
