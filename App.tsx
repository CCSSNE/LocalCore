import React, {useRef, useState} from 'react';
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

const {Backend} = NativeModules;

type RouteKey = 'chat' | 'core' | 'model' | 'backend' | 'log';

// 侧边栏顺序按用户要求：后端在日志上面，日志沉底。
const ROUTES: Array<{key: RouteKey; title: string}> = [
  {key: 'chat', title: '聊天'},
  {key: 'core', title: '核心'},
  {key: 'model', title: '模型'},
  {key: 'backend', title: '后端'},
  {key: 'log', title: '日志'},
];

const TITLES: Record<RouteKey, string> = {
  chat: 'LocalCore 聊天',
  core: '核心管理',
  model: '模型管理',
  backend: '后端服务',
  log: '日志',
};

type LogLine = {kind: 'info' | 'ok' | 'fail'; text: string};
type ChatMsg = {role: 'user' | 'ai' | 'error'; text: string};

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

function ActionCard(props: {
  title: string;
  desc?: string;
  running: boolean;
  onPress: () => void;
}) {
  return (
    <TouchableOpacity
      onPress={props.onPress}
      disabled={props.running}
      style={[styles.card, props.running && styles.cardDisabled]}>
      <View style={styles.cardRow}>
        <View style={styles.cardText}>
          <Text style={styles.cardTitle}>{props.title}</Text>
          {props.desc ? <Text style={styles.cardDesc}>{props.desc}</Text> : null}
        </View>
        {props.running ? <ActivityIndicator /> : null}
      </View>
    </TouchableOpacity>
  );
}

