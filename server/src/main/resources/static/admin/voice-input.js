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

    // v1.94.0 — 매 onresult 마다 e.results 전체를 처음부터 재구성(idempotent).
    // 이전 구현은 e.resultIndex 부터 finalText 에 누적(+=)했는데, 일부 엔진
    // (특히 ko-KR / 모바일 Chrome)이 이미 final 처리된 result 를 resultIndex=0 으로
    // 재전송하면 같은 조각이 계속 덧붙어("뭔가뭔가뭔가 하다가뭔가…") 보였다.
    // e.results 는 세션 시작부터의 전체 결과 배열이므로 매번 전부 다시 읽으면
    // 재전송·인덱스 리셋에도 중복이 생기지 않는다.
    rec.onresult = function (e) {
      var finals = '';
      var interim = '';
      for (var i = 0; i < e.results.length; i++) {
        var r = e.results[i];
        if (r.isFinal) {
          finals += r[0].transcript;
        } else {
          interim += r[0].transcript;
        }
      }
      finalText = finals;
      input.value = basePrefix + finalText + interim;
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
