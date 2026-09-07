import React, {useEffect, useState} from 'react';
import {NativeModules, SafeAreaView, Text, View} from 'react-native';
import {startRuntimeService} from './RuntimeService';

const {Backend} = NativeModules;

export default function App() {
  const [status, setStatus] = useState('初始化中');

  useEffect(() => {
    try {
      startRuntimeService();
      Backend.getBackendState()
        .then((state: string) => setStatus('后端就绪: ' + state.slice(0, 200)))
        .catch((error: Error) => setStatus('后端状态获取失败: ' + error.message));
    } catch (error) {
      setStatus('运行时服务启动失败: ' + String(error));
    }
  }, []);

  return (
    <SafeAreaView>
      <View>
        <Text>LocalCore</Text>
        <Text>{status}</Text>
      </View>
    </SafeAreaView>
  );
}
