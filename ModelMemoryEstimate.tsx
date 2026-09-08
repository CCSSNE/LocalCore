import React, {useEffect, useRef, useState} from 'react';
import {Alert, NativeModules, StyleSheet, Text, TouchableOpacity} from 'react-native';

type Estimate = {
  version: string;
  modelBytes: number;
  contextBytes: number;
  computeBytes: number;
  mmprojBytes: number;
  totalBytes: number;
  contextSize: number;
  batchSize: number;
  microBatchSize: number;
  hasMmproj: boolean;
};

function memorySize(bytes: number): string {
  return bytes >= 1024 ** 3
    ? `${(bytes / 1024 ** 3).toFixed(2)} GiB`
    : `${(bytes / 1024 ** 2).toFixed(1)} MiB`;
}

export default function ModelMemoryEstimate({modelId, loadJson, form}: {
  modelId: string;
  loadJson: string;
  form: Record<string, string>;
}) {
  const {contextSize, batchSize, threads} = form;
  const key = JSON.stringify([modelId, loadJson, contextSize, batchSize, threads]);
  const [state, setState] = useState<{
    key: string;
    estimate?: Estimate;
    error?: string;
    running?: boolean;
  }>({key});
  const queue = useRef<Promise<void>>(Promise.resolve());

  useEffect(() => {
    let current = true;
    setState({key});
    // Debounce draft edits, then serialize previews. A superseded preview never overwrites newer input.
    const timer = setTimeout(() => {
      queue.current = queue.current.then(async () => {
        if (!current) return;
        try {
          const load = JSON.parse(loadJson);
          for (const [name, label, raw] of [
            ['contextSize', '上下文长度', contextSize],
            ['batchSize', '批大小', batchSize],
            ['threads', '线程数', threads],
          ]) {
            const value = Number(raw);
            if (raw == null || raw.trim() === '' || !Number.isSafeInteger(value)) {
              throw new Error(`${label}必须是可精确表示的整数，当前输入无法估算`);
            }
            load[name] = value;
          }
          setState({key, running: true});
          const raw = await NativeModules.Backend.estimateModelMemory(modelId, JSON.stringify(load));
          const result: Estimate = JSON.parse(raw);
          for (const name of ['modelBytes', 'contextBytes', 'computeBytes', 'mmprojBytes', 'totalBytes',
            'contextSize', 'batchSize', 'microBatchSize'] as const) {
            if (!Number.isSafeInteger(result[name]) || result[name] < 0) {
              throw new Error(`核心返回的内存估算字段无效：${name}`);
            }
          }
          if (typeof result.version !== 'string' || typeof result.hasMmproj !== 'boolean' ||
              result.totalBytes !== result.modelBytes + result.contextBytes + result.computeBytes + result.mmprojBytes) {
            throw new Error('核心返回的内存估算结构或合计无效');
          }
          if (current) setState({key, estimate: result});
        } catch (error: any) {
          if (current) setState({key, error: error?.message ?? String(error)});
        }
      });
    }, 350);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [key, modelId, loadJson, contextSize, batchSize, threads]);

  const estimate = state.key === key ? state.estimate : undefined;
  const error = state.key === key ? state.error : undefined;
  const label = estimate ? `预估内存 ≈ ${memorySize(estimate.totalBytes)}`
    : error ? '预估失败（详情）'
      : state.key === key && state.running ? '预估内存：排队/计算中…' : '预估内存：等待估算…';
  const showDetails = () => {
    if (error) {
      Alert.alert('内存估算失败', error);
    } else if (estimate) {
      Alert.alert('推理内存预估', [
        `合计：${memorySize(estimate.totalBytes)}`,
        `模型权重：${memorySize(estimate.modelBytes)}`,
        `上下文缓存（含循环状态）：${memorySize(estimate.contextBytes)}`,
        `计算缓冲：${memorySize(estimate.computeBytes)}`,
        ...(estimate.hasMmproj ? [`多模态权重及规划缓冲：${memorySize(estimate.mmprojBytes)}`] : []),
        '',
        `核心实际规划的上下文：${estimate.contextSize}`,
        `批大小：${estimate.batchSize}，微批大小：${estimate.microBatchSize}`,
        '按当前输入参数模拟分配；不是整个 APP 的实际内存，也不是运行峰值保证。',
        ...(estimate.hasMmproj ? ['多模态缓冲按模型默认图像尺寸规划；实际图片尺寸、数量及预处理会影响峰值。'] : []),
        `核心：${estimate.version}`,
      ].join('\n'));
    } else {
      Alert.alert('内存估算', '输入暂停后自动估算；估算与推理串行排队，结果只对应当前参数。');
    }
  };

  return (
    <TouchableOpacity onPress={showDetails} style={styles.action} accessibilityRole="button"
      accessibilityLabel={label} accessibilityHint="查看估算明细或失败原因">
      <Text style={[styles.text, error ? styles.error : undefined]}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  action: {flexShrink: 1, marginLeft: 8},
  text: {fontSize: 12, lineHeight: 20, color: '#666666', textAlign: 'right'},
  error: {color: '#b3261e'},
});
