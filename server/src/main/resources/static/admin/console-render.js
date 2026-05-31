/*
 * v1.70.0 — 콘솔 stream-json 이벤트를 사용자 친화적 메시지로 변환하는 공용 렌더러.
 *
 * 메인 프로젝트 콘솔(WebProjectTemplates) · /chat(__scratch__) · sub-agent 콘솔
 * (SubAgentRoutes) 이 모두 같은 로직을 쓰도록 추출. 순수 함수만 노출하고 DOM/상태는
 * 각 콘솔의 inline 스크립트가 담당한다.
 *
 * 노출: window.VibeConsole = { renderToolUse, extractToolResult, renderUnknown,
 *                              summarizeInput, clip }
 *
 * 이 스크립트는 콘솔 inline <script> 보다 먼저(동기 로드) 포함되어야 한다.
 */
(function () {
  'use strict';

  function clip(s, n) {
    s = String(s == null ? '' : s);
    return s.length > n ? s.slice(0, n) + ' …(+' + (s.length - n) + ')' : s;
  }

  // tool input object → 짧은 "key=value" 요약 (raw JSON dump 회피).
  function summarizeInput(i) {
    if (i == null) return '';
    if (typeof i === 'string') return clip(i, 300);
    if (Array.isArray(i)) return '[' + i.length + ' item(s)]';
    if (typeof i !== 'object') return String(i);
    var keys = Object.keys(i);
    if (keys.length === 0) return '';
    var parts = [];
    for (var k = 0; k < keys.length && parts.length < 6; k++) {
      var key = keys[k], v = i[key], vs;
      if (v == null) vs = '';
      else if (typeof v === 'string') vs = clip(v, 80);
      else if (Array.isArray(v)) vs = '[' + v.length + ']';
      else if (typeof v === 'object') vs = '{…}';
      else vs = String(v);
      parts.push(key + '=' + vs);
    }
    return parts.join('  ');
  }

  // tool_use(name, input) → { label, body } 한 줄 친화 표현.
  function renderToolUse(name, input) {
    var i = input || {};
    if (typeof i === 'string') { try { i = JSON.parse(i); } catch (e) { return { label: name || 'tool', body: clip(input, 500) }; } }
    switch (name) {
      case 'Bash': {
        var cmd = i.command || '';
        var desc = i.description ? ' — ' + i.description : '';
        return { label: '$', body: clip(cmd, 400) + desc };
      }
      case 'Read': {
        var p = i.file_path || i.path || '';
        var range = (i.offset != null || i.limit != null)
          ? ' [' + (i.offset || 0) + ', +' + (i.limit || '?') + ']' : '';
        return { label: '📄 Read', body: p + range };
      }
      case 'Write': {
        var p2 = i.file_path || i.path || '';
        var sz = (i.content || '').length;
        return { label: '✏️ Write', body: p2 + ' (' + sz + ' chars)' };
      }
      case 'Edit': {
        var p3 = i.file_path || i.path || '';
        var oldS = clip(i.old_string || '', 80);
        var newS = clip(i.new_string || '', 80);
        var ra = i.replace_all ? ' [all]' : '';
        return { label: '✎ Edit' + ra, body: p3 + '\n  - ' + oldS + '\n  + ' + newS };
      }
      case 'Glob':
        return { label: '🔍 Glob', body: (i.pattern || '') + (i.path ? ' in ' + i.path : '') };
      case 'Grep':
        return { label: '🔎 Grep', body: '"' + clip(i.pattern || '', 80) + '"' +
          (i.path ? ' in ' + i.path : '') + (i.glob ? ' (' + i.glob + ')' : '') };
      case 'MultiEdit': {
        var pm = i.file_path || i.path || '';
        var ne = (i.edits || []).length;
        return { label: '✎ MultiEdit', body: pm + ' (' + ne + ' edit(s))' };
      }
      case 'NotebookEdit':
        return { label: '✎ Notebook', body: (i.notebook_path || '') + (i.cell_type ? ' [' + i.cell_type + ']' : '') };
      case 'TaskCreate':
        return { label: '📋 TaskCreate', body: i.subject || i.description || '' };
      case 'TaskUpdate':
        return { label: '📋 TaskUpdate',
          body: 'id=' + (i.taskId || '?') + (i.status ? ' status=' + i.status : '') +
                (i.subject ? ' "' + i.subject + '"' : '') };
      case 'TaskList':
        return { label: '📋 TaskList', body: i.status ? 'status=' + i.status : '' };
      case 'TaskGet':
        return { label: '📋 TaskGet', body: 'id=' + (i.taskId || '?') };
      case 'TaskOutput':
        return { label: '📋 TaskOutput', body: 'id=' + (i.taskId || '?') };
      case 'TaskStop':
        return { label: '📋 TaskStop', body: 'id=' + (i.taskId || '?') };
      case 'TodoWrite': {
        var n = (i.todos || []).length;
        return { label: '📋 TodoWrite', body: n + ' todo(s)' };
      }
      case 'Task':
        return { label: '🤖 Task' + (i.subagent_type ? '·' + i.subagent_type : ''),
                 body: clip(i.description || i.prompt || '', 300) };
      case 'Agent':
        return { label: '🤖 Agent' + (i.subagent_type ? '·' + i.subagent_type : ''),
                 body: clip(i.description || i.prompt || '', 300) };
      case 'WebSearch':
        return { label: '🌐 WebSearch', body: '"' + clip(i.query || '', 200) + '"' };
      case 'WebFetch':
        return { label: '🌐 WebFetch', body: i.url || '' };
      case 'ToolSearch':
        return { label: '🔧 ToolSearch', body: clip(i.query || '', 200) };
      case 'Monitor':
        return { label: '⏱ Monitor', body: clip(i.command || i.description || summarizeInput(i), 200) };
      case 'ScheduleWakeup':
        return { label: '⏰ Schedule',
                 body: (i.delaySeconds != null ? '+' + i.delaySeconds + 's  ' : '') + clip(i.reason || '', 200) };
      case 'BashOutput':
        return { label: '$ output', body: 'bash_id=' + (i.bash_id || i.shell_id || '?') };
      case 'KillShell':
      case 'KillBash':
        return { label: '✖ kill shell', body: 'id=' + (i.shell_id || i.bash_id || '?') };
      case 'SlashCommand':
        return { label: '/ command', body: clip(i.command || '', 200) };
      case 'ExitPlanMode':
      case 'EnterPlanMode':
        return { label: '📝 ' + name, body: clip(i.plan || '', 300) };
      case 'AskUserQuestion': {
        var q = (i.questions && i.questions[0] && i.questions[0].question) || '';
        return { label: '❓ Question', body: clip(q, 200) };
      }
      case 'PushNotification':
        return { label: '🔔 Notify', body: clip(i.message || i.title || '', 200) };
      case 'Skill':
        return { label: '🧩 Skill', body: (i.skill || '') + (i.args ? ' ' + clip(i.args, 120) : '') };
      case 'Workflow':
        return { label: '🔀 Workflow', body: clip(i.description || i.name || '', 200) };
      default: {
        // MCP 도구: mcp__<server>__<tool> → "🔌 server·tool"
        if (name && name.indexOf('mcp__') === 0) {
          var parts = name.split('__');
          var server = parts[1] || '';
          var tool = parts.slice(2).join('__');
          return { label: '🔌 ' + server + (tool ? '·' + tool : ''), body: summarizeInput(i) };
        }
        return { label: name || 'tool', body: summarizeInput(i) };
      }
    }
  }

  // tool_result content(string | array[{type,text}] | object) → 사람이 읽는 텍스트.
  function extractToolResult(output) {
    if (output == null) return '';
    if (typeof output === 'string') return output;
    if (Array.isArray(output)) {
      var parts = [];
      for (var j = 0; j < output.length; j++) {
        var b = output[j];
        if (b == null) continue;
        if (typeof b === 'string') { parts.push(b); continue; }
        if (b.type === 'text' && typeof b.text === 'string') parts.push(b.text);
        else if (b.type === 'image') parts.push('[image]');
        else if (typeof b.text === 'string') parts.push(b.text);
        else parts.push(summarizeInput(b));
      }
      return parts.join('\n');
    }
    if (typeof output === 'object') {
      if (typeof output.text === 'string') return output.text;
      if (typeof output.content === 'string') return output.content;
      return summarizeInput(output);
    }
    return String(output);
  }

  function fmtEpoch(sec) {
    var n = Number(sec);
    if (!isFinite(n)) return String(sec);
    try { return new Date(n * 1000).toLocaleString(); } catch (e) { return String(sec); }
  }

  // console_unknown.raw (object 또는 JSON string) → { cls, label, body, cat } 또는 null(숨김).
  function renderUnknown(raw) {
    var o = raw;
    if (typeof o === 'string') {
      try { o = JSON.parse(o); } catch (e) { return { cls: 'sys', label: 'event', body: clip(o, 300), cat: 'system' }; }
    }
    if (o == null || typeof o !== 'object') return null;
    var type = o.type;

    if (type === 'thinking') {
      var th = String(o.thinking || '').trim();
      if (!th) return null; // signature-only redacted thinking → 노이즈, 숨김
      return { cls: 'thinking', label: '💭 thinking', body: clip(th, 4000), cat: 'thinking' };
    }

    if (type === 'system') {
      var st = o.subtype;
      if (st === 'init' || st === 'thinking_tokens') return null; // init 은 session_started 로 처리됨 / token 추정치는 노이즈
      if (st === 'task_started')
        return { cls: 'tool', label: '🟢 task', body: clip(o.description || '', 200) + (o.task_type ? ' [' + o.task_type + ']' : ''), cat: 'todo' };
      if (st === 'task_notification') {
        var done = o.status === 'completed';
        return { cls: done ? 'tool-out' : 'tool', label: done ? '✓ task' : 'task·' + (o.status || ''),
                 body: clip(o.summary || o.output_file || '', 300), cat: 'todo' };
      }
      if (st === 'task_updated')
        return { cls: 'tool', label: '… task', body: clip(o.summary || ('status=' + (o.status || '')), 200), cat: 'todo' };
      return { cls: 'sys', label: 'system·' + (st || '?'), body: summarizeInput(o), cat: 'system' };
    }

    if (type === 'rate_limit_event') {
      var info = o.rate_limit_info || {};
      var body = (info.status || '') +
                 (info.rateLimitType ? ' · ' + info.rateLimitType : '') +
                 (info.resetsAt ? ' · resets ' + fmtEpoch(info.resetsAt) : '');
      return { cls: 'sys', label: '⏳ rate limit', body: body || summarizeInput(info), cat: 'system' };
    }

    // 알 수 없는 top-level type → raw JSON 대신 요약.
    return { cls: 'sys', label: type || 'event', body: summarizeInput(o), cat: 'system' };
  }

  window.VibeConsole = {
    clip: clip,
    summarizeInput: summarizeInput,
    renderToolUse: renderToolUse,
    extractToolResult: extractToolResult,
    renderUnknown: renderUnknown,
  };
})();
