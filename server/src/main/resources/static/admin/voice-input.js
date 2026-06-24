// v1.15.0 — Console prompt 음성 입력. Web Speech API (SpeechRecognition).
//
// 브라우저 지원: Chrome / Edge / Safari 14+. Firefox 미지원 → 버튼 자동 hidden.
// HTTPS 또는 localhost 에서만 작동 (브라우저 정책). 마이크 권한 prompt.
//
// 동작:
//   - 🎤 버튼 클릭 → SpeechRecognition 시작 → 버튼에 listening 클래스(CSS 강조: 빨강+pulse).
//   - 인식 중 textarea 에 final + interim 결과 append (사용자가 이미 입력해둔 부분 뒤에).
//   - 한 번 더 클릭 또는 onend → 중지 → listening 클래스 해제.
//
// v1.108.4 — 아이콘이 이모지(🎤/⏺) → Material 'mic' 인라인 SVG 로 바뀌어, 상태는 더 이상
//   textContent 스왑이 아니라 .listening 클래스로만 표시한다(SVG 보존). "자동 전송"(발화 종료 시
//   자동 submit) 옵션도 제거 — 음성 입력은 받아쓰기만 하고 전송은 사용자가 직접 한다.
//
// v1.144.6 — 에코잉/중복 누적 수정. ko-KR / 모바일 Chrome 엔진은 같은 발화에 대해 누적
//   prefix("안녕하세요"/"안녕하세요"/"안녕하세요 음성")를 담은 여러 result item 을 동시에 들고
//   있는데, v1.94.0 의 전체 재구성이 interim 을 전부 concat(+=) 하는 바람에
//   "안녕하세요안녕하세요안녕하세요 음성" 처럼 중복됐다. 인접 result 가 prefix 관계면 더 긴
//   쪽으로 흡수하는 collapsePrefix() 로 final·interim 모두 정규화해 회수.
//
// 언어: document.documentElement.lang ("ko" / "en") 으로 ko-KR / en-US 자동 선택.

(function () {
  'use strict';

  function init() {
    var btn = document.getElementById('voice-btn');
    var input = document.getElementById('prompt-input');
    if (!btn || !input) return;

    var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) {
      // 미지원 브라우저 — 버튼 hide.
      btn.style.display = 'none';
      return;
    }
    btn.hidden = false;
    btn.style.display = '';

    var rec = new SR();
    rec.continuous = true;
    rec.interimResults = true;
    var langAttr = (document.documentElement.getAttribute('lang') || 'en').toLowerCase();
    rec.lang = langAttr.indexOf('ko') === 0 ? 'ko-KR' : 'en-US';

    var listening = false;
    var basePrefix = '';   // 인식 시작 시점의 textarea 값. 그 뒤에 결과 append.
    var finalText = '';

    function applyResult() {
      input.value = basePrefix + finalText;
      // textarea auto-grow / scrollIntoView 처리는 page 별 inline JS 가 input
      // event 리스너로 이미 처리하므로 dispatchEvent 로 트리거.
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function stopUi() {
      listening = false;
      btn.classList.remove('listening');
      btn.title = btn.dataset.titleStart || '';
    }

    // 인접한 조각이 prefix 관계면 더 긴 쪽으로 흡수해 하나로 합친다.
    //  - cur 가 prev 로 시작 → cur 가 prev 의 확장본(엔진이 같은 발화를 더 길게
    //    재전송) → prev 를 cur 로 대체.
    //  - prev 가 cur 로 시작 → cur 가 prev 의 짧은 재전송 → cur 버림.
    //  - 둘 다 아님 → 서로 다른 발화 → 이어붙임(정상 누적).
    // ko-KR / 모바일 엔진이 같은 발화를 누적 prefix 여러 item 으로 쪼개 보내는
    // ("안녕하세요"/"안녕하세요"/"안녕하세요 음성") 중복을 흡수한다.
    function collapsePrefix(parts) {
      var out = [];
      for (var k = 0; k < parts.length; k++) {
        var cur = parts[k];
        if (!cur) continue;
        if (out.length) {
          var prev = out[out.length - 1];
          if (cur.indexOf(prev) === 0) { out[out.length - 1] = cur; continue; }
          if (prev.indexOf(cur) === 0) { continue; }
        }
        out.push(cur);
      }
      return out.join('');
    }

    // v1.94.0 — 매 onresult 마다 e.results 전체를 처음부터 재구성(idempotent):
    //   이미 final 처리된 result 를 resultIndex=0 으로 재전송하는 엔진의 누적 중복
    //   방어. v1.144.6 — 거기에 더해 final·interim 을 collapsePrefix 로 정규화해
    //   "같은 발화가 누적 prefix 여러 item 으로 동시에 존재"하는 ko-KR interim 중복
    //   ("안녕하세요안녕하세요안녕하세요 음성")까지 흡수한다. final/interim 경계의
    //   중복(직전 final 을 interim 이 다시 포함)은 all 통합 collapse 로 표시값에서 제거.
    rec.onresult = function (e) {
      var finals = [];
      var all = [];
      for (var i = 0; i < e.results.length; i++) {
        var r = e.results[i];
        var t = (r[0] && r[0].transcript) || '';
        all.push(t);
        if (r.isFinal) finals.push(t);
      }
      finalText = collapsePrefix(finals);   // onend commit 용 (확정분만)
      input.value = basePrefix + collapsePrefix(all);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    rec.onend = function () {
      stopUi();
      applyResult();   // commit final text
    };
    rec.onerror = function (e) {
      console && console.warn && console.warn('voice-input:', e.error);
      stopUi();
    };

    btn.addEventListener('click', function () {
      if (listening) {
        try { rec.stop(); } catch (e) {}
        return;
      }
      basePrefix = input.value || '';
      // 사용자가 입력 끝에 공백이 없으면 한 칸 띄움 — 음성 결과가 기존 텍스트와
      // 붙어 보이지 않게.
      if (basePrefix.length > 0 && basePrefix.charAt(basePrefix.length - 1) !== ' '
          && basePrefix.charAt(basePrefix.length - 1) !== '\n') {
        basePrefix += ' ';
      }
      finalText = '';
      try {
        rec.start();
        listening = true;
        btn.classList.add('listening');
        btn.title = btn.dataset.titleStop || '';
      } catch (e) {
        console && console.warn && console.warn('voice-input start failed:', e);
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