export default function App() {
  const [route, setRoute] = useState<RouteKey>('chat');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [chatMenuOpen, setChatMenuOpen] = useState(false);
  const [log, setLog] = useState<LogLine[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [draft, setDraft] = useState('');
  const [stateCache, setStateCache] = useState<string | null>(null);
  const chatScroll = useRef<ScrollView | null>(null);
  const logScroll = useRef<ScrollView | null>(null);

  const push = (kind: LogLine['kind'], text: string) =>
    setLog(prev => [...prev, {kind, text}]);

  const go = (next: RouteKey) => {
    setRoute(next);
    setDrawerOpen(false);
    setChatMenuOpen(false);
  };

  const run = (label: string, action: () => Promise<any>) => {
    if (busy) {
      push('fail', 'FAIL ' + label + ' => 已有任务进行中: ' + busy + '，请稍候再试');
      return;
    }
    setBusy(label);
    push('info', '>> ' + label);
    action()
      .then((value: any) =>
        push('ok', 'OK ' + label + (value ? ' => ' + String(value) : '')),
      )
      .catch((error: Error) => push('fail', 'FAIL ' + label + ' => ' + error.message))
      .finally(() => setBusy(null));
  };

  const refreshState = () =>
    run('查询状态', async () => {
      const value = await Backend.getBackendState();
      setStateCache(String(value));
      return value;
    });

  const stateJson = (): any | null => {
    if (!stateCache) return null;
    try {
      return JSON.parse(stateCache);
    } catch (error: any) {
      push('fail', 'FAIL 解析状态 => ' + (error?.message ?? String(error)));
      return null;
    }
  };

  const sendChat = () => {
    const prompt = draft.trim();
    if (!prompt || busy) return;
    setDraft('');
    setMessages(prev => [...prev, {role: 'user', text: prompt}]);
    const label = '聊天推理';
    setBusy(label);
    push('info', '>> ' + label + '：' + prompt);
    (async () => {
      const id = await firstModelId();
      return Backend.testChat(id, prompt);
    })()
      .then((value: any) => {
        const text = String(value ?? '');
        setMessages(prev => [...prev, {role: 'ai', text}]);
        push('ok', 'OK ' + label + ' => ' + text);
      })
      .catch((error: Error) => {
        setMessages(prev => [...prev, {role: 'error', text: error.message}]);
        push('fail', 'FAIL ' + label + ' => ' + error.message);
      })
      .finally(() => setBusy(null));
  };

  const parsed = stateJson();

  const renderChat = () => (
    <View style={styles.screen}>
      <ScrollView
        ref={chatScroll}
        style={styles.chatList}
        contentContainerStyle={styles.chatListContent}
        onContentSizeChange={() => chatScroll.current?.scrollToEnd({animated: true})}>
        {messages.length === 0 ? (
          <Text style={styles.hint}>先去「模型」屏加载最新模型，再回来聊天。{"\n"}聊天走 NativeRuntime → loader → core，与旧「测试推理」同一链路。</Text>
        ) : null}
        {messages.map((m, i) => (
          <View
            key={i}
            style={[
              styles.bubble,
              m.role === 'user' ? styles.bubbleUser : m.role === 'error' ? styles.bubbleError : styles.bubbleAi,
            ]}>
            <Text style={m.role === 'user' ? styles.bubbleUserText : styles.bubbleAiText} selectable>
              {m.text}
            </Text>
          </View>
        ))}
        {busy === '聊天推理' ? (
          <View style={[styles.bubble, styles.bubbleAi]}>
            <ActivityIndicator />
            <Text style={styles.bubbleAiText}>正在推理…</Text>
          </View>
        ) : null}
      </ScrollView>
      <View style={styles.inputBar}>
        <TextInput
          style={styles.input}
          value={draft}
          onChangeText={setDraft}
          placeholder="输入消息…"
          placeholderTextColor="#999999"
          multiline
        />
        <TouchableOpacity
          style={[styles.sendBtn, (!draft.trim() || busy) && styles.sendBtnDisabled]}
          onPress={sendChat}
          disabled={!draft.trim() || !!busy}>
          <Text style={styles.sendText}>发送</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  const renderCore = () => (
    <ScrollView style={styles.screen} contentContainerStyle={styles.screenContent}>
      <ActionCard
        title="导入核心"
        desc="选择 LocalCore 核心 SO/ZIP"
        running={busy === '导入核心'}
        onPress={() => run('导入核心', pickAnd('导入核心', uri => Backend.importCore(uri)))}
      />
      <ActionCard
        title="从仓库 Release 下载/更新核心"
        running={busy === '从仓库 Release 下载/更新核心'}
        onPress={() => run('从仓库 Release 下载/更新核心', () => Backend.checkCoreUpdate())}
      />
      <ActionCard
        title="刷新核心状态"
        desc="读取 runtime / coreUpdate"
        running={busy === '查询状态'}
        onPress={refreshState}
      />
      {parsed ? (
        <View style={styles.statusBox}>
          <Text style={styles.statusTitle}>runtime</Text>
          <Text style={styles.statusText} selectable>{JSON.stringify(parsed.runtime ?? null, null, 2)}</Text>
          <Text style={styles.statusTitle}>coreUpdate</Text>
          <Text style={styles.statusText} selectable>{JSON.stringify(parsed.coreUpdate ?? null, null, 2)}</Text>
        </View>
      ) : (
        <Text style={styles.hint}>点「刷新核心状态」查看当前核心 ID 与版本。</Text>
      )}
    </ScrollView>
  );

  const renderModel = () => (
    <ScrollView style={styles.screen} contentContainerStyle={styles.screenContent}>
      <ActionCard
        title="导入模型"
        desc="选择 .gguf"
        running={busy === '导入模型'}
        onPress={() => run('导入模型', pickAnd('导入模型', uri => Backend.importModel(uri)))}
      />
      <ActionCard
        title="为最新模型导入 MMPROJ"
        desc="选择 .gguf 投影文件"
        running={busy === '导入 MMPROJ'}
        onPress={() =>
          run(
            '导入 MMPROJ',
            pickAnd('导入 MMPROJ', async uri => Backend.importMmproj(uri, await firstModelId())),
          )
        }
      />
      <ActionCard
        title="加载最新模型"
        running={busy === '加载最新模型'}
        onPress={() =>
          run('加载最新模型', async () => {
            const id = await firstModelId();
            return Backend.loadModel(id);
          })
        }
      />
      <ActionCard
        title="刷新模型列表"
        running={busy === '查询状态'}
        onPress={refreshState}
      />
      {parsed ? (
        <View style={styles.statusBox}>
          <Text style={styles.statusTitle}>config.models</Text>
          <Text style={styles.statusText} selectable>
            {JSON.stringify(parsed.config?.models ?? parsed.config ?? null, null, 2)}
          </Text>
        </View>
      ) : (
        <Text style={styles.hint}>点「刷新模型列表」查看已导入模型。</Text>
      )}
    </ScrollView>
  );

  const renderBackend = () => (
    <ScrollView style={styles.screen} contentContainerStyle={styles.screenContent}>
      <ActionCard
        title="启动后端服务"
        running={busy === '启动后端服务'}
        onPress={() => run('启动后端服务', () => Backend.startService())}
      />
      <ActionCard
        title="停止后端服务"
        running={busy === '停止后端服务'}
        onPress={() => run('停止后端服务', () => Backend.stopService())}
      />
      <ActionCard
        title="查询后端状态"
        running={busy === '查询状态'}
        onPress={refreshState}
      />
      {parsed ? (
        <View style={styles.statusBox}>
          <Text style={styles.statusTitle}>backend</Text>
          <Text style={styles.statusText} selectable>{JSON.stringify(parsed.backend ?? null, null, 2)}</Text>
        </View>
      ) : (
        <Text style={styles.hint}>点「查询后端状态」查看 running / address / error。</Text>
      )}
    </ScrollView>
  );

  const renderLog = () => (
    <View style={styles.screen}>
      <View style={styles.logBar}>
        <TouchableOpacity style={styles.smallBtn} onPress={refreshState}>
          <Text>查询状态</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.smallBtn} onPress={() => setLog([])}>
          <Text>清空日志</Text>
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

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => setDrawerOpen(true)} style={styles.iconBtn}>
          <Text style={styles.iconText}>＝</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{TITLES[route]}</Text>
        {route === 'chat' ? (
          <TouchableOpacity onPress={() => setChatMenuOpen(true)} style={styles.iconBtn}>
            <Text style={styles.iconText}>⋮</Text>
          </TouchableOpacity>
        ) : (
          <View style={styles.iconBtn} />
        )}
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
            <Text style={styles.drawerFoot}>薄 Loader → 可更新核心</Text>
          </Pressable>
        </Pressable>
      </Modal>

      <Modal visible={chatMenuOpen} transparent animationType="fade" onRequestClose={() => setChatMenuOpen(false)}>
        <Pressable style={styles.menuMask} onPress={() => setChatMenuOpen(false)}>
          <Pressable style={styles.menu} onPress={e => e.stopPropagation()}>
            <TouchableOpacity style={styles.menuItem} onPress={() => go('model')}>
              <Text>去模型屏</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.menuItem} onPress={() => go('core')}>
              <Text>去核心屏</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.menuItem} onPress={() => go('backend')}>
              <Text>去后端屏</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.menuItem} onPress={() => go('log')}>
              <Text>去日志屏</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.menuItem}
              onPress={() => {
                setMessages([]);
                setChatMenuOpen(false);
              }}>
              <Text>清空聊天</Text>
            </TouchableOpacity>
          </Pressable>
        </Pressable>
      </Modal>
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
  screen: {flex: 1, backgroundColor: '#ffffff'},
  screenContent: {padding: 16},
  card: {
    padding: 14,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 10,
    backgroundColor: '#f7f7f7',
  },
  cardDisabled: {opacity: 0.6},
  cardRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  cardText: {flex: 1, paddingRight: 12},
  cardTitle: {fontSize: 15, color: '#111111', fontWeight: '600'},
  cardDesc: {fontSize: 12, color: '#666666', marginTop: 4},
  hint: {fontSize: 13, color: '#666666', lineHeight: 20},
  statusBox: {marginTop: 8, padding: 12, borderWidth: 1, borderColor: '#e0e0e0', borderRadius: 10},
  statusTitle: {fontSize: 13, fontWeight: 'bold', color: '#333333', marginTop: 8},
  statusText: {fontSize: 12, color: '#333333', marginTop: 4},
  chatList: {flex: 1},
  chatListContent: {padding: 16},
  bubble: {padding: 10, borderRadius: 10, marginBottom: 8, maxWidth: '85%', alignSelf: 'flex-start'},
  bubbleAi: {backgroundColor: '#f1f1f1', alignSelf: 'flex-start'},
  bubbleUser: {backgroundColor: '#2563eb', alignSelf: 'flex-end'},
  bubbleError: {backgroundColor: '#fdecea', alignSelf: 'flex-start', borderWidth: 1, borderColor: '#b00020'},
  bubbleAiText: {color: '#111111'},
  bubbleUserText: {color: '#ffffff'},
  inputBar: {flexDirection: 'row', padding: 10, borderTopWidth: 1, borderTopColor: '#e5e5e5', alignItems: 'flex-end'},
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 18,
    paddingHorizontal: 14,
    paddingVertical: 8,
    maxHeight: 110,
    color: '#111111',
  },
  sendBtn: {marginLeft: 8, backgroundColor: '#2563eb', borderRadius: 18, paddingHorizontal: 16, paddingVertical: 10},
  sendBtnDisabled: {opacity: 0.4},
  sendText: {color: '#ffffff', fontWeight: '600'},
  logBar: {flexDirection: 'row', padding: 12, borderBottomWidth: 1, borderBottomColor: '#e5e5e5'},
  smallBtn: {paddingHorizontal: 12, paddingVertical: 8, borderWidth: 1, borderColor: '#dddddd', borderRadius: 8, marginRight: 8},
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
  drawerFoot: {marginTop: 24, marginLeft: 8, fontSize: 12, color: '#999999'},
  menuMask: {flex: 1, backgroundColor: 'rgba(0,0,0,0.15)'},
  menu: {position: 'absolute', top: 92, right: 8, backgroundColor: '#ffffff', borderRadius: 10, borderWidth: 1, borderColor: '#e0e0e0', minWidth: 170, paddingVertical: 6, elevation: 4},
  menuItem: {paddingVertical: 12, paddingHorizontal: 16},
});
