package com.siamakerlab.vibecoder.server.automation

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.repo.PromptAutomationRunRow
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationMode
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetDto
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStatus
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStatusDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptDto
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptStatus

/**
 * v1.59.0 — `/projects/{id}/automation/prompts` SSR 페이지.
 *
 * 프롬프트 자동화의 설정 화면: 현재 진행 상태 + 시작/중지 + 프리셋 관리 + 최근 실행 이력.
 * 콘솔 패널이 실시간 진행을 보여주는 동안, 본 페이지는 영속 설정(프리셋)과 이력을 다룬다.
 */
internal object PromptAutomationTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        p: ProjectDto,
        presets: List<PromptAutomationPresetDto>,
        status: PromptAutomationStatusDto,
        runs: List<PromptAutomationRunRow>,
        schedules: List<ScheduledPromptDto> = emptyList(),
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
        embed: Boolean = false,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""

        // 현재/마지막 상태 카드.
        val statusCard = if (status.active) {
            """<div class="card" style="margin-bottom:14px;border-color:#1e40af;background:rgba(30,64,175,0.08)">
              <h2 style="margin-top:0">▶ 진행 중 — ${esc(status.name)}</h2>
              <p style="margin:4px 0;font-size:14px">${esc(status.mode)} · <strong>${status.sent}/${status.total}</strong> 전송됨</p>
              <form method="post" action="/projects/${esc(p.id)}/automation/prompts/stop" style="display:inline">
                ${CsrfTokens.hiddenInput(csrf)}
                <button type="submit" class="chip chip-danger" onclick="return confirm('자동화를 중지할까요?')">■ 중지</button>
              </form>
            </div>"""
        } else if (status.runId != null) {
            val badge = when (status.status) {
                PromptAutomationStatus.DONE -> "<span class=\"ok\">✓ 완료</span>"
                PromptAutomationStatus.FAILED -> "<span class=\"warn\">⚠ 실패</span>"
                PromptAutomationStatus.STOPPED -> "<span class=\"dim\">■ 중지됨</span>"
                else -> esc(status.status)
            }
            """<div class="card" style="margin-bottom:14px">
              <h2 style="margin-top:0">최근 실행</h2>
              <p style="margin:4px 0;font-size:14px">${esc(status.name)} · ${esc(status.mode)} · ${status.sent}/${status.total} · $badge</p>
            </div>"""
        } else ""

        // 프리셋 select option.
        val presetOptions = presets.joinToString("") { pr ->
            """<option value="${esc(pr.id)}">${esc(pr.name)} (${esc(pr.mode)})</option>"""
        }
        val presetSelectDisabled = if (presets.isEmpty()) " disabled" else ""

        // 프리셋 테이블.
        val presetRows = if (presets.isEmpty()) {
            """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">저장된 프리셋이 없습니다. 아래에서 추가하세요.</td></tr>"""
        } else presets.joinToString("") { pr ->
            val summary = when (pr.mode) {
                PromptAutomationMode.REPEAT -> "반복 ×${pr.repeatCount}"
                PromptAutomationMode.SEQUENCE -> "순차 ${pr.prompts.size}개 ×${pr.loops}"
                else -> esc(pr.mode)
            }
            val firstPrompt = pr.prompts.firstOrNull().orEmpty()
            """<tr>
              <td><strong>${esc(pr.name)}</strong><br><small class="dim">${esc(firstPrompt.take(60))}${if (firstPrompt.length > 60) "…" else ""}</small></td>
              <td class="dim">${esc(summary)}</td>
              <td class="dim">${if (pr.stopOnError) "에러 시 중단" else "에러 무시"}</td>
              <td style="text-align:right">
                <form method="post" action="/projects/${esc(p.id)}/automation/prompts/start" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <input type="hidden" name="presetId" value="${esc(pr.id)}">
                  <button type="submit" class="chip chip-action" style="background:#1e40af;color:#fff"${if (status.active) " disabled" else ""}>▶ 시작</button>
                </form>
                <form method="post" action="/projects/${esc(p.id)}/automation/prompts/presets/${esc(pr.id)}/delete" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('프리셋 삭제?')">삭제</button>
                </form>
              </td>
            </tr>"""
        }

        // 최근 실행 이력.
        val runRows = if (runs.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:14px">실행 이력이 없습니다.</td></tr>"""
        } else runs.joinToString("") { r ->
            val badge = when (r.status) {
                PromptAutomationStatus.RUNNING -> "<span>▶ 진행</span>"
                PromptAutomationStatus.DONE -> "<span class=\"ok\">✓ 완료</span>"
                PromptAutomationStatus.FAILED -> "<span class=\"warn\">⚠ 실패</span>"
                else -> "<span class=\"dim\">■ 중지</span>"
            }
            """<tr>
              <td>${esc(r.name)}</td>
              <td class="dim">${esc(r.mode)}</td>
              <td>${r.sent}/${r.total}</td>
              <td>$badge</td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(AdminTemplates.fmtTs(r.startedAt, lang))}</td>
            </tr>"""
        }

        // ⏰ 예약 보내기 — 목록(pending + 이력).
        val schedRows = if (schedules.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:14px">등록된 예약이 없습니다.</td></tr>"""
        } else schedules.joinToString("") { s ->
            val badge = when (s.status) {
                ScheduledPromptStatus.PENDING -> "<span style=\"color:#3b82f6\">⏳ 대기</span>"
                ScheduledPromptStatus.SENT -> "<span class=\"ok\">✓ 전송됨</span>"
                ScheduledPromptStatus.FAILED -> "<span class=\"warn\">⚠ 실패</span>"
                else -> "<span class=\"dim\">■ 취소됨</span>"
            }
            val firstLine = s.prompt.lineSequence().firstOrNull().orEmpty()
            val preview = firstLine.take(60) + if (s.prompt.length > 60 || firstLine.length < s.prompt.length) "…" else ""
            val cancelBtn = if (s.status == ScheduledPromptStatus.PENDING) {
                """<form method="post" action="/projects/${esc(p.id)}/automation/prompts/schedule/${esc(s.id)}/cancel" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('예약을 취소할까요?')">취소</button>
                </form>"""
            } else ""
            val errLine = s.lastError?.let { "<br><small class=\"warn\">${esc(it.take(80))}</small>" } ?: ""
            """<tr>
              <td><strong>${esc(s.triggerLabel ?: s.triggerType)}</strong></td>
              <td class="dim"><small>${esc(preview)}</small>$errLine</td>
              <td>$badge</td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(AdminTemplates.fmtTs(s.createdAt, lang))}</td>
              <td style="text-align:right">$cancelBtn</td>
            </tr>"""
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 프롬프트 자동화",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">프롬프트 자동화
      <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
    </h1>
    <div style="display:flex;gap:8px">
      ${AdminTemplates.backButton("/projects/${p.id}/automation", "빌드 자동화")}
      <a href="/projects/${esc(p.id)}/console" class="chip chip-link">콘솔</a>
    </div>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">작업(turn)이 완료될 때마다 다음 프롬프트를 서버가 자동 전송합니다. 브라우저를 닫아도 계속 진행됩니다.</p>
</header>

$okHtml
$errHtml
$statusCard

<div class="card" style="margin-bottom:14px;border-color:#7c3aed;background:rgba(124,58,237,0.06)">
  <h2 style="margin-top:0">⏰ 예약 보내기</h2>
  <p class="dim" style="margin:4px 0 10px;font-size:13px">지정한 시점이 되면 아래 프롬프트를 콘솔에 <strong>1회</strong> 자동 전송합니다. 발사는 콘솔이 유휴일 때 새 작업으로 시작됩니다.</p>
  <form method="post" action="/projects/${esc(p.id)}/automation/prompts/schedule">
    ${CsrfTokens.hiddenInput(csrf)}
    <textarea name="prompt" rows="3" required style="width:100%;padding:8px" placeholder="예약 시점에 보낼 프롬프트 (예: 빌드하고 결과 알려줘)"></textarea>
    <div style="display:flex;gap:16px;flex-wrap:wrap;margin:10px 0">
      <label style="margin:0"><input type="radio" name="triggerType" value="time" checked onclick="vsTrig('time')"> 시간 지정</label>
      <label style="margin:0"><input type="radio" name="triggerType" value="session_reset" onclick="vsTrig('reset')"> 세션 한도 해제 후</label>
      <label style="margin:0"><input type="radio" name="triggerType" value="weekly_reset" onclick="vsTrig('reset')"> 주간 한도 해제 후</label>
    </div>
    <div id="vs-time-opts" style="display:flex;gap:14px;flex-wrap:wrap;align-items:center">
      <label style="margin:0"><input type="radio" name="whenMode" value="in" checked onclick="vsWhen('in')"> 상대 시간</label>
      <span id="vs-in" style="display:inline-flex;gap:6px;align-items:center">
        <input name="delayAmount" type="number" min="1" max="10000" value="30" style="width:90px;padding:6px">
        <select name="delayUnit" style="padding:6px"><option value="minutes">분 뒤</option><option value="hours">시간 뒤</option></select>
      </span>
      <label style="margin:0 0 0 8px"><input type="radio" name="whenMode" value="at" onclick="vsWhen('at')"> 정확한 시각</label>
      <input id="vs-at" name="atLocal" type="datetime-local" style="display:none;padding:6px">
    </div>
    <p id="vs-reset-hint" class="dim" style="display:none;margin:8px 0 0;font-size:12px">한도가 해제되는 즉시(다음 폴링, 약 5분 해상도) 자동 전송됩니다.</p>
    <button type="submit" class="primary" style="margin-top:12px;padding:8px 16px">⏰ 예약 등록</button>
  </form>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">예약 목록</h2>
  <table style="width:100%;border-collapse:collapse">
    <thead><tr style="border-bottom:1px solid #333">
      <th style="text-align:left;padding:8px">트리거</th>
      <th style="text-align:left;padding:8px">프롬프트</th>
      <th style="text-align:left;padding:8px">상태</th>
      <th style="text-align:left;padding:8px">등록</th>
      <th></th>
    </tr></thead>
    <tbody>$schedRows</tbody>
  </table>
</div>

<script>
function vsTrig(t){
  document.getElementById('vs-time-opts').style.display=(t==='time')?'flex':'none';
  document.getElementById('vs-reset-hint').style.display=(t==='time')?'none':'block';
}
function vsWhen(m){
  document.getElementById('vs-in').style.display=(m==='in')?'inline-flex':'none';
  document.getElementById('vs-at').style.display=(m==='at')?'inline-block':'none';
}
</script>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">즉석 시작</h2>
  <form method="post" action="/projects/${esc(p.id)}/automation/prompts/start">
    ${CsrfTokens.hiddenInput(csrf)}
    <div style="display:flex;gap:14px;flex-wrap:wrap;align-items:center;margin-bottom:8px">
      <label style="margin:0"><input type="radio" name="mode" value="repeat" checked> 반복 (같은 프롬프트 N회)</label>
      <label style="margin:0"><input type="radio" name="mode" value="sequence"> 순차 (리스트 순서대로)</label>
    </div>
    <textarea name="prompts" rows="4" style="width:100%;padding:8px" required
      placeholder="반복: 한 줄. 순차: 한 줄에 하나씩.&#10;예) 앱 전체 코드를 정밀 점검하고 발견한 문제를 모두 수정해줘"></textarea>
    <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:end;margin-top:8px">
      <label style="margin:0">반복 횟수 (repeat)
        <input name="repeatCount" type="number" min="1" max="200" value="20" style="width:90px">
      </label>
      <label style="margin:0">리스트 반복 (sequence)
        <input name="loops" type="number" min="1" max="50" value="1" style="width:90px">
      </label>
      <label style="margin:0;display:flex;align-items:center;gap:4px"><input type="checkbox" name="stopOnError" value="true"> 에러 시 중단</label>
      <button type="submit" class="primary" style="padding:8px 16px"${if (status.active) " disabled title=\"이미 진행 중\"" else ""}>▶ 시작</button>
    </div>
  </form>
  ${if (presets.isNotEmpty()) """
  <hr style="margin:14px 0;border-color:#2a2a2a">
  <form method="post" action="/projects/${esc(p.id)}/automation/prompts/start" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
    ${CsrfTokens.hiddenInput(csrf)}
    <span class="dim" style="font-size:13px">프리셋으로 시작:</span>
    <select name="presetId" required$presetSelectDisabled style="padding:6px;min-width:200px">$presetOptions</select>
    <button type="submit" class="chip chip-action" style="background:#1e40af;color:#fff"${if (status.active) " disabled" else ""}>▶ 시작</button>
  </form>""" else ""}
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">프리셋 (전역 재사용)</h2>
  <table style="width:100%;border-collapse:collapse">
    <thead><tr style="border-bottom:1px solid #333">
      <th style="text-align:left;padding:8px">이름 / 첫 프롬프트</th>
      <th style="text-align:left;padding:8px">모드</th>
      <th style="text-align:left;padding:8px">에러</th>
      <th></th>
    </tr></thead>
    <tbody>$presetRows</tbody>
  </table>
  <details style="margin-top:12px">
    <summary style="cursor:pointer;font-size:13px;color:#aaa">＋ 새 프리셋 저장</summary>
    <form method="post" action="/projects/${esc(p.id)}/automation/prompts/presets" style="margin-top:10px">
      ${CsrfTokens.hiddenInput(csrf)}
      <label style="margin:0;display:block">이름
        <input name="name" required placeholder="앱 전체 정밀 리뷰" style="width:100%;padding:8px">
      </label>
      <div style="display:flex;gap:14px;flex-wrap:wrap;align-items:center;margin:8px 0">
        <label style="margin:0"><input type="radio" name="mode" value="repeat" checked> 반복</label>
        <label style="margin:0"><input type="radio" name="mode" value="sequence"> 순차</label>
        <label style="margin:0">반복 횟수<input name="repeatCount" type="number" min="1" max="200" value="20" style="width:80px;margin-left:6px"></label>
        <label style="margin:0">리스트 반복<input name="loops" type="number" min="1" max="50" value="1" style="width:80px;margin-left:6px"></label>
        <label style="margin:0;display:flex;align-items:center;gap:4px"><input type="checkbox" name="stopOnError" value="true"> 에러 시 중단</label>
      </div>
      <textarea name="prompts" rows="3" required style="width:100%;padding:8px"
        placeholder="반복: 한 줄. 순차: 한 줄에 하나씩."></textarea>
      <button type="submit" class="primary" style="margin-top:8px;padding:8px 14px">저장</button>
    </form>
  </details>
</div>

<div class="card">
  <h2 style="margin-top:0">최근 실행</h2>
  <table style="width:100%;border-collapse:collapse">
    <thead><tr style="border-bottom:1px solid #333">
      <th style="text-align:left;padding:8px">이름</th>
      <th style="text-align:left;padding:8px">모드</th>
      <th style="text-align:left;padding:8px">진행</th>
      <th style="text-align:left;padding:8px">상태</th>
      <th style="text-align:left;padding:8px">시작</th>
    </tr></thead>
    <tbody>$runRows</tbody>
  </table>
</div>
""",
            embed = embed,
        )
    }
}
