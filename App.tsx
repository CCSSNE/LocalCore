import React, {useEffect, useState} from 'react';
import {NativeModules, ScrollView, Text, TouchableOpacity} from 'react-native';
import {startRuntimeService} from './RuntimeService';

const {Backend} = NativeModules;

type LogLine = {kind: 'info' | 'ok' | 'fail'; text: string};

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

export default function App() {
  const [log, setLog] = useState<LogLine[]>([]);
  const [busy, setBusy] = useState(false);

  const push = (kind: LogLine['kind'], text: string) =>
    setLog(prev => [...prev, {kind, text}]);

  const run = (label: string, action: () => Promise<any>) => {
    if (busy) return;
    setBusy(true);
    push('info', '>> ' + label);
    action()
      .then((value: any) =>
        push('ok', 'OK ' + label + (value ? ' => ' + String(value).slice(0, 400) : '')))
      .catch((error: Error) => push('fail', 'FAIL ' + label + ' => ' + error.message))
      .finally(() => setBusy(false));
  };

  useEffect(() => {
    try {
      startRuntimeService();
      push('info', '运行时桥已就绪');
    } catch (error) {
      push('fail', '运行时桥启动失败: ' + String(error));
    }
  }, []);

  const actions: Array<{label: string; run: () => Promise<any>}> = [
    {
      label: '导入核心（选择 llama-rn-android-jni-libs.tar.gz）',
      run: pickAnd('导入核心', uri => Backend.importCore(uri)),
    },
    {
      label: '导入模型（选择 .gguf）',
      run: pickAnd('导入模型', uri => Backend.importModel(uri)),
    },
    {
      label: '加载最新模型',
      run: async () => {
        const id = await firstModelId();
        return Backend.loadModel(id);
      },
    },
    {
      label: '测试推理（你好）',
      run: async () => {
        const id = await firstModelId();
        return Backend.testChat(id, '你好');
      },
    },
    {
      label: '查询状态',
      run: () => Backend.getBackendState(),
    },
    {
      label: '启动后端服务',
      run: () => Backend.startService(),
    },
    {
      label: '停止后端服务',
      run: () => Backend.stopService(),
    },
  ];

  return (
    <ScrollView style={{flex: 1, backgroundColor: '#ffffff', padding: 16, paddingTop: 40}}>
      <Text style={{fontSize: 20, fontWeight: 'bold', marginBottom: 12, color: '#111111'}}>
        LocalCore 控制台
      </Text>
      {actions.map(action => (
        <TouchableOpacity
          key={action.label}
          onPress={() => run(action.label, action.run)}
          style={{
            padding: 12,
            marginBottom: 8,
            borderWidth: 1,
            borderColor: '#cccccc',
            borderRadius: 6,
            backgroundColor: '#f7f7f7',
          }}>
          <Text style={{color: '#111111'}}>{action.label}</Text>
        </TouchableOpacity>
      ))}
      <Text style={{fontSize: 16, fontWeight: 'bold', marginTop: 8, marginBottom: 4, color: '#111111'}}>
        日志
      </Text>
      {log.map((line, index) => (
        <Text
          key={index}
          style={{color: line.kind === 'fail' ? '#b00020' : line.kind === 'ok' ? '#1b5e20' : '#333333'}}>
          {line.text}
        </Text>
      ))}
    </ScrollView>
  );
}
