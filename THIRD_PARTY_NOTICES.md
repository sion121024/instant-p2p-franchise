# Third-party notices / 외부 라이브러리 고지

이 모드의 배포 jar에는 아래 라이브러리가 함께 들어 있습니다.
(배포 jar에서는 아래 라이브러리와 라이선스 원문 `licenses/`가 모드 jar 안의 `META-INF/jars/instant-p2p-kwik-0.11-r8.jar`에 들어 있습니다.) 모드 자체(kfc.udp.* 코드)는 MIT 라이선스(LICENSE)이고,
아래 라이브러리는 각자의 라이선스를 그대로 따릅니다.

| 라이브러리 | 버전 | 라이선스 | 소스 |
|---|---|---|---|
| kwik (QUIC) | 0.11 | LGPL-3.0 (licenses/LGPL-3.0.txt, GPL-3.0.txt) | https://github.com/ptrd/kwik |
| agent15 (TLS 1.3, kwik 의존성) | 3.3 | LGPL-3.0 | https://github.com/ptrd/kwik (tech.kwik:agent15) |
| hkdf | 2.0.0 | Apache-2.0 (licenses/Apache-2.0.txt) | https://github.com/patrickfav/hkdf |
| siphash (com.io7m 재패키징) | 2.0.1 | MIT | https://github.com/io7m-com/siphash-java |

## kwik / agent15 를 줄여서 넣는 방법 (LGPL 수정본 고지)

배포 jar의 kwik·agent15 클래스는 원본을 그대로 넣지 않고, 이 모드가 쓰지 않는 코드를 빌드 단계에서 잘라낸 것입니다.
소스 코드를 직접 고친 부분은 없습니다.

- 도구: Google R8 8.13.25 (안 닿는 코드 제거, 최적화, 내부 이름 줄이기)
- 방법: `tools/pack-release.py` — 이 저장소의 모드 코드가 참조하는 kwik 클래스를 출발점(keep 규칙)으로 삼아 나머지를 정리합니다.
  같은 입력(원본 kwik 0.11 / agent15 3.3 + 이 저장소)으로 누구나 같은 결과를 다시 만들 수 있습니다.
- 통신 규격은 원본과 같습니다. 원본 kwik과 서로 접속되는 것을 확인했습니다.
- 분리: 줄인 라이브러리는 모드 클래스와 섞지 않고, 모드 jar 안의 별도 jar
  `META-INF/jars/instant-p2p-kwik-0.11-r8.jar`(패브릭 jar-in-jar, 모드 id `instant-p2p-kwik`)로 들어 있습니다.
- 교체(LGPL-3.0 4조): 모드 코드는 kwik의 공개 API만 쓰고, 그 이름은 줄일 때 바꾸지 않습니다. 원본(또는 다른 버전)
  kwik을 쓰려면 원본 kwik·agent15·hkdf·siphash 클래스를 하나의 jar로 묶고 위 안쪽 jar의 `fabric.mod.json`을 그대로
  넣어, 모드 jar의 `META-INF/jars/instant-p2p-kwik-0.11-r8.jar`를 그 jar로 바꾸면 됩니다. 또는 이 저장소에서
  `tools/pack-release.py`를 거치지 않은 빌드(`versions/<버전>/build/libs`의 jar)를 쓰면 원본 kwik이 그대로 들어갑니다.
- 줄인 이름 ↔ 원래 이름 대응표(R8 mapping)는 배포판마다 따로 공개합니다(스택트레이스 해석용).
---

The distributed jar bundles the libraries above. The mod's own code is MIT-licensed (see LICENSE); each bundled
library keeps its own license. kwik and agent15 (LGPL-3.0) are included in a reduced form produced by Google R8
via `tools/pack-release.py` (unused code removed; no source modifications), shipped as a separate nested jar
(`META-INF/jars/instant-p2p-kwik-0.11-r8.jar`) so it can be replaced with the original library. The wire protocol is unchanged.
Original sources: https://github.com/ptrd/kwik
