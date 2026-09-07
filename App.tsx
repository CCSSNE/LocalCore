import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
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
type ChatMsg = {role: 'user' | 'ai' | 'error'; text: string; imageUri?: string | null};
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

export default function App() {
  const [route, setRoute] = useState<RouteKey>('chat');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [chatMenuOpen, setChatMenuOpen] = useState(false);
  const [log, setLog] = useState<LogLine[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [draft, setDraft] = useState('');
  const [pendingImage, setPendingImage] = useState<string | null>(null);
  const [modelList, setModelList] = useState<ModelEntry[] | null>(null);
  const [modelError, setModelError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [loadedId, setLoadedId] = useState<string | null>(null);
  const [coreInfo, setCoreInfo] = useState<{id: string; version: string} | null>(null);
  const [coreError, setCoreError] = useState<string | null>(null);
  const [coreLoading, setCoreLoading] = useState(false);
  const [backendInfo, setBackendInfo] = useState<{running: boolean; address: string | null; error: string | null} | null>(null);
  const [backendError, setBackendError] = useState<string | null>(null);
  const [backendLoading, setBackendLoading] = useState(false);
  const chatScroll = useRef<ScrollView | null>(null);
  const logScroll = useRef<ScrollView | null>(null);

  const push = (kind: LogLine['kind'], text: string) =>
    setLog(prev => [...prev, {kind, text}]);

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

  const fetchModels = async () => {
    setListLoading(true);
    try {
      const value = String(await Backend.getBackendState());
      const root = JSON.parse(value);
      setModelList(parseModels(root));
      setLoadedId(root?.runtime?.modelId ?? null);
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
      setCoreError(null);
    } catch (error: any) {
      const message = error?.message ?? String(error);
      setCoreError(message);
      push('fail', 'FAIL 刷新核心 => ' + message);
    } finally {
      setCoreLoading(false);
    }
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
    if (route === 'model') {
      fetchModels();
    }
    if (route === 'core') {
      fetchCore();
    }
    if (route === 'backend') {
      fetchBackend();
    }
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
    });

  const startBackend = () =>
    run('启动后端服务', () => Backend.startService(), () => {
      fetchBackend();
    });

  const stopBackend = () =>
    run('停止后端服务', () => Backend.stopService(), () => {
      fetchBackend();
    });

  const pickImage = () =>
    run(
      '选择图片',
      pickAnd('选择图片', async uri => {
        setPendingImage(uri);
        return uri;
      }),
    );

  const sendChat = () => {
    const prompt = draft.trim();
    if ((!prompt && !pendingImage) || busy) return;
    const image = pendingImage;
    setDraft('');
    setMessages(prev => [...prev, {role: 'user', text: prompt, imageUri: image}]);
    const label = '聊天推理';
    setBusy(label);
    push('info', '>> ' + label + '：' + prompt + (image ? ' [图片]' : ''));
    (async () => {
      const id = await firstModelId();
      if (image) return Backend.testChatWithImage(id, prompt, image);
      return Backend.testChat(id, prompt);
    })()
      .then((value: any) => {
        const text = String(value ?? '');
        setMessages(prev => [...prev, {role: 'ai', text}]);
        if (image) setPendingImage(null);
        push('ok', 'OK ' + label + ' => ' + text);
      })
      .catch((error: Error) => {
        setMessages(prev => [...prev, {role: 'error', text: error.message}]);
        push('fail', 'FAIL ' + label + ' => ' + error.message);
      })
      .finally(() => setBusy(null));
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

  const renderChat = () => (
    <View style={styles.screen}>
      <ScrollView
        ref={chatScroll}
        style={styles.chatList}
        contentContainerStyle={styles.chatListContent}
        onContentSizeChange={() => chatScroll.current?.scrollToEnd({animated: true})}>
        {messages.map((m, i) => (
          <React.Fragment key={i}>
            {m.text !== '' ? (
              <View
                style={[
                  styles.bubble,
                  m.role === 'user' ? styles.bubbleUser : m.role === 'error' ? styles.bubbleError : styles.bubbleAi,
                ]}>
                <Text style={m.role === 'user' ? styles.bubbleUserText : styles.bubbleAiText} selectable>
                  {m.text}
                </Text>
              </View>
            ) : null}
            {m.imageUri ? (
              <Image source={{uri: m.imageUri}} style={styles.thumb} resizeMode="cover" />
            ) : null}
          </React.Fragment>
        ))}
        {busy === '聊天推理' ? (
          <View style={[styles.bubble, styles.bubbleAi]}>
            <ActivityIndicator />
            <Text style={styles.bubbleAiText}>正在推理…</Text>
          </View>
        ) : null}
      </ScrollView>
      {pendingImage !== null ? (
        <View style={styles.pendingBar}>
          <Text style={styles.pendingText} numberOfLines={1}>
            已选图片：{pendingImage}
          </Text>
          <TouchableOpacity onPress={() => setPendingImage(null)} style={styles.pendingRemove}>
            <Text>移除</Text>
          </TouchableOpacity>
        </View>
      ) : null}
      <View style={styles.inputBar}>
        <TouchableOpacity onPress={pickImage} disabled={!!busy} style={styles.iconBtn}>
          <ImageIcon />
        </TouchableOpacity>
        <TextInput
          style={styles.input}
          value={draft}
          onChangeText={setDraft}
          placeholder="输入消息…"
          placeholderTextColor="#999999"
          multiline
        />
        <TouchableOpacity
          style={[styles.sendBtn, ((!draft.trim() && !pendingImage) || busy) && styles.sendBtnDisabled]}
          onPress={sendChat}
          disabled={(!draft.trim() && !pendingImage) || !!busy}>
          <Text style={styles.sendText}>发送</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

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
        {coreInfo !== null ? (
          <View style={styles.card}>
            <View style={styles.cardTitleRow}>
              <Text style={styles.cardTitle} numberOfLines={1}>
                {coreInfo.id}
              </Text>
              <Text style={styles.tagLoaded}>已激活</Text>
            </View>
            <Text style={styles.hint}>版本 {coreInfo.version}</Text>
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
            {loadedId === model.id ? <Text style={styles.tagLoaded}>已加载</Text> : null}
          </View>
          <View style={styles.rowBtns}>
            <TouchableOpacity
              style={styles.btn}
              disabled={!!busy}
              onPress={() =>
                run('加载模型', () => Backend.loadModel(model.id), () => {
                  fetchModels();
                })
              }>
              <Text>加载</Text>
            </TouchableOpacity>
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
              style={[styles.btn, styles.btnLast]}
              disabled={!!busy}
              onPress={() => confirmDelete(model)}>
              <Text>删除</Text>
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
        <TouchableOpacity onPress={importModel} style={styles.headerAction}>
          <Text style={styles.headerActionText}>导入</Text>
        </TouchableOpacity>
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
            <Text style={styles.drawerFoot}>薄 Loader → 可更新核心</Text>
          </Pressable>
        </Pressable>
      </Modal>

      <Modal visible={chatMenuOpen} transparent animationType="fade" onRequestClose={() => setChatMenuOpen(false)}>
        <Pressable style={styles.menuMask} onPress={() => setChatMenuOpen(false)}>
          <Pressable style={styles.menu} onPress={e => e.stopPropagation()}>
            <TouchableOpacity
              style={styles.menuItem}
              onPress={() => {
                setMessages([]);
                setChatMenuOpen(false);
              }}>
              <Text>清空聊天记录</Text>
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
  imgIcon: {width: 26, height: 22, borderWidth: 1.5, borderColor: '#666666', borderRadius: 4},
  imgSun: {
    position: 'absolute',
    top: 3,
    left: 4,
    width: 5,
    height: 5,
    borderRadius: 2.5,
    borderWidth: 1.2,
    borderColor: '#666666',
  },
  imgPeakLeft: {
    position: 'absolute',
    left: 2,
    bottom: 3,
    width: 10,
    height: 2,
    borderRadius: 1,
    backgroundColor: '#666666',
    transform: [{rotate: '35deg'}],
  },
  imgPeakRight: {
    position: 'absolute',
    right: 2,
    bottom: 3,
    width: 10,
    height: 2,
    borderRadius: 1,
    backgroundColor: '#666666',
    transform: [{rotate: '-35deg'}],
  },
  headerAction: {minWidth: 44, height: 44, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 8},
  headerActionText: {fontSize: 15, color: '#1a3faa'},
  screen: {flex: 1, backgroundColor: '#ffffff'},
  screenContent: {padding: 16},
  centerBox: {alignItems: 'center', paddingVertical: 24},
  hint: {fontSize: 13, color: '#666666', lineHeight: 20},
  actionBar: {flexDirection: 'row', padding: 12, borderBottomWidth: 1, borderBottomColor: '#e5e5e5'},
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
  thumb: {width: 120, height: 120, borderRadius: 10, marginBottom: 8, alignSelf: 'flex-end'},
  inputBar: {flexDirection: 'row', padding: 10, paddingLeft: 2, borderTopWidth: 1, borderTopColor: '#e5e5e5', alignItems: 'flex-end'},
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
  pendingRemove: {paddingHorizontal: 8, paddingVertical: 4},
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#dddddd',
    borderRadius: 18,
    paddingHorizontal: 14,
    paddingVertical: 8,
    maxHeight: 110,
    color: '#111111',
    textAlignVertical: 'center',
  },
  sendBtn: {marginLeft: 8, backgroundColor: '#2563eb', borderRadius: 18, paddingHorizontal: 16, paddingVertical: 10},
  sendBtnDisabled: {opacity: 0.4},
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
  drawerFoot: {marginTop: 24, marginLeft: 8, fontSize: 12, color: '#999999'},
  menuMask: {flex: 1, backgroundColor: 'transparent'},
  menu: {position: 'absolute', top: 92, right: 8, backgroundColor: '#ffffff', borderRadius: 10, borderWidth: 1, borderColor: '#e0e0e0', minWidth: 170, paddingVertical: 6, elevation: 4},
  menuItem: {paddingVertical: 12, paddingHorizontal: 16},
});
