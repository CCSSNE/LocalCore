import {NativeEventEmitter, NativeModules} from 'react-native';
// @ts-ignore llama.rn 无独立类型入口时的运行时导入
import {initLlama, isLlamaInitialized} from 'llama.rn';

const {Backend} = NativeModules;

type RuntimeRequest = {
  requestId: number;
  type: 'loadModel' | 'unload' | 'chat' | 'complete' | 'cancel';
  [key: string]: any;
};

let context: any = null;
let loadedEntry: string | null = null;

function fail(requestId: number, error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  Backend.reply(requestId, JSON.stringify({error: message}));
}

async function preloadCore(coreEntry: string) {
  console.log('preloadCore', coreEntry);
  if (loadedEntry === coreEntry && isLlamaInitialized?.()) return;
  // 抢注：先用绝对路径注册下载的核心 SONAME，
  // llama.rn 的 System.loadLibrary 随后命中已注册库，不再使用 APK 内置库。
  NativeModules.Backend.preloadCore(coreEntry);
  loadedEntry = coreEntry;
}

async function requireContext(): Promise<any> {
  if (!context) throw new Error('尚未加载模型');
  return context;
}

async function handle(request: RuntimeRequest) {
  switch (request.type) {
    case 'loadModel': {
      await preloadCore(request.coreEntry);
      if (context) {
        await context.release();
        context = null;
      }
      context = await initLlama({
        model: request.modelPath,
        n_ctx: request.contextSize,
        n_batch: request.batchSize,
        n_threads: request.threads,
        n_gpu_layers: request.gpuLayers,
      });
      Backend.reply(request.requestId, JSON.stringify({version: 'llama.rn'}));
      break;
    }
    case 'unload': {
      if (context) {
        await context.release();
        context = null;
      }
      Backend.reply(request.requestId, JSON.stringify({}));
      break;
    }
    case 'chat': {
      const ctx = await requireContext();
      const messages = request.messages ?? [];
      let prompt = request.prompt;
      if (messages.length > 0) {
        // 全能力 Jinja 模板渲染来自 llama.rn 的 JS 接口层
        prompt = ctx.formattedChat
          ? ctx.formattedChat(messages, request.chatTemplate ?? undefined)
          : messages.map((m: any) => `${m.role}: ${m.content}`).join('\n');
      }
      let text = '';
      const completion = await ctx.completion(
        {
          prompt,
          n_predict: request.max_tokens ?? 1024,
          temperature: request.temperature ?? 0.7,
          top_p: request.top_p ?? 0.95,
          top_k: request.top_k ?? 40,
          stop: request.stop ?? [],
          grammar: request.grammar || undefined,
        },
        (data: any) => {
          text += data.token;
          return true;
        },
      );
      const promptTokens = completion?.timings?.prompt_n ?? 0;
      const completionTokens = completion?.timings?.predicted_n ?? 0;
      Backend.reply(
        request.requestId,
        JSON.stringify({promptTokens, completionTokens, text, message: null}),
      );
      break;
    }
    case 'complete': {
      const ctx = await requireContext();
      let text = '';
      const completion = await ctx.completion(
        {
          prompt: request.prompt,
          n_predict: request.max_tokens ?? 1024,
          temperature: request.temperature ?? 0.7,
          top_p: request.top_p ?? 0.95,
          top_k: request.top_k ?? 40,
          stop: request.stop ?? [],
          grammar: request.grammar || undefined,
        },
        (data: any) => {
          text += data.token;
          return true;
        },
      );
      Backend.reply(
        request.requestId,
        JSON.stringify({
          promptTokens: completion?.timings?.prompt_n ?? 0,
          completionTokens: completion?.timings?.predicted_n ?? 0,
          text,
        }),
      );
      break;
    }
    case 'cancel': {
      if (context) await context.stopCompletion();
      break;
    }
    default:
      throw new Error('未知桥请求类型: ' + request.type);
  }
}

export function startRuntimeService() {
  if (!Backend) throw new Error('原生 Backend 模块不可用');
  const emitter = new NativeEventEmitter(Backend);
  emitter.addListener('LocalCoreRuntime', (payload: string) => {
    console.log('bridge request', payload?.slice(0, 120));
    const request: RuntimeRequest = JSON.parse(payload);
    handle(request).catch(error => fail(request.requestId, error));
  });
}
