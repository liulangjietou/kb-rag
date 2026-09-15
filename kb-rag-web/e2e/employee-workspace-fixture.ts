import type { BrowserContext, Page, Route } from '@playwright/test';
import type { EmployeeConversation, EmployeeRun } from '../src/api/employeeWorkspace';
import { app } from './fixtures';

export const employeeConversation: EmployeeConversation = { conversation_id: 'conv_fixture', app_id: app.app_id,
  title: '售后政策与处理流程', last_turn: 1, active_run_id: null, last_activity_at: '2026-09-11T10:00:00', created_at: '2026-09-11T10:00:00' };
export const employeeRun: EmployeeRun = { run_id: 'run_fixture', conversation_id: employeeConversation.conversation_id,
  turn_no: 1, question: '客户申请退货时，客服需要先确认哪些信息？',
  answer: '先核对订单状态和商品签收日期，再按售后政策处理。具体步骤见 [1]。\n\n| 核对项目 | 处理方式 |\n| --- | --- |\n| 签收日期 | 核对申请期限 |\n| 商品状态 | 确认是否影响二次销售 |\n\n```java\nString status = "待审核";\n```\n\n如果情况不明确，请联系售后负责人核实。',
  references: [{ doc_id: 'doc_fixture', document_version_id: 'dv3', chunk_id: 'chunk_1', kb_id: 'kb_fixture',
    file_name: '售后服务政策.docx', document_version: 'v3', document_updated_at: '2026-09-10T09:30:00',
    chunk_title: '退货申请与核验', page_no: null, chunk_ordinal: 3, content: '受理退货申请前，应核验订单、签收日期及商品状态。资料不足时请补充信息后转交售后负责人。', inherited: false },
  { doc_id: 'doc_older', document_version_id: 'dv2', chunk_id: 'chunk_old', kb_id: 'kb_fixture', file_name: '客服工作手册.pdf',
    document_version: 'v2', page_no: 8, content: '会话前文读取的服务分工。', inherited: true }],
  status: 'SUCCEEDED', stage: 'FINISHED', app_version_id: 'v13', app_version: 'v1.3', snapshot_bound: true,
  revision: 5, checkpoint_seq: 3, degraded: false, restricted: false, created_at: '2026-09-11T10:00:00' };

/** 只接管隔离浏览器中的员工 API；每项写入都有显式实现，未声明请求交给基础夹具报错。 */
export async function employeeFixture(page: Page | BrowserContext, initial: { runs?: EmployeeRun[]; conversation?: EmployeeConversation } = {}) {
  const state = { conversation: structuredClone(initial.conversation ?? employeeConversation),
    runs: structuredClone(initial.runs ?? [employeeRun]),
    submissions: [] as { request_id: string; query: string }[], stops: [] as string[], streams: 0,
    summaryReads: 0, historyReads: 0, renameFails: false, deleted: false, created: 0,
    submitUnavailable: false, streamComplete: true, revokeOnRead: false,
    historyBefore: [] as number[], searchQueries: [] as string[],
    onEvents: undefined as undefined | ((route: Route) => Promise<void>),
  };
  const ok = (route: Route, data: unknown) => route.fulfill({ json: { code: 'OK', message: 'success', data } });
  const base = '/api/v1/workspace/apps/app_fixture/conversations';
  await page.route('**/api/v1/workspace/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (path === '/api/v1/workspace/apps' && method === 'GET') return ok(route, [app]);
    if (path === base && method === 'GET') {
      state.searchQueries.push(url.searchParams.get('keyword') ?? '');
      return ok(route, { items: state.deleted ? [] : [state.conversation], total: state.deleted ? 0 : 1, page: 1, size: 20 });
    }
    if (path === base && method === 'POST') {
      state.created++;
      state.conversation = { ...state.conversation, title: request.postDataJSON().title, last_turn: 0, active_run_id: null };
      state.runs = [];
      return ok(route, state.conversation);
    }
    if (path === `${base}/conv_fixture`) {
      if (method === 'GET') { state.summaryReads++; return ok(route, state.conversation); }
      if (method === 'PATCH') {
        if (state.renameFails) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '标题保存失败，请重试' } });
        state.conversation.title = request.postDataJSON().title;
        return ok(route, state.conversation);
      }
      if (method === 'DELETE') { state.deleted = true; return ok(route, null); }
    }
    if (path === `${base}/conv_fixture/runs`) {
      if (method === 'GET') {
        state.historyReads++;
        const before = Number(url.searchParams.get('before_turn'));
        state.historyBefore.push(before);
        const runs = state.runs.filter((run) => run.turn_no < before).slice(-20);
        return ok(route, state.revokeOnRead ? runs.map((run) => ({ ...run, answer: '', references: [], restricted: true })) : runs);
      }
      if (method === 'POST') {
        const command = request.postDataJSON();
        state.submissions.push(command);
        if (state.submitUnavailable) return route.abort('failed');
        let run = state.runs.find((item) => item.run_id === `run_${command.request_id}`);
        if (!run) {
          if (state.conversation.active_run_id) return route.fulfill({ status: 409,
            json: { code: 'CONVERSATION_BUSY', message: '上一条回答仍在执行，请等待或停止后继续' } });
          run = { ...employeeRun, question: command.query, answer: '', references: [], run_id: `run_${command.request_id}`,
            turn_no: state.conversation.last_turn + 1, status: 'RUNNING', stage: 'RETRIEVING', revision: 1 };
          state.runs.push(run);
          state.conversation.last_turn = run.turn_no;
          state.conversation.active_run_id = run.run_id;
        }
        return ok(route, run);
      }
    }
    const eventMatch = path.match(/\/runs\/([^/]+)\/events$/);
    if (eventMatch && method === 'GET') {
      state.streams++;
      if (state.onEvents) return state.onEvents(route);
      let run = state.runs.find((item) => item.run_id === eventMatch[1]);
      if (!run) return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '运行不存在' } });
      if (state.streamComplete && ['RUNNING', 'PENDING'].includes(run.status)) {
        const completed: EmployeeRun = { ...run, answer: employeeRun.answer, references: employeeRun.references, status: 'SUCCEEDED', stage: 'FINISHED', revision: 5 };
        state.runs = state.runs.map((item) => item.run_id === completed.run_id ? completed : item);
        run = completed;
        state.conversation.active_run_id = null;
      }
      return route.fulfill({ contentType: 'text/event-stream', body: `event: snapshot\ndata: ${JSON.stringify(run)}\n\n` });
    }
    const stopMatch = path.match(/\/runs\/([^/]+)\/stop$/);
    if (stopMatch && method === 'POST') {
      state.stops.push(stopMatch[1]);
      const stopped = { ...state.runs.find((item) => item.run_id === stopMatch[1])!, status: 'CANCELLED' as const, stage: 'FINISHED' as const, revision: 6 };
      state.runs = state.runs.map((item) => item.run_id === stopped.run_id ? stopped : item);
      state.conversation.active_run_id = null;
      return ok(route, stopped);
    }
    return route.fallback();
  });
  return state;
}

export const employeeUrl = '/workspace?app=app_fixture&conversation=conv_fixture';
