import { Alert } from 'antd';
import type { ChatDiagnostics } from '../../api/chatDiagnostics';
import './chat-diagnostics.css';

export interface BrowserChatTiming {
  streamed: boolean;
  firstDeltaMs?: number;
  totalMs: number;
}

interface Props {
  diagnostics?: ChatDiagnostics;
  browser: BrowserChatTiming;
  stopped?: boolean;
  failed?: boolean;
}

const stageLabels = { CONFIGURATION: '配置解析', RETRIEVAL: '检索', GENERATION: '生成' };
const outcomeLabels = { SUCCEEDED: '执行完成', FAILED: '执行失败', CANCELLED: '执行停止' };

function duration(value?: number | null): string {
  return value == null ? '未采集' : `${Math.round(value).toLocaleString('zh-CN')} ms`;
}

/** 分开展示服务器执行和浏览器接收，避免将网络等待当成模型生成耗时。 */
export default function ChatDiagnosticsPanel({ diagnostics, browser, stopped, failed }: Props) {
  return <details className="chat-diagnostics">
    <summary>查看耗时诊断</summary>
    <div className="chat-diagnostics__content">
      {diagnostics ? <>
        <h2>服务端阶段</h2>
        <p>{outcomeLabels[diagnostics.outcome]}{diagnostics.failed_stage && ` · 结束于${stageLabels[diagnostics.failed_stage]}`}</p>
        <dl className="chat-diagnostics__metrics">
          <div><dt>配置解析</dt><dd>{duration(diagnostics.configuration_ms)}</dd></div>
          <div><dt>检索</dt><dd>{duration(diagnostics.retrieval_ms)}</dd></div>
          <div><dt>生成</dt><dd>{duration(diagnostics.generation_ms)}</dd></div>
          <div><dt>服务端首段等待</dt><dd>{duration(diagnostics.first_delta_ms)}</dd></div>
          <div><dt>服务端执行总耗时</dt><dd>{duration(diagnostics.total_ms)}</dd></div>
        </dl>
        <p className="chat-diagnostics__note">检索含改写、路由、召回和重排；生成含模型首段等待及服务端增量写出。失败阶段显示结束前的实际用时。</p>
      </> : <Alert type="info" showIcon message="本次未返回服务端诊断，阶段耗时未知" />}
      <h2>浏览器接收</h2>
      <p>{stopped ? '已停止接收' : failed ? '接收未完成' : '接收完成'}</p>
      <dl className="chat-diagnostics__metrics">
        <div><dt>浏览器首段等待</dt><dd>{browser.streamed ? duration(browser.firstDeltaMs) : '非流式不适用'}</dd></div>
        <div><dt>浏览器接收总耗时</dt><dd>{duration(browser.totalMs)}</dd></div>
      </dl>
      <p className="chat-diagnostics__note">浏览器用时包含网络和等待。首段等待包含在总耗时中，不能再次相加；服务端用时从预览开始执行计起，不含鉴权、排队和网络往返。</p>
      <p className="chat-diagnostics__note">耗时用于定位速度问题，不代表回答质量。费用以模型用量账本为准。</p>
    </div>
  </details>;
}
