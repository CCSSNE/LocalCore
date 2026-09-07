import React, {useEffect, useState} from 'react';
import {NativeModules, SafeAreaView, Text, View} from 'react-native';
import {startRuntimeService} from './RuntimeService';

const {Backend} = NativeModules;

export default function App() {
  const [log, setLog] = useState<string[]>([]);

  const push = (line: string) => setLog(prev => [...prev, line]);
  const run = (label: string, action: () => Promise<any>) => {
    push('>> ' + label);
    action()
      .then((value: any) => push('OK ' + label + (value ? ' => ' + String(value).slice(0, 300) : '')))
      .catch((error: Error) => push('FAIL ' + label + ' => ' + error.message));
  };

  const fullRegression = () => {
    run('导入核心', () => Backend.importCore('/sdcard/Android/data/com.localcore/files/llama-rn-android-jni-libs.tar.gz'));
    run('导入模型', () => Backend.importModel('/sdcard/Android/data/com.localcore/files/moe_shakespeare15M.gguf'));
    setTimeout(() => {
      run('加载模型', async () => {
        const state = JSON.parse(await Backend.getBackendState());
        const models = JSON.parse(state.config).models as {id: string}[];
        if (!models.length) throw new Error('无已导入模型');
        return Backend.loadModel(models[models.length - 1].id);
      });
      setTimeout(() => {
        run('测试推理', async () => {
          const state = JSON.parse(await Backend.getBackendState());
          const models = JSON.parse(state.config).models as {id: string}[];
          return Backend.testChat(models[models.length - 1].id, '你好');
        });
      }, 20000);
    }, 15000);
  };

  useEffect(() => {
    try {
      startRuntimeService();
      push('运行时服务已启动');
    } catch (error) {
      push('运行时服务启动失败: ' + String(error));
    }
  }, []);

  return (
    <SafeAreaView>
      <View>
        <Text>LocalCore 回归台</Text>
        <Text onPress={fullRegression}>一键回归</Text>
        <Text onPress={() => run('查询状态', () => Backend.getBackendState())}>查询状态</Text>
        {log.map((line, index) => (
          <Text key={index}>{line}</Text>
        ))}
      </View>
    </SafeAreaView>
  );
}
